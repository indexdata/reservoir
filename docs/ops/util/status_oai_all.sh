#!/usr/bin/env bash

show_usage() {
  cat << EOU
Usage: ${0##*/}
Summary of status of current OAI-PMH Reservoir ingest jobs.

Required:
  Okapi token as environment variable: token

Dependencies: httpie, and gojq (or jq)

EOU
}

options_okay=true
cmd_httpie=$(command -v https)
if [ ! "${cmd_httpie}" ]; then
  echo "ERROR: Could not find 'https' (httpie)." >&2
  options_okay=false
fi
cmd_jq=$(command -v gojq)
if [ ! "${cmd_jq}" ]; then
  cmd_jq=$(command -v jq)
  if [ ! "${cmd_jq}" ]; then
    echo "ERROR: Could not find 'gojq' or 'jq'" >&2
    options_okay=false
  fi
fi
if [ -z $token ]; then
  echo "ERROR: Missing Okapi token. Ensure login." >&2
  options_okay=false
fi
if ! $options_okay; then
  show_usage >&2
  exit 2
fi

temp_pn=$(mktemp)
report_header=$(cat << EORH
----------------------------------------------------------------------------------------------
status   jobID                      sourceId     lastTotalRecords  lastActiveTimestamp  error?
----------------------------------------------------------------------------------------------
EORH
)
length_report_header=$(echo "${report_header}" | wc -l | sed -E 's/^ *//')
printf "%s\n" "${report_header}"

status=$(${cmd_httpie} GET "${host}/reservoir/pmh-clients/_all/status" x-okapi-token:"${token}")
# FIXME: ensure status not empty
result=$(echo "${status}" \
  | ${cmd_jq} -r '.items[] | [ .status, .config.id, .config.sourceId, .lastTotalRecords, .lastActiveTimestamp, "\"" + (.error? | tostring) + "\"" ] | @tsv'
)
while IFS=$'\t' read -r job_status job_id source_id last_total active error; do
  time_active=${active%.*}  # strip fraction of seconds
  # Trim some known error messages
  if [[ ${error} =~ noRecordsMatch ]]; then
    error="noRecordsMatch"
  fi
  if [[ ${error} =~ "badArgument: Missing required parameters: metadataPrefix" ]]; then
    error="folio-metadataPrefix"
  fi
  if [[ ${error} =~ "Illegal processing instruction target" ]]; then
    error="double-final-xml"
  fi
  if [ "${error}" == '"null"' ]; then
    error=""
  fi
  printf "%-8s %-26s %-12s %16s %20s %-s\n" "${job_status}" "${job_id}" "${source_id}" "${last_total}" "${time_active}" "${error}" >> "${temp_pn}"
done <<<"${result}"
cat ${temp_pn} | sort -k 1,2
rm ${temp_pn}
