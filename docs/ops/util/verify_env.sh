#!/usr/bin/env bash

# Source this script to verify the login details.

echo "host=${host}"
echo "tenant=${tenant}"
echo "username=${username}"
consortium=${host%%-*}
export consortium
echo "consortium=${consortium}"
echo "job=${job}"

if [ "${consortium}" == "" ]; then
  echo "Missing 'consortium'. Do login and verify again."
  echo "  Or alternatively, set the environment specifically."
fi

echo "${token}" | grep --quiet "^ey"
if [ $? -ne 0 ]; then
  echo "Missing 'token'. Do login and verify again."
else
  echo "The Okapi token is set."
  echo "If this is not the expected environment setup, then do login and verify again."
fi
