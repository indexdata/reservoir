#!/usr/bin/env bash

# Do: source okapi-token.sh

unset OKAPI_TOKEN
unset token
# token=$(python3 login_no_refresh.py -l debug)
token=$(python3 login_no_refresh.py)
if [ "${token}" != "" ]; then
  export OKAPI_TOKEN=${token}
  export token=${token}
else
  echo "CRITICAL: Could not obtain Okapi token."
fi
