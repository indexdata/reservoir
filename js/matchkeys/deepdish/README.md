# Matchkeys - deepdish

This matchkey returns an array of matches, using goldrush2024 and standard identifier numbers.

The matcher will do the following:

- Create a matchkey based on the goldrush2024 algorithm.
- Check for numbers in the 020 (International Standard Book Number ISBN) and 022 (International Standard Serial Number ISSN) and 024 (Other Standard Identifier) fields (in that order).
- The 020 is reduced to a 9 digit number (removing any leading "978" or "979" strings and the check digit).
  The number is also normalized (all non-word characters removed).
- The 022 is normalized
- The 024 is normalized
- For identifier fields, the tag of the field is added to the front of the normalized number (e.g. "020_886372762")
- Returns an array containing: goldrush2024 matchkey, identifier matchkey, identifier matchkey...

The purpose of multiple match key values is that _any_ of the values will be enough to merge with another cluster with same match key value (it is like a union | OR).

Suppose record ‘a' returns keys ‘1', '2’. Record ‘b' returns key ‘3’. We now have two clusters as none of the values overlap. Suppose we get a third record 'c' with keys ‘2’, ‘3’. The result is one cluster with records ‘a', ‘b’, ‘c’, with saved values ‘1', ‘2, '3’.

For the goldrush2024 components: "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).
