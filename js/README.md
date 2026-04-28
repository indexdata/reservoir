# JavaScript facilities

## Table of contents

<!-- md2toc -l 2 -h 3 README.md -->
* [Matchkeys](#matchkeys)
* [Transformers](#transformers)
    * [999 subfield definitions](#999-subfield-definitions)
* [Verify matchkeys development](#verify-matchkeys-development)
    * [prettier](#prettier)
    * [lint](#lint)
    * [Conduct tests](#conduct-tests)

## Matchkeys

Matchkeys utilise some specific elements from MARC bibliographic records to generate a unique string which identifies common records that describe the same instance.

The various matchkeys implementations are explained at [js/matchkeys](matchkeys).

## Transformers

A basic example transformer is provided at [transformers](transformers) which
collects MARC fields from all member records and creates field `999_10` for each with: sourceId, localId and globalId.

### 999 subfield definitions

#### 999 10 (source holdings record)
```
{
  "i": "clusterId",
  "l": "localId",
  "s": "sourceId",
  "m": "matchKey"
}
```

#### 999 11 (library items)
```
{
  "a": "location",
  "b": "barcode",
  "c": "callNumber",
  "d": "callNumberType",
  "g": "copy",
  "i": "institutionName",
  "k": "numberOfPieces",
  "l": "localId",
  "n": "enumeration",
  "p": "policy",
  "s": "sourceId",
  "t": "type",
  "u": "chronology",
  "v": "volume",
  "w": "yearCaption",
  "x": "itemMaterialType",
  "y": "itemId"
}
```

#### 999 12 (online items)
```
{
  "i": "instutionName",
  "l": "localId",
  "s": "sourceId",
  "t": "type",
  "u": "uri",
  "r": "rights",
  "x": "nonPublicNote",
  "z": "publicNote"
}
```

#### 999 13 (vendor entries)
```
{
  "a": "fullVendorName",
  "b": "price",
  "c": "currencyCode",
  "e": "priceNote",
  "i": "vendor",
  "j": "countryCode",
  "l": "localId",
  "s": "sourceId",
  "t": "type",
  "z": "availability"
}
```


## Verify matchkeys development

Do 'npm install' to install and configure ESLint and Prettier.

See all available scripts listed in the [package.json](package.json) file.

Prior to commit, do the following steps:

### prettier

See if any files need to be re-formatted:

```
npm run prettier-check
```

If so then do re-format:

```
npm run prettier
```

### lint

Then assess code quality with ESLint:

```
npm run lint
```

If rules are contravened (see rule explanations at [ESLint](https://eslint.org/docs/latest/rules/) and [eslint-plugin-import](https://github.com/import-js/eslint-plugin-import)) then manually fix them.
If any are highlighted as automatically fixable, then can do:

```
npm run lint -- --fix
```

### Conduct tests

Ensure that the matchkey tests do pass.

There are some sample MARC files in the [js/test/records](test/records) directory.
Each matchkey has a set of assertions in the [js/test](test) directory.

For example do:

```
npm run test-goldrush2024
```
