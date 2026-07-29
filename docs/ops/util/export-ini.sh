#!/usr/bin/env bash

# Use INI files to set and export environment variables.
#
# These same files are also used by the Python scripts via ConfigParser ExtendedInterpolation
# and by other shell scripts.
#
# Typical invocation:
#   CONFIG=config-mnlink.ini source export-ini.sh

set -o allexport
if [[ -r $CONFIG ]]; then
  source <(grep = $CONFIG | sed 's/ *= */=/')
else
  echo "ERROR: CONFIG: INI file does not exist or not provided." >&2
fi
set +o allexport
