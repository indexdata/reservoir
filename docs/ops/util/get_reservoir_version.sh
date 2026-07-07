#!/usr/bin/env bash

commit=$(curl -s -S https://api.github.com/repos/indexdata/reservoir/commits/master | jq -r '.sha')
sha_short_commit=$(echo "${commit}" | cut -c 1-7)
deployment=$(https GET "$host/_/invoke/tenant/$tenant/reservoir" | jq -r '.revision')
sha_short_deployment=$(echo "${deployment}" | cut -c 1-7)
url_commit="https://github.com/indexdata/reservoir/commit/${sha_short_commit}"
url_deployment="https://github.com/indexdata/reservoir/commit/${sha_short_deployment}"

printf "%12s %s\n" "last commit:" "${commit}"
printf "%12s %s %s\n" "sha short:" "${sha_short_commit} : ${url_commit}"
printf "%12s %s %s\n" "deployment:" "${deployment} : ${url_deployment}"
