# Miscellaneous

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 miscellaneous.md -->
* [Gateway timeout](#gateway-timeout)
* [Touch](#touch)
* [Finalise and delete old sourceVersion](#finalise-and-delete-old-sourceversion)
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

## Follow other docs

See [README](README.md).
