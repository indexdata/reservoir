# Matchkeys - deepdish

This is a simple match algorithm using mostly standard keys.

The matcher will do the following:
* Check for numbers in the 020, 022, and 024 fields (in that order).
  If nothing is found it will create a dumbed down goldrush2024 key (only using title, author, publisher, date, and type).
* The 020 is reduced to a 9 digit number (removing any leading "978" or "979" strings and the check digit).
  The number is also normalized (all non-word characters removed).
* The 022 is normalized
* The 024 is normalized
* For identifier fields, the tag of the field is add to the front of the normalized number (e.g. "020_886372762")

For the goldrush2024 components: "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).

