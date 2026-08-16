# Verified DevSpace MCP capability report

Evidence source: `scripts/check-devspace-mcp.sh` against the service-managed DevSpace 1.0.6 endpoint on 2026-08-16. The diagnostic used OAuth discovery, dynamic client registration, owner approval, authorization-code PKCE, a disposable directory below the configured allowed root, and a second MCP session.

## Negotiated protocol

| Area | Evidence | Result |
|---|---|---|
| Transport | Streamable HTTP at `http://127.0.0.1:9191/mcp` | Confirmed |
| Initialization | Protocol `2025-06-18`, session ID returned | Passed |
| Server | `devspace`, title `DevSpace`, version `0.1.0` | Confirmed |
| Authentication | OAuth authorization code + PKCE, DCR, bearer access token, refresh-token grant advertised | Passed locally |
| Resources | `resources/list` returned one embedded app resource, `ui://devspace/workspace-app.html` | Confirmed |
| Resource templates | `resources/templates/list` returned an empty list | Confirmed empty |
| Prompts | `prompts/list` returned JSON-RPC `-32601 Method not found` | Unsupported by current server |
| Server capabilities | `resources.listChanged` and `tools.listChanged` advertised | Confirmed advertised |
| Logging | No logging capability advertised | Not advertised by current server |
| Progress | A bash call carrying a progress token produced no progress notification | Not observed |
| Cancellation | Not claimed; a safe cancellation probe can hold this Streamable HTTP session until its subprocess exits | Not tested |
| Completion/subscriptions | No completion or subscription capability was advertised or exercised | Not advertised; no test path |

## Complete local tool catalog

The service runs `DEVSPACE_TOOL_MODE=full`, so the catalog returned by DevSpace is:

| Tool | Annotation | Local test |
|---|---|---|
| `open_workspace` | `readOnlyHint: true` | Passed |
| `read` | `readOnlyHint: true` | Passed |
| `write` | `readOnlyHint: false`, `destructiveHint: true`, `idempotentHint: false`, `openWorldHint: false` | Passed in disposable directory |
| `edit` | `readOnlyHint: false`, `destructiveHint: true`, `idempotentHint: false`, `openWorldHint: false` | Disposable file edit passed |
| `grep` | `readOnlyHint: true` | Disposable file search passed |
| `glob` | `readOnlyHint: true` | Disposable filename search passed |
| `ls` | `readOnlyHint: true` | Disposable directory listing passed |
| `bash` | `readOnlyHint: false`, `destructiveHint: true`, `idempotentHint: false`, `openWorldHint: true` | `pwd`, Git status/diff, disposable create/move/delete, and `sleep 1` passed |

Tool descriptors all had names, descriptions, and object input schemas. The diagnostic also verified an MCP tool error response for an unknown tool (`-32602`, returned as a tool error). An `open_workspace` request for `/tmp` was rejected by the allowed-root check. All disposable files and directories were removed after the run.

## Catalog comparison

| Layer | Count/status |
|---|---|
| DevSpace-reported tools | 8: the catalog above |
| Tunnel-visible tools | Not tested: no tunnel ID/runtime key |
| ChatGPT-scanned tools | Not tested: no Business workspace session was available |
| Callable tools | 8 locally; every listed tool exercised in the disposable workspace |
| Tools requiring confirmation | Expected for mutating/open-world tools from annotations; ChatGPT policy review pending |
| Tools blocked by ChatGPT policy | None observed; ChatGPT validation unavailable |
| Compatibility failures | None in local MCP tests; remote compatibility unknown |

This report intentionally does not claim that ChatGPT preserves the catalog until a real Business app scan and invocation succeed. There is no fixed allowlist or command replacement in the tunnel profile.
