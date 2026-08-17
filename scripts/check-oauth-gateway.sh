#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "gateway check failed: $*" >&2
  exit 1
}

env_file=${DEVSPACE_TUNNEL_ENV_FILE:-/etc/devspace/openai-mcp-tunnel.env}
if [[ -r "$env_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_file"
  set +a
fi

base_url=${OAUTH_GATEWAY_URL:-http://127.0.0.1:9292}
base_url=${base_url%/}
public_base=${OAUTH_PUBLIC_BASE_URL:-}
public_base=${public_base%/}
[[ -n "$public_base" ]] || fail "OAUTH_PUBLIC_BASE_URL is not configured"
[[ "$public_base" == https://* ]] || fail "OAUTH_PUBLIC_BASE_URL must use https://"
expected_resource=${MCP_PUBLIC_RESOURCE_URL:-$public_base/mcp}

health=$(curl --compressed --fail --silent --show-error --max-time 5 "$base_url/healthz")
protected=$(curl --compressed --fail --silent --show-error --max-time 5 "$base_url/.well-known/oauth-protected-resource/mcp")
authorization=$(curl --compressed --fail --silent --show-error --max-time 5 "$base_url/.well-known/oauth-authorization-server")
public_protected=$(curl --compressed --fail --silent --show-error --max-time 20 "$public_base/.well-known/oauth-protected-resource/mcp")
public_authorization=$(curl --compressed --fail --silent --show-error --max-time 20 "$public_base/.well-known/oauth-authorization-server")

HEALTH_JSON="$health" \
LOCAL_PROTECTED_JSON="$protected" \
LOCAL_AUTHORIZATION_JSON="$authorization" \
PUBLIC_PROTECTED_JSON="$public_protected" \
PUBLIC_AUTHORIZATION_JSON="$public_authorization" \
PUBLIC_BASE="$public_base" \
EXPECTED_RESOURCE="$expected_resource" \
node <<'NODE'
const fail = (message) => {
  console.error(`gateway check failed: ${message}`);
  process.exit(1);
};

const parse = (name) => {
  try {
    return JSON.parse(process.env[name]);
  } catch (error) {
    fail(`${name} is not valid JSON: ${error.message}`);
  }
};

const normalizeBase = (raw) => {
  const url = new URL(raw);
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url.href.replace(/\/$/, "");
};

const assert = (condition, message) => {
  if (!condition) fail(message);
};

const assertExternalHttps = (raw, label) => {
  let url;
  try {
    url = new URL(raw);
  } catch (error) {
    fail(`${label} is not an absolute URL: ${error.message}`);
  }
  const host = url.hostname.toLowerCase().replace(/\.$/, "");
  assert(url.protocol === "https:", `${label} must use https://`);
  assert(!["localhost", "127.0.0.1", "::1"].includes(host), `${label} must not use loopback`);
};

const assertNoLoopback = (value, label) => {
  const serialized = JSON.stringify(value);
  assert(!/(?:127\.0\.0\.1|localhost|::1)/i.test(serialized), `${label} leaks a loopback URL`);
};

const health = parse("HEALTH_JSON");
const localProtected = parse("LOCAL_PROTECTED_JSON");
const localAuthorization = parse("LOCAL_AUTHORIZATION_JSON");
const publicProtected = parse("PUBLIC_PROTECTED_JSON");
const publicAuthorization = parse("PUBLIC_AUTHORIZATION_JSON");
const base = normalizeBase(process.env.PUBLIC_BASE);
const expectedResource = new URL(process.env.EXPECTED_RESOURCE).href;
const expectedIssuer = `${base}/`;

assert(health.ok === true, "gateway health response is not ok");
assert(normalizeBase(health.publicOAuthBase) === base, "gateway process did not load OAUTH_PUBLIC_BASE_URL");
assert(new URL(health.publicResource).href === expectedResource, "gateway process did not load MCP_PUBLIC_RESOURCE_URL");
assertExternalHttps(expectedResource, "MCP public resource");

const validateProtected = (document, label) => {
  assert(document.resource === expectedResource, `${label}.resource does not match MCP_PUBLIC_RESOURCE_URL`);
  assert(Array.isArray(document.authorization_servers), `${label}.authorization_servers is missing`);
  assert(document.authorization_servers.length === 1, `${label}.authorization_servers must contain one issuer`);
  assert(document.authorization_servers[0] === expectedIssuer, `${label}.authorization_servers[0] is not the public issuer`);
  assertExternalHttps(document.resource, `${label}.resource`);
  assertExternalHttps(document.authorization_servers[0], `${label}.authorization_servers[0]`);
  assertNoLoopback(document, label);
};

const validateAuthorization = (document, label) => {
  const expected = {
    issuer: expectedIssuer,
    authorization_endpoint: `${base}/authorize`,
    token_endpoint: `${base}/token`,
    registration_endpoint: `${base}/register`,
  };
  for (const [key, value] of Object.entries(expected)) {
    assert(document[key] === value, `${label}.${key} is not the expected public URL`);
    assertExternalHttps(document[key], `${label}.${key}`);
  }
  if (document.revocation_endpoint !== undefined) {
    assert(document.revocation_endpoint === `${base}/revoke`, `${label}.revocation_endpoint is not the expected public URL`);
  }
  assert(Array.isArray(document.code_challenge_methods_supported), `${label}.code_challenge_methods_supported is missing`);
  assert(document.code_challenge_methods_supported.includes("S256"), `${label} does not advertise PKCE S256`);
  assertNoLoopback(document, label);
};

validateProtected(localProtected, "local protected-resource metadata");
validateProtected(publicProtected, "public protected-resource metadata");
validateAuthorization(localAuthorization, "local authorization-server metadata");
validateAuthorization(publicAuthorization, "public authorization-server metadata");
NODE

challenge=$(curl --compressed --silent --show-error --dump-header - --output /dev/null --max-time 5 "$base_url/mcp")
grep -Eiq '^HTTP/[0-9.]+[[:space:]]+401([[:space:]]|$)' <<<"$challenge" \
  || fail "gateway /mcp did not return HTTP 401"
expected_metadata="$public_base/.well-known/oauth-protected-resource/mcp"
grep -Fqi "resource_metadata=\"$expected_metadata\"" <<<"$challenge" \
  || fail "WWW-Authenticate does not advertise the public protected-resource metadata URL"
if grep -Eqi '127\.0\.0\.1|localhost|::1' <<<"$challenge"; then
  fail "WWW-Authenticate leaks a loopback URL"
fi

printf '%s\n' "gateway: healthy with public OAuth configuration"
printf '%s\n' "gateway: local and public metadata advertise only public HTTPS URLs"
printf '%s\n' "gateway: MCP challenge advertises public protected-resource metadata"
