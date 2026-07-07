# Reservoir ingest OAI-PMH

This document explains configuration and running [OAI-PMH](https://www.openarchives.org/OAI/openarchivesprotocol.html) ingest jobs.

Also refer to the [Reservoir README](https://github.com/indexdata/reservoir/blob/master/README.md).

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 ingest-oai-pmh.md -->
* [Ensure relevant login, and set some additional environment](#ensure-relevant-login-and-set-some-additional-environment)
* [Verify connection details and OAI responses](#verify-connection-details-and-oai-responses)
* [Prepare a job configuration](#prepare-a-job-configuration)
* [Show all jobs status](#show-all-jobs-status)
* [Add a job configuration](#add-a-job-configuration)
* [Follow mod-reservoir logs](#follow-mod-reservoir-logs)
* [Start a job](#start-a-job)
* [Review job status](#review-job-status)
* [Update a job configuration](#update-a-job-configuration)
* [Delete a job](#delete-a-job)
* [Count the reservoir records](#count-the-reservoir-records)
* [Remove old sourceVersion](#remove-old-sourceversion)
* [Manage ongoing ingests](#manage-ongoing-ingests)
* [Follow other docs](#follow-other-docs)

## Ensure relevant login, and set some additional environment

Follow [Setup workspace and login](workspace.md).

## Verify connection details and OAI responses

Before commencing, ensure that the customer has provided the necessary details, which include the URL (and perhaps authentication credentials), the metadataPrefix, and the set name.

Ensure verbs `Identify` and `ListSets` and `ListMetadataFormats` and `ListRecords` (and with resumptionToken).

Public resources are often useable via the web browser.

## Prepare a job configuration

For the Reservoir "sourceId" we use the upper-case [ISIL](https://en.wikipedia.org/wiki/International_Standard_Identifier_for_Libraries_and_Related_Organizations) (International Standard Identifier for Libraries and Related Organizations). These often utilise the "MARC organization code" (use the LoC [facility](https://www.loc.gov/marc/organizations/org-search.php)).

For the Reservoir "job identifier" we use a lower-case string composed with a short-name for the institution and the ISIL, e.g. `umn-us-mnu`.

```json
{
  "id": "umn-us-mnu",
  "metadataPrefix": "marc21",
  "set": "reshare",
  "sourceId": "US-MNU",
  "sourceVersion": 1,
  "url": "https://obscured-url"
}
```

Save that job descriptor as the file `umn-us-mnu.json`

## Show all jobs status

```
https GET $host/reservoir/pmh-clients/_all/status x-okapi-token:$token
```

## Add a job configuration

Add an environment variable for the job identifier:

```
export job=umn-us-mnu
```

Post the job descriptor:

```
https POST $host/reservoir/pmh-clients x-okapi-token:$token < ${job}.json
```

Inspect the initial job status:

```
https GET $host/reservoir/pmh-clients/${job)/status x-okapi-token:$token
```

## Follow mod-reservoir logs

```
kubectl -n test-prod get pods | grep reservoir
kubectl -n test-prod logs --tail=200 --follow=true reservoir-...
# Or when multiple replicas, do this:
kubectl -n test-prod logs --selector app.kubernetes.io/name=reservoir --tail=5000 --timestamps --follow
```

## Start a job

```
https POST $host/reservoir/pmh-clients/${job}/start x-okapi-token:$token
```

## Review job status

```
https GET $host/reservoir/pmh-clients/${job}/status x-okapi-token:$token
```

## Update a job configuration

For example to clear its "from" date, so as to re-ingest (e.g. perhaps there was some complication with their initial ingest):

```
https PUT $host/reservoir/pmh-clients/${job} x-okapi-token:$token ${job}.json
```

and then "start" the job again.

## Delete a job

If needed then delete the job configuration (not usually needed).

```
https DELETE $host/reservoir/pmh-clients/${job} x-okapi-token:$token
```

If needed, then delete its records:

```
https GET "$host/reservoir/records?limit=0&count=exact&query=sourceId=US-MNU" x-okapi-token:$token
https DELETE "$host/reservoir/records?query=sourceId=US-MNU and sourceVersion=1" x-okapi-token:$token
https GET "$host/reservoir/records?limit=0&count=exact&query=sourceId=US-MNU" x-okapi-token:$token
```

## Count the reservoir records

If this was a new ingest job, then do a default count or use `sourceVersion=1`.
Otherwise specify the relevant `sourceVersion` value.

```
https GET "$host/reservoir/records?limit=0&count=exact&query=sourceId=US-MNU and sourceVersion=1" x-okapi-token:$token
```

## Remove old sourceVersion

If this is a new sourceVersion to replace the previous, then when happy do delete the previous sourceVersion.
This makes the new sourceVersion become the current collection.

## Manage ongoing ingests

Investigate the job status. It will probably be "idle" meaning that the remote server has finished delivering records and is now up-to-date (otherwise would still be "running").

```
https GET $host/reservoir/pmh-clients/${job}/status x-okapi-token:$token
```

Use a daily cronjob to start all jobs. Or devise a method to start jobs in groups for a large consortium.

```
https POST $host/reservoir/pmh-clients/_all/start x-okapi-token:$token
```

## Follow other docs

See [README](README.md).

