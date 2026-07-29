# Setup workspace and login

This document explains establishing a workspace and logging in to the system.

<!-- $GH_FOLIO/okapi/doc/md2toc -l 2 -h 3 workspace.md -->
* [Assumptions](#assumptions)
* [Clone relevant repositories](#clone-relevant-repositories)
* [Create a workspace](#create-a-workspace)
* [Prepare configuration files](#prepare-configuration-files)
* [Do login and verify setup](#do-login-and-verify-setup)
* [Follow other docs](#follow-other-docs)

## Assumptions

Throughout all Reservoir documentation, using a UNIX-like system is assumed.

These instructions utilise the [httpie](https://httpie.io/cli) command-line client (although of course [curl](https://everything.curl.dev/) could be used instead).

This documentation assumes a consortium name "test".

The [Okapi](https://github.com/folio-org/okapi) (a multitenant API Gateway) controls access to Reservoir.
Refer to the set of [Okapi API endpoints](https://dev.folio.org/reference/api/#okapi).

There needs to be an Okapi user named "test_reservoir_admin" (or some such) with permissions to conduct Reservoir operations.

## Clone relevant repositories

Clone these repositories:

```shell
mkdir ~/id-workspace
export ID_WORKSPACE=~/id-workspace
git clone https://github.com/indexdata/reservoir
git clone https://github.com/indexdata/matchkeys
```

## Create a workspace

```shell
mkdir $ID_WORKSPACE/reservoir-workspace
export RESERVOIR_WORKSPACE=$ID_WORKSPACE/reservoir-workspace
cd $RESERVOIR_WORKSPACE
```

## Prepare configuration files

The configuration file for a consortium uses the INI format.

Example config.ini

```shell
[DEFAULT]
consortium = test
host = test-prod-okapi.example.com
tenant = test_reservoir
username = test_reservoir_admin
password = XXXXXXXX
OKAPI_URL = https://${host}
OKAPI_TENANT = ${tenant}
OKAPI_USER = ${username}
OKAPI_PW = ${password}
```

Copy the utility tools over to this RESERVOIR_WORKSPACE.
(Note these are crude but functional scripts, provided as examples. Operators will develop their own mechanisms.)

```shell
cp $ID_WORKSPACE/reservoir/doc/ops/util/export-ini.sh .
cp $ID_WORKSPACE/reservoir/doc/ops/util/login_no_refresh.py .
cp $ID_WORKSPACE/reservoir/doc/ops/util/okapi-login.sh .
cp $ID_WORKSPACE/reservoir/doc/ops/util/verify_env.sh .
cp $ID_WORKSPACE/reservoir/doc/ops/util/get_reservoir_version.sh .
cp $ID_WORKSPACE/reservoir/doc/ops/util/status_oai_all.sh .
```

## Do login and verify setup

FOLIO is on a quest to improve login and token management.
The first phase is Refresh Token Rotation (RTR). Our systems are not yet doing that.

The login facility provided here obtains a once-off token via the new login-with-expiry endpoint, if available.
Otherwise it uses the old login endpoint.

This once-off token is currently non-expiring.
When RTR is implemented then it will expire after 10 minutes.

Ensure [Prepare configuration](#prepare-configuration-files) as explained in the previous section.

Establish the environment:

```shell
CONFIG=config.ini source export-ini.sh
```

Now do login to obtain the okapi token and export it (the okapi token will be utilised for Reservoir operations throughout this documentation set):

```shell
source okapi-login.sh
```

Verify the setup:

```shell
source verify_env.sh
```

Ensure that the okapi token is operational, and report the Okapi version:

```shell
https GET $host/_/version x-okapi-token:$token
```

Get the most-recent commit SHA of Reservoir source-code, and the SHA of this Reservoir deployment:

```shell
./get_reservoir_version.sh
```

## Follow other docs

See [README](README.md).
