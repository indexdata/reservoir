# Obtain example Reservoir records

Obtain a set of sample records for various purposes such as troubleshooting, and to assist with the task of devising the transformer, and to enable comparison with the previous sourceVersion if this is a replacement.

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 obtain-example-records.md -->
* [Ensure relevant login, and set some additional environment](#ensure-relevant-login-and-set-some-additional-environment)
* [Search facilities](#search-facilities)
* [Get random records via offset](#get-random-records-via-offset)
* [Get specific records via the localID](#get-specific-records-via-the-localid)
* [Get the related cluster records](#get-the-related-cluster-records)
* [Get the related OAI-PMH records](#get-the-related-oai-pmh-records)
* [Get records via SRU](#get-records-via-sru)
* [Search via VuFind](#search-via-vufind)
* [Follow other docs](#follow-other-docs)

## Ensure relevant login, and set some additional environment

Follow [Setup workspace and login](workspace.md).

## Search facilities

Locating records via Reservoir can only use `localId` or `globalId` or `clusterId`.
There are no general search facilities.

The query language is [CQL](https://dev.folio.org/faqs/explain-cql/) (Contextual Query Language).

## Get random records via offset

```
mkdir records

https GET "$host/reservoir/records?limit=1&offset=70&count=exact&query=sourceId=US-MNMHCL" \
  x-okapi-token:$token | jq -r '.items[]' \
  > records/1.json
```

## Get specific records via the localID

Use the [getGlobalRecords endpoint](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html#operation/getGlobalRecords).

```
https GET "$host/reservoir/records?query=localId==2673 and sourceId=US-MNMHCL" \
  x-okapi-token:$token | jq -r '.items[]' \
  > records/2.json
```

Note: For records that were ingested via OAI-PMH, the Reservoir localId is qualified with their OAI-PMH identifier.
So for example a record known to MNLINK Anoka County as localId "306669", then the Reservoir localId is "oai:anok.sirsi.net:306669".

```
https GET "$host/reservoir/records?query=localId==oai:anok.sirsi.net:306669 and sourceId=US-MNMAC" \
  x-okapi-token:$token | jq -r '.items[]' \
  > records/3.json
```

Extract the `localId` from the set of records:

```
jq -r '[.sourceId, .localId] | @tsv' records/[1-3].json
```

## Get the related cluster records

Use the [getClusters endpoint](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html#operation/getClusters) to obtain the cluster record for each globalId.

Extract the `globalId` from the set of records:

```
jq -r '.globalId' records/[1-3].json

32fdb07c-b1fa-462c-a56e-9962434b7a2a
4c7bab96-3d3c-44f4-9b6e-cdb5a07b880d
72be822a-df09-417c-9497-1176a0105b5c
```

Get each cluster record using their `globalId`:

```
https GET "$host/reservoir/clusters?matchkeyid=goldrush&query=globalId=32fdb07c-b1fa-462c-a56e-9962434b7a2a" \
  x-okapi-token:$token | gojq -r '.items[]' \
  > records/cluster-goldrush-1.json
```

## Get the related OAI-PMH records

Extract the `clusterId` from the set of cluster records:

```
jq -r '.clusterId' records/cluster-goldrush-[1-3].json

c1b15f92-3729-4684-9ff5-77b3e4569e9e
c7b565b0-585f-4f3f-a7e0-f70ba892c512
87455791-aa5b-4964-a6af-29cf986d481c
```

Get the OAI records using each `clusterId`:

```
https GET "$host/_/invoke/tenant/${tenant}/reservoir/oai?verb=GetRecord&identifier=c1b15f92-3729-4684-9ff5-77b3e4569e9e" \
  > records/oai-cluster-goldrush-1.xml
```

## Get records via SRU

Use [SRU](https://www.loc.gov/standards/sru/) Search/Retrieve via URL.

The only supported index is `rec.id` and uses the `clusterId`:

```
https GET "$host/_/invoke/tenant/${tenant}/reservoir/sru?version=2.0&operation=searchRetrieve&recordSchema=marcxml&maximumRecords=1&query=rec.id=c1b15f92-3729-4684-9ff5-77b3e4569e9e" \
  x-okapi-tenant:$tenant \
  > records/sru-cluster-goldrush-1.xml
```

## Search via VuFind

Locating records via Reservoir can only use `localId` or `globalId` or `clusterId` (as shown earlier in this document).
There are no general search facilities.

Use the power of VuFind search.

When a specific record has been located, then its VuFind URL has the Reservoir clusterId:

```
.../Record/<clusterId>
```

Using that clusterId, obtain the cluster record from Reservoir as shown earlier in this document.
Then get the actual Reservoir records using their globalId.

## Follow other docs

See [README](README.md).

