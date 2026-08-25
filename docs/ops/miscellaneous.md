# Miscellaneous

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 4 miscellaneous.md -->
* [Gateway timeout](#gateway-timeout)
* [Touch](#touch)
* [Finalise and delete old sourceVersion](#finalise-and-delete-old-sourceversion)
* [Investigate issues with records ingest](#investigate-issues-with-records-ingest)
    * [Follow Reservoir logs with kubectl](#follow-reservoir-logs-with-kubectl)
    * [Ensure configure localIdPath when ingest record files](#ensure-configure-localidpath-when-ingest-record-files)
    * [Use xmlFixing parameter](#use-xmlfixing-parameter)
    * [Investigate the MARC data](#investigate-the-marc-data)
    * [Structural problems with MARC records](#structural-problems-with-marc-records)
    * [Split input files to isolate errors](#split-input-files-to-isolate-errors)
* [Management tasks](#management-tasks)
    * [Know the set of source identifiers](#know-the-set-of-source-identifiers)
    * [Keep track of source versions](#keep-track-of-source-versions)
    * [Assess ongoing OAI-PMH ingests](#assess-ongoing-oai-pmh-ingests)
    * [Count records regularly](#count-records-regularly)
    * [Deleting records](#deleting-records)
        * [Delete a complete source version](#delete-a-complete-source-version)
        * [Delete specific records](#delete-specific-records)
* [Follow other docs](#follow-other-docs)

## Gateway timeout

Some operations, such as pool statistics, can take a long time and so might encounter an NGINX gateway timeout.
So spin up a container in the AWS cluster to avoid the ALB/NGINX timeout.

```shell
kubectl -n test-prod run --rm -it --restart=Never debug --image=alpine:latest sh
apk add httpie gojq jq bash curl
http GET "http://reservoir:80/reservoir/config/pools/goldrush2024/stats" x-okapi-tenant:test_reservoir
# Do copy-and-paste output to local file.
```

## Touch

When to issue the "touch" command?

When records are added or updated (via ingest to Reservoir) then some clusters will change. When a sourceVersion is deleted then the clusters can change. Changed clusters will automatically trigger OAI-PMH events, and hence VuFind will be updated. In such situations the "touch" is not needed.

When the Transformer is modified for a particular tenant, then we need to trigger the Reservoir OAI-PMH feed to re-deliver all clusters that pertain to that tenant (i.e. sourceId).
Or for some reason we want VuFind to completely refresh.
So "touch" will modify the dates of all cluster records for that pool (e.g. goldrush2024).

```shell
https POST "$host/reservoir/clusters/touch?count=exact&limit=0&query=poolId==goldrush2024 AND sourceId==US-PPLAS" x-okapi-token:$token
```

NOTE: Whenever the Transformer script for a particular consortium
is modified, then its Reservoir configuration needs the [Reload transformers configuration](configure.md#reload-transformers-configuration).

## Finalise and delete old sourceVersion

When satisfied with the ingest of sourceVersion=2, then delete the old previous sourceVersion. This will leave the new sourceVersion to become the current collection.
This operation could take over 90 minutes for a large collection, and will result in NGINX Gateway timeout, but behind-the-scenes the deletion is happening.

```shell
https DELETE "$host/reservoir/records?query=sourceId=US-NNU and sourceVersion=1" x-okapi-token:$token
```

Wait a while.

There will eventually be a message in the mod-reservoir logs about the number of cluster metadata records that were modified (i.e. `Number of meta records updated =`).
For small collections wait for that, then wait a bit longer before doing the counts.

Or watch the activity via AWS CloudWatch Database Insights. Reservoir deletes the records and adjusts the affected pools.
When charts for SQL DELETE have settled, count sourceVersion=1 which should become zero:

```shell
https GET "$host/reservoir/records?limit=0&count=exact&query=sourceId=US-NNU and sourceVersion=1" x-okapi-token:$token
```

Now count without specifying the sourceVersion (which will equate to the count of sourceVersion=2)

```shell
https GET "$host/reservoir/records?limit=0&count=exact&query=sourceId=US-NNU" x-okapi-token:$token
```

## Investigate issues with records ingest

Most problems arise due to issues with the actual MARC records.

Some tips ...

### Follow Reservoir logs with kubectl

As [explained](ingest-oai-pmh.md#follow-reservoir-logs).

### Ensure configure localIdPath when ingest record files

See advice at [Ingest record files](../../README.md#ingest-record-files) about `localIdPath` parameter. Different LMS use a different field for the `localId` in the MARC records. The default is `001`.

For example Koha uses `999$c` and Horizon uses `999$a` (mostly! some are `001`).

When Reservoir commences the ingest of a file, it will report to logs for the first ~10 records "found ID ...".
If not, then there is a configuration problem or a data problem.

### Use xmlFixing parameter

Sometimes there are content issues with MARC records. Invalid characters are one such issue.
Configure the optional parameter `xmlFixing` -- If `true` then an attempt is made to remove invalid characters (e.g. control chars) from the XML input (`false` by default).

### Investigate the MARC data

There are many potential MARC content issues. Two particular tools can assist with investigation:

* [yaz-marcdump](https://software.indexdata.com/yaz/doc/yaz-marcdump.html) -- part of the [YAZ](https://www.indexdata.com/resources/software/yaz/) toolkit.
* [MarcEdit](https://marcedit.reeset.net/).

### Structural problems with MARC records

Refer to the very helpful article: \
https://bibwild.wordpress.com/2010/02/02/structural-marc-problems-you-may-encounter/

### Split input files to isolate errors

Sometimes [Ingest record files](../../README.md#ingest-record-files) will fail with no clues.
One laborious technique is to split the ingest file into smaller chunks, ingest again, split again, etc.

The "yaz-marcdump" and "MarcEdit" tools can assist.

## Management tasks

Some tips ...

### Know the set of source identifiers

Use consistent `sourceId` properties for ingest jobs, as noted for OAI-PMH ingest [job configuration](ingest-oai-pmh.md#prepare-a-job-configuration) and also for file-based ingests.

The `sourceId` of OAI-PMH ingest jobs is easy to know via the [Show all jobs status](ingest-oai-pmh.md#show-all-jobs-status) operation.
Not so easy for the file-based ingest jobs, so do keep track.

### Keep track of source versions

Note the current `sourceVersion` of each ingest source.

### Assess ongoing OAI-PMH ingests

Use a daily process to assess the [Show all jobs status](ingest-oai-pmh.md#show-all-jobs-status) operation.
After daily ingests have completed, jobs should be in "idle" state.

If any have an "error" or have been running for an unusually long time or lost their resumptionToken, then will need investigation.

However some OAI-PMH servers present an "error" message when they reach idle state, but is not actually an error. It is just their strange way of indicating that there are no more records to deliver.

### Count records regularly

Regularly count the records held for each `sourceId` to ensure no unexpected activity.

### Deleting records

#### Delete a complete source version

There will occasionally be a need to replace a collection for a specific source.
Perhaps due to the source library migrating to a new LMS.
Perhaps a problem has been discovered with their data export content.
Perhaps there was an ingest configuration issue.

Sometimes it is appropriate to just re-ingest over the top of the current `sourceVersion`.
Other times better to completely replace with a new one.

See the section [Finalise and delete old sourceVersion](#finalise-and-delete-old-sourceversion).

#### Delete specific records

See [API docs](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html#operation/deleteGlobalRecords).

Find the `globalId` of the relevant records via the methods described at [Obtain example Reservoir records](obtain-example-records.md).

Verify via its globalId:

```shell
https GET "$host/reservoir/records?query=globalId=eabcf1fa-9b11-4d83-8403-225085acaf11" x-okapi-token:$token
```

Delete via its globalId:

```shell
https DELETE "$host/reservoir/records?query=globalId=eabcf1fa-9b11-4d83-8403-225085acaf11" x-okapi-token:$token
```

Or devise a [CQL](obtain-example-records.md#search-facilities) "query" to address multiple records (with care of course).

## Follow other docs

See [README](README.md).
