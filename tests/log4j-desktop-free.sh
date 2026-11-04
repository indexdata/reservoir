#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
jar=${1:-$repo_dir/server/target/reservoir-server-fat.jar}
classes_dir=$(mktemp -d "${TMPDIR:-/tmp}/reservoir-log4j-check.XXXXXX")
trap 'rm -rf "$classes_dir"' EXIT

# Compile against the transformed artifact, so the test checks its actual API.
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$jar" -d "$classes_dir" \
  "$repo_dir/tests/Log4jDesktopFreeCheck.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  --limit-modules java.base,java.logging,java.management,java.sql,java.xml,java.security.sasl,jdk.unsupported \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -Dlog4j2.disableJmx=true -cp "$jar:$classes_dir" Log4jDesktopFreeCheck
