# JavaScript facilities

## Matchkeys

Matchkeys utilise some specific elements from MARC bibliographic records to generate a unique string which identifies common records that describe the same instance.

The various matchkeys implementations are explained at [js/matchkeys](matchkeys).

## Transformers

Example transformer that collects MARC fields from all member records and creates field `999_10` for each with: sourceId, localId and globalId.

## Verify matchkeys development

Do 'npm install' to install and configure ESLint and Prettier.

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
