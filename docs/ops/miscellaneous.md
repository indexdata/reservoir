# Miscellaneous

## Gateway timeout

Some operations, such as matchkeys statistics, can take a long time and so might encounter an NGINX gateway timeout.
So spin up a container in the AWS cluster to avoid the ALB/NGINX timeout.

```
kubectl -n test-prod run --rm -it --restart=Never debug --image=alpine:latest sh
apk add httpie gojq jq bash curl
http GET "http://reservoir:80/reservoir/config/matchkeys/goldrush2024/stats" x-okapi-tenant:test_reservoir
# Do copy-and-paste output to local file.
```

## Touch

When to issue the "touch" command?

When records are added or updated (via ingest to Reservoir) then some clusters will change. When a sourceVersion is deleted then the clusters can change. Changed clusters will automatically trigger OAI-PMH events, and hence VuFind will be updated. In such situations the "touch" is not needed.

When the Transformer is modified for a particular tenant, then we need to trigger the Reservoir OAI-PMH feed to re-deliver all clusters that pertain to that tenant (i.e. sourceId).
Or for some reason we want VuFind to completely refresh.
So "touch" will modify the dates of all cluster records for that MatchKey (e.g. goldrush2024).

```
https POST "$host/reservoir/clusters/touch?count=exact&limit=0&query=matchkeyId==goldrush2024 AND sourceId==US-PPLAS" x-okapi-token:$token
```

NOTE: Whenever the Transformer script for a particular consortium
is modified, then its Reservoir configuration needs the [Reload transformers configuration](configure.md#reload-transformers-configuration).

## Follow other docs

See [README](README.md).
