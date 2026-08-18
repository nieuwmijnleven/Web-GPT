#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
allowed_roots=${DEVSPACE_ALLOWED_ROOTS:-$repo_dir}
export DEVSPACE_TEST_ROOT=${DEVSPACE_TEST_ROOT:-${allowed_roots%%,*}}
exec node "$script_dir/devspace-mcp-client.mjs" "$@"
