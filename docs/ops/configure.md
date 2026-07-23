# Reservoir server configuration

This document explains configuration of matchkeys and transformers, and Reservoir installations.

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 configure.md -->
* [Ensure relevant login, and set some additional environment](#ensure-relevant-login-and-set-some-additional-environment)
* [Do matchkeys configuration](#do-matchkeys-configuration)
* [Initialize the matchkeys pool](#initialize-the-matchkeys-pool)
* [Reload matchkeys configuration](#reload-matchkeys-configuration)
* [Update matchkeys configuration](#update-matchkeys-configuration)
* [Do transformers configuration](#do-transformers-configuration)
* [Reload transformers configuration](#reload-transformers-configuration)
* [Do OAI-PMH transformer configuration](#do-oai-pmh-transformer-configuration)
* [Follow other docs](#follow-other-docs)

## Ensure relevant login, and set some additional environment

Follow [Setup workspace and login](workspace.md).

## Do matchkeys configuration

(Refer to detailed notes at Reservoir [Configuring matchers](../../README.md#configuring-matchers).)

Show current matchkeys and modules configuration:

```shell
https GET $host/reservoir/config/modules x-okapi-token:$token \
  | jq -r '.modules[] | [.id, .type, .url] | @tsv'
https GET $host/reservoir/config/matchkeys x-okapi-token:$token
```

POST the initial matchkeys configuration.

> [!WARNING]
> Note that the example [configuration](https://github.com/indexdata/matchkeys/blob/main/js/matchkeys/goldrush2024/config-matchkeys-goldrush2024.json) refers to its JavaScript implementation via a specific git commit SHA.
> Operators should manage their own configuration files and not use these examples directly.

```shell
https POST $host/reservoir/config/modules x-okapi-token:$token \
  < $ID_WORKSPACE/matchkeys/js/matchkeys/goldrush2024/config-matchkeys-goldrush2024.json
```

POST the matchkeys pool configuration.

```shell
https POST $host/reservoir/config/matchkeys x-okapi-token:$token \
  < $ID_WORKSPACE/matchkeys/js/matchkeys/goldrush2024/config-pool-goldrush2024.json
```

## Initialize the matchkeys pool

Initialize the pool.

```shell
https PUT "$host/reservoir/config/matchkeys/goldrush2024/initialize" x-okapi-token:$token
```

Note that if this is being done for a large consortium then this process will take a long time (typical records-per-second=160).
After 5-minutes there will be an expected NGINX "Gateway timeout".

```shell
https PUT "$host/reservoir/config/matchkeys/goldrush2024/initialize" x-okapi-token:$token
```

Watch the AWS facilities from time-to-time at "CloudWatch > Database insights".

The count and statistics can be done only after the initialisation has completed.

```shell
https GET "$host/reservoir/clusters?matchkeyid=goldrush2024&count=exact&limit=0" x-okapi-token:$token
https GET $host/reservoir/config/matchkeys/goldrush2024/stats x-okapi-token:$token | jq '.'
```

The statistics operation can take a long time.
So spin up a [container in the cluster](miscellaneous.md#gateway-timeout) to avoid the ALB/NGINX timeouts.

## Update matchkeys configuration

To update existing matchkeys module configuration, e.g. to verify an in-development branch raw url.
(See [API docs](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html#operation/putCodeModule).)

Modify its URL to refer to the new commit SHA of the script modification. Then update the matchkey:

```shell
https PUT $host/reservoir/config/modules/goldrush2024-matcher x-okapi-token:$token \
  < $ID_WORKSPACE/matchkeys/js/matchkeys/goldrush2024/config-matchkeys-goldrush2024.json

https PUT $host/reservoir/config/modules/goldrush2024-matcher/reload x-okapi-token:$token
```

Initialize the pool, as [explained](#initialize-the-matchkeys-pool) above.

## Do transformers configuration

(Refer to detailed notes at Reservoir [Transformers](../../README.md#transformers).)

POST the initial transformers configuration:

```shell
https POST $host/reservoir/config/modules x-okapi-token:$token \
  < $ID_WORKSPACE/matchkeys/js/transformers/marc-transformer.json
```

## Reload transformers configuration

If the script source, to which the transformer configuration refers, is subsequently modified then reload it, e.g.:

```shell
https PUT $host/reservoir/config/modules/marc-transformer/reload x-okapi-token:$token
```

> [!IMPORTANT]
> When the transformer settings for a particular tenant of a consortium are added or modified,
> then the [touch](miscellaneous.md#touch) operation is also required.

## Do OAI-PMH transformer configuration

PUT the initial transformer configuration for OAI-PMH.

The "function" part at the end of the declaration is the name of the exported function in the transformer.mjs code.
Of course if that function name is later changed, then modify and PUT again.

```shell
https PUT $host/reservoir/config/oai x-okapi-token:$token \
  < $ID_WORKSPACE/matchkeys/js/transformers/config-transformer-oai.json
```

## Follow other docs

See [README](README.md).
