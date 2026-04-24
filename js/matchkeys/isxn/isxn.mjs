// matchkey for ISBN/ISSN and normalize terms for searching via CQL on SRU

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

function getRelevantSubFields(record, tag, sf) {
  let data = [];
  const fields = record.fields.filter((f) => f[tag]);
  for (let x = 0; x < fields.length; x += 1) {
    const f = fields[x];
    if (f[tag].subfields) {
      for (let n = 0; n < f[tag].subfields.length; n += 1) {
        const s = f[tag].subfields[n];
        if (s[sf]) {
          data.push(s[sf]);
        }
      }
    }
  }
  if (data.length === 0) {
    return null;
  }
  return data;
}

/**
 * ISBN/ISSN match key generation function. Takes a MARC-in-JSON record as input and generates
 * a match key string based on bibliographic fields.
 * @param {string} record - The MARC-in-JSON input string wrapped in {marc: ...} object.
 * @return {array} ISBN/ISSN normalized. Empty if no ISSN/ISBN.
 */
export function matchkey(record) {
  const marcObj = loadMarcJson(record);

  let isbn = getRelevantSubFields(marcObj, '020', 'a');
  if (isbn) {
    for (let n = 0; n < isbn.length; n += 1) {
      isbn[n] = isbn[n].replace(/[^0-9Xx]/g, '').toUpperCase();
    }
    return isbn;
  }
  let issn = getRelevantSubFields(marcObj, '022', 'a');
  if (issn) {
    for (let n = 0; n < issn.length; n += 1) {
      issn[n] = issn[n].replace(/[^0-9Xx]/g, '').toUpperCase();
    }
    return issn;
  }
  return [];
}

/**
 * Normalize CQL query term for ISBN. Takes a CQL query string as input and normalizes it by
 * removing non-numeric characters and converting to uppercase.
 * @param {*} node string which decodes to object with property "term" containing the CQL query string
 * and "field" containing the CQL query field.
 * @return an array with the normalized ISBN as the only element. Empty if no valid ISBN.
 */
export function normIsbn(node) {
  const obj = JSON.parse(node);
  let isbn = obj.term;
  isbn = isbn.replace(/[^0-9Xx]/g, '');
  return [isbn.toUpperCase()];
}

/**
 * Normalize CQL query term for ISSN. Takes a CQL query string as input and normalizes it by
 * removing non-numeric characters and converting to uppercase.
 * @param {*} node string which decodes to object with property "term" containing the CQL query string
 * and "field" containing the CQL query field.
 * @return an array with the normalized ISSN as the only element. Empty if no valid ISSN.
 */
export function normIssn(node) {
  const obj = JSON.parse(node);
  let issn = obj.term;
  issn = issn.replace(/[^0-9Xx]/g, '');
  return [issn.toUpperCase()];
}
