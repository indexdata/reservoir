import fs from 'fs';
import path from 'path';
import { matchkey } from '../matchkeys/deepdish/deepdish.mjs';

function assert(result, message) {
  if (result) {
    console.log('Passed assertion');
  } else {
    console.log(`Failed assertion: Should match:\n${message}`);
  }
  return result;
}
let testsNum = 0;
let testsFailedNum = 0;

const assertionsFile = 'test/assertions-deepdish.json';
const assertionsJson = fs.readFileSync(assertionsFile, 'utf8');
const assertionsDeepdish = JSON.parse(assertionsJson);
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
  const assertion = assertionsDeepdish[testFile];
  if (!assert(key === assertion, assertion)) {
    console.log(`Received:\n${key}`);
    testsFailedNum += 1;
  }
}

const assertionsFile2 = 'test/assertions-deepdish-goldrush2024.json';
const assertionsJson2 = fs.readFileSync(assertionsFile2, 'utf8');
const assertionsDeepdish2 = JSON.parse(assertionsJson2);
const testsPath2 = 'test/records';
const files2 = fs.readdirSync(testsPath2);
const testFiles2 = files2.filter((file) => path.extname(file) === '.json');
for (let n = 0; n < testFiles2.length; n += 1) {
  const testFile = `${testsPath2}/${testFiles2[n]}`;
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
  const assertion = assertionsDeepdish2[testFile];
  if (!assert(key === assertion, assertion)) {
    console.log(`Received:\n${key}`);
    testsFailedNum += 1;
  }
}

console.log(`\nProcessed ${testsNum} test files, failed ${testsFailedNum}`);
if (testsFailedNum) {
  process.exit(1);
}
