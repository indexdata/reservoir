// Generates deepdish match key.

const numFields = ['020', '022', '024'];

function loadMarcJson(record) {
  const marcObj = JSON.parse(record).marc;
  if (marcObj.fields === undefined) {
    throw new Error('MARC fields array is missing.');
  }
  if (!Array.isArray(marcObj.fields)) {
    throw new Error('MARC fields is not an array.');
  }
  if (!marcObj.leader) {
    marcObj.leader = '00000nam a22000000a 4500';
  }
  return marcObj;
}

function hasField(record, tag) {
  let result = false;
  result = record.fields.some((f) => f[tag]);
  return result;
}

function getField(record, tag, sf) {
  // Get the first relevant field or subfield.
  let data = null;
  const fields = record.fields.filter((f) => f[tag]);
  // Use the first relevant field
  const f = fields[0];
  if (f !== undefined) {
    if (f[tag].subfields) {
      for (let n = 0; n < f[tag].subfields.length; n += 1) {
        const s = f[tag].subfields[n];
        if (s[sf]) {
          data = s[sf];
          // Use the first relevant subfield
          break;
        }
      }
    } else {
      data = f[tag];
    }
  }
  return data;
}

function getRelevantSubField(record, tag, sf) {
  // Get the first repeating field that has the relevant subfield.
  let data = null;
  const fields = record.fields.filter((f) => f[tag]);
  loop1: for (let x = 0; x < fields.length; x += 1) {
    const f = fields[x];
    if (f[tag].subfields) {
      for (let n = 0; n < f[tag].subfields.length; n += 1) {
        const s = f[tag].subfields[n];
        if (s[sf]) {
          data = s[sf];
          // Use the first relevant subfield
          break loop1;
        }
      }
    }
  }
  return data;
}

function stripPunctuation(keyPart, replaceChar) {
  let trimmed = keyPart;
  trimmed = trimmed.replace(/%22/g, '_');
  trimmed = trimmed.replace(/%/g, '_');
  trimmed = trimmed.replace(/^ *[aA] +/, '');
  trimmed = trimmed.replace(/^ *[aA]n +/, '');
  trimmed = trimmed.replace(/^ *[tT]he +/, '');
  trimmed = trimmed.replace(/['{}]/g, '');
  trimmed = trimmed.replace(/&/g, 'and');
  trimmed = trimmed.replace(/\u0020/g, replaceChar);
  trimmed = trimmed.replace(/\u0021/g, replaceChar);
  trimmed = trimmed.replace(/\u0022/g, replaceChar);
  trimmed = trimmed.replace(/\u0023/g, replaceChar);
  trimmed = trimmed.replace(/\u0024/g, replaceChar);
  trimmed = trimmed.replace(/\u0028/g, replaceChar);
  trimmed = trimmed.replace(/\u0029/g, replaceChar);
  trimmed = trimmed.replace(/\u002A/g, replaceChar);
  trimmed = trimmed.replace(/\u002B/g, replaceChar);
  trimmed = trimmed.replace(/\u002C/g, replaceChar);
  trimmed = trimmed.replace(/\u002D/g, replaceChar);
  trimmed = trimmed.replace(/\u002E/g, replaceChar);
  trimmed = trimmed.replace(/\u002F/g, replaceChar);
  trimmed = trimmed.replace(/\u003A/g, replaceChar);
  trimmed = trimmed.replace(/\u003B/g, replaceChar);
  trimmed = trimmed.replace(/\u003C/g, replaceChar);
  trimmed = trimmed.replace(/\u003D/g, replaceChar);
  trimmed = trimmed.replace(/\u003E/g, replaceChar);
  trimmed = trimmed.replace(/\u003F/g, replaceChar);
  trimmed = trimmed.replace(/\u0040/g, replaceChar);
  trimmed = trimmed.replace(/\u005B/g, replaceChar);
  trimmed = trimmed.replace(/\\/g, replaceChar);
  trimmed = trimmed.replace(/\u005D/g, replaceChar);
  trimmed = trimmed.replace(/\u005E/g, replaceChar);
  trimmed = trimmed.replace(/\u005F/g, replaceChar);
  trimmed = trimmed.replace(/\u0060/g, replaceChar);
  trimmed = trimmed.replace(/\u007C/g, replaceChar);
  trimmed = trimmed.replace(/\u007E/g, replaceChar);
  trimmed = trimmed.replace(/\u00A9/g, replaceChar);
  return trimmed;
}

function normalizeAndUnaccent(fieldData) {
  let fieldStr = fieldData;
  if (fieldData !== null) {
    fieldStr = fieldData.normalize('NFD').replace(/\p{Diacritic}/gu, '');
  }
  return fieldStr;
}

function padContent(keyPart, length) {
  let padded = keyPart;
  padded = padded.replace(/ +/g, ' ');
  padded = padded.replace(/ /g, '_');
  padded = padded.substring(0, length).padEnd(length, '_');
  return padded;
}

function doTitle(fieldData) {
  // FIXME: Handle the note of the spec 245$6
  let fieldStr = '';
  for (let n = 0; n < fieldData.length; n += 1) {
    if (fieldData[n] !== null) {
      fieldStr += stripPunctuation(fieldData[n], ' ').trim();
    }
  }
  fieldStr = normalizeAndUnaccent(fieldStr.replace(/ /g, ''));
  return padContent(fieldStr, 70);
}

function doPublicationYear(fieldData) {
  let fieldStr = '';
  for (let n = 0; n < fieldData.length; n += 1) {
    if (fieldData[n] !== null) {
      let dataStr = '';
      if (n === 0) {
        const dateType = `${fieldData[n]}`.substring(6, 7);
        if (dateType === 'r') {
          // Try for date1 from field 008 -- reissue
          dataStr = `${fieldData[n]}`.substring(7, 11).replace(/[^0-9]/g, '');
          if (dataStr.match(/[1-9][0-9]{3}/) && dataStr !== '9999') {
            fieldStr = dataStr;
            break;
          }
        } else {
          // Try for date2 from field 008
          dataStr = `${fieldData[n]}`.substring(11, 15).replace(/[^0-9]/g, '');
          if (dataStr.match(/[1-9][0-9]{3}/) && dataStr !== '9999') {
            fieldStr = dataStr;
            break;
          }
        }
      } else if (n === 1) {
        // Try for date from field 264$c
        dataStr = `${fieldData[n]}`.replace(/[^0-9]/g, '');
        if (dataStr.match(/[1-9][0-9]{3}/) && dataStr !== '9999') {
          fieldStr = dataStr;
          break;
        }
      } else {
        // Try for date from field 260$c
        dataStr = `${fieldData[n]}`.replace(/[^0-9]/g, '');
        if (dataStr.match(/[1-9][0-9]{3}/) && dataStr !== '9999') {
          fieldStr = dataStr;
          break;
        }
      }
    }
  }
  if (!fieldStr) {
    fieldStr = '0000';
  }
  return padContent(fieldStr, 4);
}

function doPublisherName(fieldData) {
  let fieldStr = '';
  for (let n = 0; n < fieldData.length; n += 1) {
    if (fieldData[n] !== null) {
      if (n === 0) {
        // Try first for field 264$b
        fieldStr = normalizeAndUnaccent(fieldData[n]).toLowerCase();
        break;
      } else {
        // Try then for field 260$b
        fieldStr = normalizeAndUnaccent(fieldData[n]).toLowerCase();
      }
    }
  }
  fieldStr = stripPunctuation(fieldStr, ' ').replace(/ /g, '');
  return padContent(fieldStr, 5);
}

function doTypeOfRecord(fieldData) {
  let fieldStr = '';
  if (fieldData.length > 10) {
    fieldStr = fieldData.substring(6, 7);
  }
  return fieldStr;
}

function doAuthor(fieldData) {
  let fieldStr = '';
  for (let n = 0; n < fieldData.length; n += 1) {
    if (fieldData[n] !== null) {
      let dataStr = stripPunctuation(fieldData[n], '');
      dataStr = normalizeAndUnaccent(dataStr);
      fieldStr += dataStr;
    }
  }
  return padContent(fieldStr.replace(/[^a-zA-Z0-9]/g, ''), 5);
}

function doElectronicIndicator(marcObj) {
  let field = '';
  field = normalizeAndUnaccent(getRelevantSubField(marcObj, '245', 'h'));
  if (field) {
    if (field.match(/\belectronic resource\b/i)) {
      return 'e';
    }
  }
  field = normalizeAndUnaccent(getRelevantSubField(marcObj, '590', 'a'));
  if (field) {
    if (field.match(/\belectronic reproduction\b/i)) {
      return 'e';
    }
  }
  field = normalizeAndUnaccent(getRelevantSubField(marcObj, '533', 'a'));
  if (field) {
    if (field.match(/\belectronic reproduction\b/i)) {
      return 'e';
    }
  }
  field = normalizeAndUnaccent(getRelevantSubField(marcObj, '300', 'a'));
  if (field) {
    if (field.match(/\bonline resource\b/i)) {
      return 'e';
    }
  }
  field = getField(marcObj, '007');
  if (field) {
    if (field.substring(0, 1) === 'c') {
      return 'e';
    }
  }
  // RDA
  field = getField(marcObj, '337', 'a');
  if (field) {
    if (field.substring(0, 1) === 'c') {
      return 'e';
    }
  }
  // other electronic document
  if (hasField(marcObj, '086') && hasField(marcObj, '856')) {
    return 'e';
  }
  return 'p';
}

function addComponent(component) {
  // Assist debug
  const debug = false;
  let delimiter = '';
  if (debug) {
    delimiter = '|';
  }
  return `${delimiter}${component}`;
}

function doStandardNum(snum) {
  let { num } = snum;
  num = num.replace(/\W/g, '');
  // let's convert all isbn to 9 digits
  if (snum.tag === '020') {
    num = num.replace(/.$/, '');
    num = num.replace(/^97./, '');
  }
  return `${snum.tag}_${num}`;
}

function doAuthorTitle(marcObj) {
  let keyStr = '';
  keyStr += addComponent(
    doTitle([
      getRelevantSubField(marcObj, '245', 'a'),
      getRelevantSubField(marcObj, '245', 'b'),
      getRelevantSubField(marcObj, '245', 'p'),
    ])
  );
  keyStr += addComponent(
    doPublicationYear([
      getField(marcObj, '008'),
      getRelevantSubField(marcObj, '264', 'c'),
      getRelevantSubField(marcObj, '260', 'c'),
    ])
  );
  keyStr += addComponent(
    doPublisherName([
      getRelevantSubField(marcObj, '264', 'b'),
      getRelevantSubField(marcObj, '260', 'b'),
    ])
  );
  keyStr += addComponent(doTypeOfRecord(marcObj.leader));
  keyStr += addComponent(
    doAuthor([
      getField(marcObj, '100', 'a'),
      getField(marcObj, '110', 'a'),
      getField(marcObj, '111', 'a'),
      getField(marcObj, '130', 'a'),
    ])
  );
  keyStr += addComponent(doElectronicIndicator(marcObj));
  return keyStr;
}

/**
 * Generates deepdish match key.
 *
 * The functions apart from doStandardNum() are based on
 * the GoldRush specification December2024_0
 *
 * @version 1.0.0
 * @param {string} record - The MARC-in-JSON input string wrapped in {marc: ...} object.
 * @return {string} The matchkey. Components are gathered from relevant fields
 *     and concatenated to a long string.
 */
export function matchkey(record) {
  let keyStr = '';
  const snum = {};
  const marcObj = loadMarcJson(record);
  for (let x = 0; x < numFields.length; x += 1) {
    const tag = numFields[x];
    snum.num = getRelevantSubField(marcObj, tag, 'a');
    snum.tag = tag;
    if (snum.num) break;
  }
  if (snum.num) {
    keyStr = doStandardNum(snum);
  } else {
    keyStr = doAuthorTitle(marcObj);
  }
  keyStr = keyStr.toLowerCase();
  // console.log(keyStr);
  return keyStr;
}
