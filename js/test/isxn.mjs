import fs from 'fs';
import path from 'path';
import { matchkey, normIsbn, normIssn } from '../matchkeys/isxn/isxn.mjs';

function assert(result, message) {
  if (result) {
    console.log('Passed assertion');
  } else {
    console.log(`Failed assertion: Should match:\n${message}`);
  }
  return result;
}

const assertionsFile = 'test/assertions-isxn.json';
const assertionsJson = fs.readFileSync(assertionsFile, 'utf8');
const assertionsObj = JSON.parse(assertionsJson);
let testsNum = 0;
let testsFailedNum = 0;
const testsPath = 'test/records/numbers';
const files = fs.readdirSync(testsPath);
const testFiles = files.filter((file) => path.extname(file) === '.json');
for (let n = 0; n < testFiles.length; n += 1) {
  const testFile = `${testsPath}/${testFiles[n]}`;
  let key = '';
  testsNum += 1;
  console.log(`\nProcessing ${testFile}`);
  const marcJsonStr = fs.readFileSync(testFile, 'utf8');
  try {
    const marcJson = JSON.parse(marcJsonStr);
    const payloadJson = { marc: marcJson };
    const payloadJsonStr = JSON.stringify(payloadJson);
    let keyArr = matchkey(payloadJsonStr);
    key = JSON.stringify(keyArr);
  } catch (e) {
    key = e.message;
  }
  const assertion = assertionsObj[testFile];
  if (!assert(key === assertion, assertion)) {
    console.log(`Received:\n${key}`);
    testsFailedNum += 1;
  }
}

let isbn_term = {
  term: '978-3-16-148410-0',
  field: 'isbn',
}

let kayAr = normIsbn(JSON.stringify(isbn_term));
let expected = '9783161484100';
if (!assert(kayAr[0] === expected, `Should match:\n${expected}`)) {
  console.log(`Received:\n${kayAr[0]}`);
  testsFailedNum += 1;
}

let issn_term = {
  term: '1432-069X',
  field: 'issn',
}

kayAr = normIssn(JSON.stringify(issn_term));
expected = '1432069X';
if (!assert(kayAr[0] === expected, `Should match:\n${expected}`)) {
  console.log(`Received:\n${kayAr[0]}`);
  testsFailedNum += 1;
}

console.log(`\nProcessed ${testsNum} test files, failed ${testsFailedNum}`);
if (testsFailedNum) {
  process.exit(1);
}
