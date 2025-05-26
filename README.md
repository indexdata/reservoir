# Reservoir

Copyright (C) 2025 Index Data ApS

This software is distributed under the terms of the
GNU Affero General Public License version 3.0
See the file "[LICENSE](LICENSE)" for more information.

This software was previously hosted at
[github.com/folio-org/mod-reservoir](github.com/folio-org/mod-reservoir), which
is now archived. The original repository was distributed under the Apache
License 2.0, which is the default license for repositories in the FOLIO
organization. While developed within the FOLIO GitHub organization, this
software was never formally accepted as part of the FOLIO project.
The license change to AGPL v3 has been consented to by all contributors to this project.

## Introduction

A service that provides a clustering storage of metadata for Data Integration purposes.
Optimized for fast storage and retrieval performance.

This project has three subprojects:

* `util` -- A library with utilities to convert and normalize XML, JSON and MARC.
* `server` -- The reservoir storage server. This is the FOLIO module: mod-reservoir
* `client` -- A client for sending ISO2709/MARCXML records to the server.

## Compilation

Requirements:

* Java 21 (later versions might not work with graalvm)
* Maven 3.6.3 or later
* Docker (unless `-DskipTests` is used)

You need `JAVA_HOME` set, e.g.:

   * Linux: `export JAVA_HOME=$(readlink -f /usr/bin/javac | sed "s:bin/javac::")`
   * macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

Build all components with: `mvn install`

## Server

You will need Postgres 12 or later.

You can create an empty database and a user with, e.g:

```
CREATE DATABASE folio_modules;
CREATE USER folio WITH CREATEROLE PASSWORD 'folio';
GRANT ALL PRIVILEGES ON DATABASE folio_modules TO folio;
```

The server's database connection is then configured by setting environment variables:
`DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DATABASE`,
`DB_MAXPOOLSIZE`, `DB_SERVER_PEM`.

Once configured, start the server with:

```
java -Dport=8081 --upgrade-module-path=server/target/compiler \
   -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
   -jar server/target/mod-reservoir-server-fat.jar
```

## Server metrics

Reservoir can produce Prometheus and JMX metrics. Prometheus metrics are exposed on the path `/metrics` and port `PORT` if the `-Dmetrics.prometheus.port=PORT` option is specified.
JMX metrics are exposed for domain `reservoir` if `-Dmetrics.jmx=true` option is specified.

## Running with Docker

If you feel adventurous and want to run Reservoir in a docker container, build the container first:

```
docker build -t mod-reservoir:latest .
```

And run with the server port exposed (`8081` by default):

```
docker run -e DB_HOST=host.docker.internal \
  -e DB_USERNAME=folio \
  -e DB_PASSWORD=folio \
  -e DB_DATABASE=folio_modules \
  -p 8081:8081 --name reservoir mod-reservoir:latest
```

**Note**: The magic host `host.docker.internal` is required to access the DB and may be only available in Docker Desktop.
If it's not defined you can specify it by passing `--add-host=host.docker.internal:<docker bridge net IP>` to the run command.

**Note**: Those docker build and run commands do work as-is with [Colima](https://github.com/abiosoft/colima).

## Command-line client

Note: the CLI is no longer developed and the file upload functionality is now available from
curl (see below) so please use this instead.

The client is a command-line tool for sending records to the mod-reservoir server.

Run the client with:

```
java -jar client/target/mod-reservoir-client-fat.jar [options] [files...]
```

To see list options use `--help`. The client uses environment variables
`OKAPI_URL`, `OKAPI_TENANT`, `OKAPI_TOKEN` for Okapi URL, tenant and
token respectively.

Before records can be pushed, the database needs to be prepared for the tenant.
If Okapi is used, then the usual `install` command will do it, but if the
mod-reservoir module is being run on its own, then that must be done manually.

For example, to prepare the database for tenant `diku` on server running on localhost:8081, use:

```
export OKAPI_TENANT=diku
export OKAPI_URL=http://localhost:8081
java -jar client/target/mod-reservoir-client-fat.jar --init
```

**Note**: The above-mentioned commands are for the server running on localhost.
For a secured server, the `-HX-Okapi-Token:$OKAPI_TOKEN` is required rather
than `X-Okapi-Tenant`.

To purge the data, use:

```
export OKAPI_TENANT=diku
export OKAPI_URL=http://localhost:8081
java -jar client/target/mod-reservoir-client-fat.jar --purge
```

To send MARCXML to the same server with defined `sourceId`, use:

```
export OKAPI_TENANT=diku
export OKAPI_URL=http://localhost:8081
export sourceid=lib1
java -jar client/target/mod-reservoir-client-fat.jar \
  --source $sourceid \
  --xsl xsl/localid.xsl \
  client/src/test/resources/record10.xml
```

The option `--xsl` may be repeated for a sequence of transformations.

Once records are loaded, they can be retrieved with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT $OKAPI_URL/reservoir/records
```

## Ingest record files

The endpoint `/reservoir/upload` allows uploading files via HTTP `PUT` and `POST`.

Currently, two formats are supported.

 * MARC/ISO2709 concatenated records, triggered by Content-Type `application/octet-stream`.
 * MARCXML collection, triggered by Content-Type `application/xml` or `text/xml`.

The following query parameters are recognized:

 * `sourceId`: required parameter for specifying the source identifier.
 * `sourceVersion`: optional parameter for specifying source version
    (default is 1)
 * `fileName`: optional parameter for specifying the name of the uploaded file
 * `localIdPath`: optional parameter for specifying where to find local identifier
    (default is `$.marc.fields[*].001`)
 * `xmlFixing`: optional boolean parameter, if `true` an attempt is made to remove invalid characters (e.g control chars)
    from the XML input, `false` by default

These query parameters are for debugging and performance testing only:

 * `ingest` optional boolean parameter to determine whether ingesting is to take place (default `true`)
 * `raw` optional boolean parameter to determine whether to just pipe the stream (default `false`)

Note that this endpoint expects granular permissions for ingesting records from a particular source:

 * `reservoir-upload.source.*` to ingest records from a specific source, where `*` symbol must be replaced
    by the `sourceId` parameter
 * `reservoir-upload.all-sources` to ingest data for any source (admin permission).

 These permissions are enforced not by Okapi but by Reservoir directly and hence must be specified through
 the `X-Okapi-Permissions` header if the request is performed directly against the module. This is achieved
 by adding `-H'X-Okapi-Permissions:["reservoir-upload.all-sources"]'` switch to the curl commands below.

For uploading ISO2709 (binary MARC) use:

```
  curl -HX-Okapi-Tenant:$OKAPI_TENANT \
    -T records.mrc $OKAPI_URL/reservoir/upload?sourceId=BIB1
```

Note: curl's `-T` is a shorthand for `--upload-file` and uses `PUT` for uploads,
no `Content-Type` is set by curl which Reservoir treats the same as `application/octet-stream`.

For uploading MARCXML:

```
  curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-Type:text/xml \
    -T records100k.xml $OKAPI_URL/reservoir/upload?sourceId=BIB1
```

In order to send `gzip` compressed files use:

```
  curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-Encoding:gzip \
   -T records.mrc.gz $OKAPI_URL/reservoir/upload?sourceId=BIB1
```

or apply compression on the fly:

```
  cat records.mrc | gzip | curl -HX-Okapi-Tenant:$OKAPI_TENANT \
    -HContent-Encoding:gzip -T - $OKAPI_URL/reservoir/upload?sourceId=BIB1
```

Avoid using curl's alternative with `--data-binary @...` for large files as it buffers the entire file and may result in out of memory errors.

## Ingest via multipart/form-data

An alternative to the method above is uploading with `multipart/form-data` content type at `/reservoir/upload` endpoint.

Only the named form input `records` is recognized; other form inputs are ignored.

For example to ingest a set of MARCXML records via curl from sourceId `BIB1`:

```
  curl -HX-Okapi-Tenant:$OKAPI_TENANT -Frecords=@records100k.xml $OKAPI_URL/reservoir/upload?sourceId=BIB1
```

This approach does not allow for `gzip` compression but is simpler to integrate with a regular browser.

## Ingest via an embedded upload form

Reservoir comes with a simple HTML/JS file upload form that can be accessed by a browser at:
(when running Reservoir locally)

```
  http://localhost:8081/reservoir/upload-form
```

When running behind Okapi you need to use the `invoke` URL:

```
  http://$OKAPI_URL/_/invoke/tenant/$OKAPI_TENANT/reservoir/upload-form/
```

in order to pass the tenant identifier (trailing slash is important).

## Configuring matchers

Records in Reservoir are clustered according to rules expressed in a `matcher`. Matchers
can be implemented using `jsonpath`, for simple matching rules, or `javascript` for arbitrary
complexity.

To configure a matcher, first load an appropriate code module, e.g a simple `jsonpath`
module with a matcher that works for __Marc-in-Json__ payload could be defined like this:

```
cat title-matcher.json
{
  "id": "title-matcher",
  "type": "jsonpath",
  "script": "$.marc.fields[*].245.subfields[*].a"
}
```

Post it to the server with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-type:application/json \
 $OKAPI_URL/reservoir/config/modules -d @title-matcher.json
```

Next, create a pool and reference this matcher to apply the method for clustering:

```
cat title-pool.json
{
  "id": "title",
  "matcher": "title-matcher",
  "update": "ingest"
}
```

Post the pool configuration to the server with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-type:application/json \
 $OKAPI_URL/reservoir/config/matchkeys -d @title-pool.json
```

and then initialize the pool for this config:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -XPUT $OKAPI_URL/reservoir/config/matchkeys/title/initialize
```

Now, you can retrieve individual record clusters from this pool with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT $OKAPI_URL/reservoir/clusters?matchkeyid=title
```

Obviously, matcher configuration must be aligned with the format of stored records.

Reservoir ships with a JS module that implements the `goldrush` matching
algorithm from coalliance.org.

```
cat js/matchkeys/goldrush/goldrush-conf.json
{
  "id": "goldrush-matcher",
  "type": "javascript",
  "url": "https://raw.githubusercontent.com/folio-org/mod-reservoir/master/js/matchkeys/goldrush/goldrush.mjs"
}
```

Load it with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-type:application/json \
 $OKAPI_URL/reservoir/config/modules -d @js/matchkeys/goldrush/goldrush-conf.json
```

And create a corresponding pool with:

```
cat goldrush-pool.json
{
  "id": "goldrush",
  "matcher": "goldrush-matcher::matchkey",
  "update": "ingest"
}
```
post:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-type:application/json \
 $OKAPI_URL/reservoir/config/matchkeys -d @goldrush-pool.json
```

## OAI-PMH client

The OAI-PMH client is executing in the server. It is an alternative to
ingesting records via the command-line client mentioned earlier.
Commands are sent to the server to initiate the client operations.

### OAI-PMH client configuration

The OAI-PMH client is configured by posting simple JSON configuration.
The identifier `id` is user-defined and given in the initial post.
Example with identifier `us-mdbj` below:

```
export OKAPI_TENANT=diku
export OKAPI_URL=http://localhost:8081
cat oai-us-mdbj.json
{
  "id": "us-mdbj",
  "set": "397",
  "sourceId": "US-MDBJ",
  "url": "https://pod.stanford.edu/oai",
  "metadataPrefix": "marc21",
  "headers": {
    "Authorization": "Bearer ey.."
  }
}
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-Type:application/json -XPOST \
   -d@oai-us-mdbj.json $OKAPI_URL/reservoir/pmh-clients
```

In this case, all ingested records from the client are given the source identifier `US-MDBJ`.

See [schema](server/src/main/resources/openapi/schemas/oaiPmhClient.json) for more information.

This configuration can be inspected with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT \
  $OKAPI_URL/reservoir/pmh-clients/us-mdbj
```

Start a job with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -XPOST \
  $OKAPI_URL/reservoir/pmh-clients/us-mdbj/start
```

Start all jobs with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -XPOST \
  $OKAPI_URL/reservoir/pmh-clients/_all/start
```

Each job will continue until the server returns error or returns no resumption token. The `from`
property of the configuration is populated with latest datestamp in records received. This enables
the client to repeat the job again at a later date to fetch updates from `from` to now (unless `until` is
specified).

Get status for a job with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT \
  $OKAPI_URL/reservoir/pmh-clients/us-mdbj/status
```

Get status for all jobs with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT \
  $OKAPI_URL/reservoir/pmh-clients/_all/status
```

Stop a job with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -XPOST \
  $OKAPI_URL/reservoir/pmh-clients/us-mdbj/stop
```

Stop all jobs with:
```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -XPOST \
  $OKAPI_URL/reservoir/pmh-clients/_all/stop
```

## OAI-PMH server

The path prefix for the OAI server is `/reservoir/oai` and requires no access permissions.

The following OAI-PMH verbs are supported by the server: `ListIdentifiers`, `ListRecords`, `GetRecord`, `Identify`.

At this stage, only `metadataPrefix` with value `marcxml` is supported. This
parameter can be omitted, in which case `marcxml` is assumed.

Each Reservoir cluster corresponds to an OAI-PMH record and each matchkey configuration corresponds to
an OAI `set`.

For example, to initiate a harvest of "title" clusters:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT "$OKAPI_URL/reservoir/oai?verb=ListRecords&set=title"
```

and to retrieve a particular OAI-PMH record (Reservoir cluster):

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT \
  "$OKAPI_URL/reservoir/oai?verb=GetRecord&identifier=oai:<cluster UUID>"
```

Since no permissions are required for `/reservoir/oai`, the endpoint can be accessed without the need for
the `X-Okapi-Tenant` and `X-Okapi-Token` headers using the invoke feature of Okapi:

```
curl "$OKAPI_URL/_/invoke/tenant/$OKAPI_TENANT/reservoir/oai?set=title&verb=ListRecords"

```
Note: this obviously only works if Okapi is proxying requests to the module

The OAI server delivers 1000 identifiers/records at a time. This limit can be
increased with a non-standard query parameter `limit`. The service returns resumption token
until the full set is retrieved.

The OAI-PMH server returns MarcXML and expects that the payload provides MARC-in-JSON format under the `marc` key.

## Transformers

Payloads can be converted or normalized using JavaScript Transformers during export.

Example transformer:

```
cat js/transformers/marc-transformer.mjs
        export function transform(clusterStr) {
          let cluster = JSON.parse(cluster);
          let recs = cluster.records;
          //merge all marc recs
          const out = {};
          out.leader = 'new leader';
          out.fields = [];
          for (let i = 0; i < recs.length; i++) {
            let rec = recs[i];
            let marc = rec.payload.marc;
            //collect all marc fields
            out.fields.push(marc.fields);
            //stamp with custom 999 for each member
            out.fields.push(
              {
                '999' :
                {
                  'ind1': '1',
                  'ind2': '0',
                  'subfields': [
                    {'i': rec.globalId },
                    {'l': rec.localId },
                    {'s': rec.sourceId }
                  ]
                }
              }
            );
          }
          return JSON.stringify(out);
}
```

Transformers just like matchers are `code modules` and the above marc transformer
can be installed with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-Type:application/json \
  $OKAPI_URL/reservoir/config/modules -d @js/transformers/marc-transformer.json
```

and enabled for the OAI-PMH server with:

```
curl -HX-Okapi-Tenant:$OKAPI_TENANT -HContent-Type:application/json \
  -XPUT $OKAPI_URL/reservoir/config/oai -d'{"transformer":"marc-transformer::transform"}'
```

If a transformer is modified to produce a different result for a particular source,
the clusters that include records from this source should be marked for re-export by having their datestamps updated.
This can be achieved with the following call:

```
curl -G -HX-Okapi-Tenant:$OKAPI_TENANT $OKAPI_URL/reservoir/clusters/touch \
  --data-urlencode "query=matchkeyId = title AND sourceId = BIB1" -XPOST
```

## Hosting notes

Harvest operations against slow OAI-PMH servers may take a long time and appear idle which can cause timeouts in NAT gateways or firewalls.

Reservoir enables _TCP keepalive_ for client sockets in an attempt to workaround OAI-PMH idle resets. The following values are used:

  * `tcp_keepalive_idle` `45s`
  * `tcp_keepalive_interval` `45s`
  * `tcp_keepalive_count` (default, 9)

which are below the default idle timeout values (~300s).

Similarly, certain Reservoir API operations, including:

  * `/config/matchkeys/{name}/initialize`
  * `/clusters/?matchkeyid={name}&count=exact`

are database heavy and may take a long time. Such request may be considered idle by the front load-balancer
or ingress controller and require tuning of the timeout values.

Specifically, for NGINX it's recommended that the read timeout is increased beyond the default 60s:

```
proxy_read_timeout 600s
```

Additionally to allow uploading large files, it's a good idea to disable request buffering in NGINX and
increase the max size:

```
proxy_request_buffering off
client_max_body_size 10G
```

In `ingress-nginx` the following annotations should be used:

```
    nginx.ingress.kubernetes.io/proxy-body-size: 10G
    nginx.ingress.kubernetes.io/proxy-read-timeout: "600"
    nginx.ingress.kubernetes.io/proxy-request-buffering: "off"
```

## Additional information

### Issue tracker

See project [RSRVR](https://index-data.atlassian.net/jira/software/c/projects/RSRVR/issues) .

### ModuleDescriptor

See the [ModuleDescriptor](descriptors/ModuleDescriptor-template.json)
for the interfaces that this module requires and provides, the permissions,
and the additional module metadata.

### API documentation

API descriptions:

 * [OpenAPI](server/src/main/resources/openapi/reservoir.yaml)
 * [Schemas](server/src/main/resources/openapi/schemas/)

Generated [API documentation](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html).


