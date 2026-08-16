#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
allowed_roots=${DEVSPACE_ALLOWED_ROOTS:-/home/ivenewjeans25/forum-for-democracy}
export DEVSPACE_TEST_ROOT=${DEVSPACE_TEST_ROOT:-${allowed_roots%%,*}}
exec node "$script_dir/devspace-mcp-client.mjs" "$@"
