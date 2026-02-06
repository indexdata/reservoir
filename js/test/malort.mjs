import fs from 'fs';
import path from 'path';
import { matchkey } from '../matchkeys/malort/malort.mjs';

function assert(result, message) {
  if (result) {
    console.log('Passed assertion');
  } else {
    console.log(`Failed assertion: Should match:\n${message}`);
  }
  return result;
}

const assertionsFile = 'test/assertions-malort.json';
const assertionsJson = fs.readFileSync(assertionsFile, 'utf8');
const assertionsMalort = JSON.parse(assertionsJson);
let testsNum = 0;
let testsFailedNum = 0;
const testsPath = 'test/records/unique';
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
    key = matchkey(payloadJsonStr);
  } catch (e) {
    key = e.message;
  }
  const assertion = assertionsMalort[testFile];
  if (!assert(key.match(assertion), assertion)) {
    testsFailedNum += 1;
  }
}
console.log(`\nProcessed ${testsNum} test files, failed ${testsFailedNum}`);
if (testsFailedNum) {
  process.exit(1);
}
