# Reservoir server configuration

This document explains configuration of matchers, pools, transformers, and Reservoir installations.

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 configure.md -->
* [Ensure relevant login, and set some additional environment](#ensure-relevant-login-and-set-some-additional-environment)
* [Configure matchers and pools](#configure-matchers-and-pools)
* [Initialize the pool](#initialize-the-pool)
* [Update matcher configuration](#update-matcher-configuration)
* [Pool with multiple matchers](#pool-with-multiple-matchers)
* [Do transformers configuration](#do-transformers-configuration)
* [Reload transformers configuration](#reload-transformers-configuration)
* [Do OAI-PMH transformer configuration](#do-oai-pmh-transformer-configuration)
* [Follow other docs](#follow-other-docs)

## Ensure relevant login, and set some additional environment

Follow [Setup workspace and login](workspace.md).

## Configure matchers and pools

(Refer to detailed notes at Reservoir [Configuring matchers](../../README.md#configuring-matchers).)

Matchkeys utilise some specific elements from MARC bibliographic records to generate a unique string which identifies common records that describe the same instance.
The various matchers implementations are explained at the [indexdata/reservoir-scripts](https://github.com/indexdata/reservoir-scripts) repository.

Show the current pools and modules for this particular Reservoir server:

```shell
https GET $host/reservoir/config/modules x-okapi-token:$token \
  | jq -r '.modules[] | [.id, .type, .url] | @tsv'
https GET $host/reservoir/config/pools x-okapi-token:$token
```

POST the initial matcher module configuration.

> [!WARNING]
> Note that each example [configuration](https://github.com/indexdata/reservoir-scripts/blob/main/js/matchers/goldrush2024/config-matcher-goldrush2024.json) refers to its JavaScript implementation via a specific git commit SHA (and might not be current).
> Operators should manage their own configuration files and not use these examples directly.

```shell
https POST $host/reservoir/config/modules x-okapi-token:$token \
  < $ID_WORKSPACE/reservoir-scripts/js/matchers/goldrush2024/config-matcher-goldrush2024.json
```

POST the pool configuration.

```shell
https POST $host/reservoir/config/pools x-okapi-token:$token \
  < $ID_WORKSPACE/reservoir-scripts/js/matchers/goldrush2024/config-pool-goldrush2024.json
```

## Initialize the pool

Start initialization of the pool with

```shell
echo '{}' | https POST "$host/reservoir/config/pools/goldrush2024/initializations" x-okapi-token:$token
```

Check progress with:

```shell
https GET "$host/reservoir/config/pools/goldrush2024/initializations" x-okapi-token:$token
```

The count and statistics can be done only after the initialisation has completed.

```shell
https GET "$host/reservoir/clusters?poolId=goldrush2024&count=exact&limit=0" x-okapi-token:$token
https GET $host/reservoir/config/pools/goldrush2024/stats x-okapi-token:$token | jq '.'
```

The statistics operation can take a long time.
So spin up a [container in the cluster](miscellaneous.md#gateway-timeout) to avoid the ALB/NGINX timeouts.

## Update matcher configuration

To update an existing matcher module configuration, e.g. to verify an in-development branch raw URL.
(See [API docs](https://s3.amazonaws.com/indexdata-docs/api/reservoir/reservoir.html#operation/putCodeModule).)

Modify its URL to refer to the new commit SHA of the script modification. Then update the matcher module:

```shell
https PUT $host/reservoir/config/modules/goldrush2024-matcher x-okapi-token:$token \
  < $ID_WORKSPACE/reservoir-scripts/js/matchers/goldrush2024/config-matcher-goldrush2024.json
```

Initialize the pool, as [explained](#initialize-the-pool) above.

## Pool with multiple matchers

A pool can declare multiple matchers. See [example](https://github.com/indexdata/reservoir-scripts/blob/main/js/matchers/goldrush2024/config-pool-goldrush2024-isxn.json).

Reservoir will utilise each matcher and create a union of match values. This avoids duplicating matcher source code across multiple JavaScript files.

## Do transformers configuration

Payloads can be converted or normalized using JavaScript Transformers during export.

(Refer to detailed notes at Reservoir [Transformers](../../README.md#transformers).)

POST the initial transformers configuration:

```shell
https POST $host/reservoir/config/modules x-okapi-token:$token \
  < $ID_WORKSPACE/reservoir-scripts/js/transformers/marc-transformer.json
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
  < $ID_WORKSPACE/reservoir-scripts/js/transformers/config-transformer-oai.json
```

## Follow other docs

See [README](README.md).
