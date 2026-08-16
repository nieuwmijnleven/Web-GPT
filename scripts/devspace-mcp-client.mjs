#!/usr/bin/env node
/* Local DevSpace MCP diagnostic client. It exercises the real OAuth and HTTP
 * contract without printing credentials or retaining tokens on disk. */
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { chmod, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const endpoint = new URL(process.env.DEVSPACE_MCP_URL ?? "http://127.0.0.1:9191/mcp");
const authFile = process.env.DEVSPACE_AUTH_FILE ?? join(process.env.HOME ?? ".", ".devspace", "auth.json");
const ownerToken = process.env.DEVSPACE_OAUTH_OWNER_TOKEN ?? process.env.DEVSPACE_AUTH_TOKEN
  ?? JSON.parse(await readFile(authFile, "utf8")).ownerToken;
const protocolVersion = "2025-06-18";
const report = {
  endpoint: `${endpoint.protocol}//${endpoint.host}${endpoint.pathname}`,
  authentication: { model: "OAuth 2.1 authorization-code + PKCE", status: "not-tested" },
  initialization: "not-tested",
  protocolVersion: null,
  serverInfo: null,
  serverCapabilities: null,
  session: "not-tested",
  tools: { count: 0, names: [], descriptorsValid: false },
  resources: { supported: false, count: 0 },
  resourceTemplates: { supported: false, count: 0 },
  prompts: { supported: false, count: 0 },
  notifications: { initialized: "not-tested", progress: "not-tested", cancellation: "not-tested", logging: "not-tested" },
  operations: {},
  reconnection: "not-tested",
  errors: {},
};

function challenge(verifier) {
  return createHash("sha256").update(verifier).digest("base64url");
}

async function http(url, options = {}) {
  const response = await fetch(url, { redirect: "manual", ...options });
  const text = await response.text();
  return { response, text };
}

function json(text, fallback = null) {
  try { return JSON.parse(text); } catch { return fallback; }
}

function shellQuote(value) {
  return `'${value.replaceAll("'", "'\\''")}'`;
}

function parseSse(text) {
  const data = text.split(/\r?\n\r?\n/).map((block) => {
    const lines = block.split(/\r?\n/).filter((line) => line.startsWith("data:"));
    if (!lines.length) return null;
    return json(lines.map((line) => line.slice(5).trimStart()).join("\n"));
  }).filter(Boolean);
  return data.length === 1 ? data[0] : data;
}

function parseBody(response, text) {
  const type = response.headers.get("content-type") ?? "";
  return type.includes("text/event-stream") ? parseSse(text) : json(text, text);
}

async function discoverAndAuthorize() {
  const prm = await http(new URL(`/.well-known/oauth-protected-resource${endpoint.pathname}`, endpoint.origin));
  if (!prm.response.ok) throw new Error(`protected-resource metadata HTTP ${prm.response.status}`);
  const metadata = json(prm.text);
  const issuer = new URL(metadata.authorization_servers?.[0] ?? endpoint.origin);
  const authorization = await http(new URL("/.well-known/oauth-authorization-server", issuer));
  if (!authorization.response.ok) throw new Error(`authorization-server metadata HTTP ${authorization.response.status}`);
  const authMetadata = json(authorization.text);
  const client = await http(new URL(authMetadata.registration_endpoint, issuer), {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      client_name: "devspace-local-diagnostic",
      redirect_uris: ["http://127.0.0.1/callback"],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
    }),
  });
  if (!client.response.ok) throw new Error(`dynamic client registration HTTP ${client.response.status}`);
  const clientInfo = json(client.text);
  const verifier = randomBytes(32).toString("base64url");
  const state = randomUUID();
  const redirectUri = "http://127.0.0.1/callback";
  const authorizationUrl = new URL(authMetadata.authorization_endpoint);
  authorizationUrl.search = new URLSearchParams({
    response_type: "code",
    client_id: clientInfo.client_id,
    redirect_uri: redirectUri,
    code_challenge: challenge(verifier),
    code_challenge_method: "S256",
    scope: (metadata.scopes_supported ?? ["devspace"]).join(" "),
    state,
    resource: metadata.resource ?? endpoint.href,
  }).toString();
  const form = new URLSearchParams({
    response_type: "code",
    client_id: clientInfo.client_id,
    redirect_uri: redirectUri,
    code_challenge: challenge(verifier),
    code_challenge_method: "S256",
    scope: (metadata.scopes_supported ?? ["devspace"]).join(" "),
    state,
    resource: metadata.resource ?? endpoint.href,
    owner_token: ownerToken,
  });
  const approved = await http(authorizationUrl, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: form });
  if (approved.response.status !== 302) throw new Error(`owner authorization HTTP ${approved.response.status}`);
  const callback = new URL(approved.response.headers.get("location"));
  if (callback.searchParams.get("state") !== state) throw new Error("OAuth state mismatch");
  const token = await http(new URL(authMetadata.token_endpoint), {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: callback.searchParams.get("code") ?? "",
      client_id: clientInfo.client_id,
      redirect_uri: redirectUri,
      code_verifier: verifier,
      resource: metadata.resource ?? endpoint.href,
    }),
  });
  if (!token.response.ok) throw new Error(`token exchange HTTP ${token.response.status}`);
  const tokens = json(token.text);
  report.authentication.status = "passed";
  return { accessToken: tokens.access_token, refreshToken: tokens.refresh_token, clientInfo, authMetadata, metadata };
}

let requestId = 0;
function beginRpc(method, params, token, sessionId) {
  const id = ++requestId;
  const headers = {
    authorization: `Bearer ${token}`,
    "content-type": "application/json",
    accept: "application/json, text/event-stream",
  };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  const promise = http(endpoint, { method: "POST", headers, body: JSON.stringify({ jsonrpc: "2.0", id, method, params }) }).then((result) => ({ id, response: result.response, body: parseBody(result.response, result.text), sessionId: result.response.headers.get("mcp-session-id") ?? sessionId }));
  return { id, promise };
}

async function rpc(method, params, token, sessionId) {
  return (await beginRpc(method, params, token, sessionId)).promise;
}

async function notification(method, params, token, sessionId) {
  const headers = { authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json, text/event-stream" };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  const result = await http(endpoint, { method: "POST", headers, body: JSON.stringify({ jsonrpc: "2.0", method, params }) });
  return result.response.status;
}

function resultObject(body) {
  if (Array.isArray(body)) return body.find((item) => item?.result || item?.error) ?? body[0];
  return body;
}

function markOperation(name, body, response) {
  const item = resultObject(body);
  const result = item?.result;
  const toolError = result?.isError === true;
  report.operations[name] = {
    httpStatus: response.status,
    ok: response.ok && !item?.error && !toolError,
    error: item?.error ? { code: item.error.code, message: item.error.message } : toolError ? { type: "tool", message: (result.content ?? []).find((entry) => entry.type === "text")?.text ?? "tool returned isError" } : undefined,
  };
  return item;
}

async function run() {
  const auth = await discoverAndAuthorize();
  const initialized = await rpc("initialize", {
    protocolVersion,
    capabilities: { roots: { listChanged: false }, sampling: {} },
    clientInfo: { name: "devspace-local-diagnostic", version: "1.0.0" },
  }, auth.accessToken);
  const initItem = markOperation("initialize", initialized.body, initialized.response);
  if (!initItem?.result) throw new Error("MCP initialize returned no result");
  report.initialization = "passed";
  report.protocolVersion = initItem.result.protocolVersion;
  report.serverInfo = initItem.result.serverInfo ?? null;
  report.serverCapabilities = initItem.result.capabilities ?? null;
  report.session = initialized.sessionId ? "created" : "not-created";
  const sessionId = initialized.sessionId;
  report.notifications.initialized = String(await notification("notifications/initialized", {}, auth.accessToken, sessionId));

  const outside = await rpc("tools/call", { name: "open_workspace", arguments: { path: process.env.DEVSPACE_OUTSIDE_TEST ?? "/tmp" } }, auth.accessToken, sessionId);
  markOperation("outside_root_rejection", outside.body, outside.response);

  const listed = await rpc("tools/list", {}, auth.accessToken, sessionId);
  const listedItem = markOperation("tools/list", listed.body, listed.response);
  const tools = listedItem?.result?.tools ?? [];
  report.tools = {
    count: tools.length,
    names: tools.map((tool) => tool.name),
    descriptorsValid: tools.every((tool) => typeof tool.name === "string" && typeof tool.description === "string" && tool.inputSchema && typeof tool.inputSchema === "object"),
    annotations: Object.fromEntries(tools.map((tool) => [tool.name, tool.annotations ?? null])),
  };

  for (const [method, key] of [["resources/list", "resources"], ["resources/templates/list", "resourceTemplates"], ["prompts/list", "prompts"]]) {
    const listedOptional = await rpc(method, {}, auth.accessToken, sessionId);
    const item = markOperation(method, listedOptional.body, listedOptional.response);
    if (item?.result) {
      const entries = item.result.resources ?? item.result.resourceTemplates ?? item.result.prompts ?? [];
      report[key] = { supported: true, count: entries.length, entries: entries.map((entry) => ({ name: entry.name, uri: entry.uri, description: entry.description })) };
    } else if (item?.error?.code === -32601) {
      report[key] = { supported: false, count: 0 };
    }
  }

  const testRoot = process.env.DEVSPACE_TEST_ROOT ?? "/tmp";
  const root = await mkdtemp(join(testRoot, "devspace-mcp-test-"));
  await chmod(root, 0o770);
  try {
    const opened = await rpc("tools/call", { name: "open_workspace", arguments: { path: root, mode: "checkout" } }, auth.accessToken, sessionId);
    const openedItem = markOperation("open_workspace", opened.body, opened.response);
    const workspaceId = openedItem?.result?.structuredContent?.workspaceId ?? openedItem?.result?.workspaceId;
    if (!workspaceId) throw new Error("open_workspace did not return workspaceId");
    const write = await rpc("tools/call", { name: "write", arguments: { workspaceId, path: "mcp-diagnostic.txt", content: "DevSpace MCP diagnostic\n" } }, auth.accessToken, sessionId);
    markOperation("write", write.body, write.response);
    const edit = await rpc("tools/call", {
      name: "edit",
      arguments: {
        workspaceId,
        path: "mcp-diagnostic.txt",
        edits: [{ oldText: "DevSpace MCP diagnostic\n", newText: "DevSpace MCP diagnostic edited\n" }],
      },
    }, auth.accessToken, sessionId);
    markOperation("edit", edit.body, edit.response);
    const read = await rpc("tools/call", { name: "read", arguments: { workspaceId, path: "mcp-diagnostic.txt" } }, auth.accessToken, sessionId);
    markOperation("read", read.body, read.response);
    const listedDirectory = await rpc("tools/call", { name: "ls", arguments: { workspaceId, path: "." } }, auth.accessToken, sessionId);
    markOperation("ls", listedDirectory.body, listedDirectory.response);
    const globbed = await rpc("tools/call", { name: "glob", arguments: { workspaceId, pattern: "mcp-diagnostic.txt" } }, auth.accessToken, sessionId);
    markOperation("glob", globbed.body, globbed.response);
    const grepped = await rpc("tools/call", { name: "grep", arguments: { workspaceId, pattern: "edited", path: "mcp-diagnostic.txt" } }, auth.accessToken, sessionId);
    markOperation("grep", grepped.body, grepped.response);
    const shell = await rpc("tools/call", { name: "bash", arguments: { workspaceId, command: "pwd", timeout: 10 } }, auth.accessToken, sessionId);
    markOperation("bash", shell.body, shell.response);
    const gitCommand = `git -c safe.directory=${shellQuote(testRoot)} status --short --untracked-files=no -- . && git -c safe.directory=${shellQuote(testRoot)} diff --no-ext-diff --quiet -- .`;
    const git = await rpc("tools/call", { name: "bash", arguments: { workspaceId, command: gitCommand, timeout: 10 } }, auth.accessToken, sessionId);
    markOperation("git_status_diff", git.body, git.response);
    const filesystem = await rpc("tools/call", { name: "bash", arguments: { workspaceId, command: "mkdir mcp-diagnostic-dir && mv mcp-diagnostic.txt mcp-diagnostic-dir/moved.txt && rm mcp-diagnostic-dir/moved.txt && rmdir mcp-diagnostic-dir", timeout: 10 } }, auth.accessToken, sessionId);
    markOperation("bash_filesystem_probe", filesystem.body, filesystem.response);
    const progressToken = `devspace-diagnostic-${randomUUID()}`;
    const progress = await rpc("tools/call", { _meta: { progressToken }, name: "bash", arguments: { workspaceId, command: "sleep 1", timeout: 10 } }, auth.accessToken, sessionId);
    markOperation("bash_progress_probe", progress.body, progress.response);
    report.notifications.progress = "not-observed (sleep probe returned no progress notification)";
    report.notifications.cancellation = "not-tested (a cancellation probe can hold the streamable HTTP session until the subprocess exits)";
    const invalid = await rpc("tools/call", { name: "tool_that_does_not_exist", arguments: {} }, auth.accessToken, sessionId);
    markOperation("invalid_tool_error", invalid.body, invalid.response);
    report.errors.invalidTool = report.operations.invalid_tool_error?.error ?? null;
  } finally {
    await rm(root, { recursive: true, force: true });
  }

  const reconnected = await discoverAndAuthorize();
  const second = await rpc("initialize", { protocolVersion, capabilities: {}, clientInfo: { name: "devspace-reconnect-check", version: "1.0.0" } }, reconnected.accessToken);
  report.reconnection = second.response.ok && Boolean(second.sessionId) ? "passed" : "failed";
  report.notifications.progress = "not-observed (DevSpace tools do not emit progress for the tested calls)";
  report.notifications.logging = initItem.result.capabilities?.logging ? "advertised" : "not-advertised";
  console.log(JSON.stringify(report, null, 2));
}

try {
  await run();
} catch (error) {
  report.error = error instanceof Error ? error.message : String(error);
  console.log(JSON.stringify(report, null, 2));
  process.exitCode = 1;
}
