# Matchkeys - deepdish

This is a simple match algorithm using mostly standard keys and also Goldrush2024.

The matcher will do the following:

- Create a matchkey based on the Goldrush2024 algorithm.
- Check for numbers in the 020, 022, and 024 fields (in that order).
- The 020 is reduced to a 9 digit number (removing any leading "978" or "979" strings and the check digit).
  The number is also normalized (all non-word characters removed).
- The 022 is normalized
- The 024 is normalized
- For identifier fields, the tag of the field is add to the front of the normalized number (e.g. "020_886372762")
- Returns and array containing: Goldrush2024 matchkey, identifier matchkey, identifier matchkey...

For the goldrush2024 components: "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).
