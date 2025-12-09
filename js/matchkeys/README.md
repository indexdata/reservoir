# Matchkeys facilities - JavaScript

## Overview

Matchkeys utilise some specific elements from MARC bibliographic records to generate a unique string which identifies common records that describe the same instance.

The matchkeys in this directory are implemented with JavaScript.

Takes input being a MARC-in-JSON string of MARC fields, and returns the matchkey string.

Each component of the matchkey is padded with the underscore character to fill to its field width.

## Matchkeys implementations

### goldrush

The [js/matchkeys/goldrush](goldrush) implements the "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated September 2021).

### goldrush2024

The [js/matchkeys/goldrush2024](goldrush2024) implements the "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).

### deepdish

The [js/matchkeys/deepdish](deepdish) utilises fields "020 International Standard Book Number (ISBN)" and "022 International Standard Serial Number (ISSN)" and "024 Other Standard Identifier". If those fields are not found then it utilises some specific components of the "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).

## Matchkeys tests of development code

Do 'cd ..' to change to the 'reservoir/js' directory.
Follow the instructions there.
