#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
allowed_roots=${DEVSPACE_ALLOWED_ROOTS:-$repo_dir}
root=${DEVSPACE_TEST_ROOT:-${allowed_roots%%,*}}
[[ -n "$root" && -d "$root" ]] || { printf '%s\n' "DEVSPACE_TEST_ROOT or DEVSPACE_ALLOWED_ROOTS must name an existing allowed directory" >&2; exit 2; }
report=$(mktemp /tmp/devspace-tool-catalog.XXXXXX.json)
trap 'unlink "$report" 2>/dev/null || true' EXIT
DEVSPACE_TEST_ROOT="$root" scripts/check-devspace-mcp.sh > "$report"
jq '{endpoint,authentication,initialization,protocolVersion,session,serverCapabilities,tools,resources,resourceTemplates,prompts,notifications,operations,reconnection}' "$report"
