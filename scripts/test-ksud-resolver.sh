#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT

"${JAVAC:-javac}" --release 11 -d "$build_dir" \
    "$project_dir/app/src/main/java/df/root/KsudResolver.java" \
    "$project_dir/tests/df/root/KsudResolverTest.java"
"${JAVA:-java}" -cp "$build_dir" df.root.KsudResolverTest "$@"
