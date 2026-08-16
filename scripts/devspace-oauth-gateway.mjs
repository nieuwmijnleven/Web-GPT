#!/usr/bin/env node

import http from "node:http";
import process from "node:process";

const LOCAL_MCP_URL = process.env.DEVSPACE_MCP_URL || "http://127.0.0.1:9191/mcp";
const LISTEN_ADDR = process.env.OAUTH_GATEWAY_LISTEN_ADDR || "127.0.0.1:9292";
const LOCAL_MCP = parseHttpUrl(LOCAL_MCP_URL, "DEVSPACE_MCP_URL");
const LOCAL_RESOURCE_URL = LOCAL_MCP.href;
const MAX_AUTH_BODY_BYTES = 256 * 1024;
const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

const server = http.createServer((req, res) => {
  void handleRequest(req, res).catch((error) => {
    if (!res.headersSent) {
      sendJson(res, 502, { error: "gateway_error" });
    } else {
      res.destroy();
    }
    log("error", req, { error: error instanceof Error ? error.message : String(error) });
  });
});

server.on("clientError", (error, socket) => {
  log("warn", null, { event: "client_error", error: error.message });
  socket.destroy();
});

const { host, port } = parseListenAddress(LISTEN_ADDR);
server.listen(port, host, () => {
  log("info", null, {
    event: "listening",
    address: `${host}:${port}`,
    localMcp: LOCAL_RESOURCE_URL,
  });
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 10_000).unref();
  });
}

async function handleRequest(req, res) {
  const requestUrl = new URL(req.url || "/", `http://${LISTEN_ADDR}`);

  if (requestUrl.pathname === "/healthz" && req.method === "GET") {
    sendJson(res, 200, { ok: true, name: "devspace-oauth-gateway" });
    return;
  }

  const shouldRewriteResource = ["/authorize", "/token", "/revoke"].includes(requestUrl.pathname);
  if (shouldRewriteResource && ["GET", "POST"].includes(req.method)) {
    const body = req.method === "POST" ? await readBody(req, MAX_AUTH_BODY_BYTES) : undefined;
    if (body !== undefined) {
      const rewritten = rewriteResourceInBody(body, req.headers["content-type"]);
      await proxyRequest(req, res, requestUrl, rewritten);
      return;
    }
    rewriteResourceInQuery(requestUrl);
  }

  await proxyRequest(req, res, requestUrl);
}

function parseHttpUrl(value, name) {
  const url = new URL(value);
  if (url.protocol !== "http:") {
    throw new Error(`${name} must use http:// for the loopback upstream`);
  }
  url.hash = "";
  url.search = "";
  return url;
}

function parseListenAddress(value) {
  if (value.startsWith("[")) {
    const closing = value.indexOf("]");
    if (closing < 0 || value[closing + 1] !== ":") {
      throw new Error(`Invalid OAUTH_GATEWAY_LISTEN_ADDR: ${value}`);
    }
    return { host: value.slice(1, closing), port: parsePort(value.slice(closing + 2)) };
  }
  const separator = value.lastIndexOf(":");
  if (separator <= 0) {
    throw new Error(`Invalid OAUTH_GATEWAY_LISTEN_ADDR: ${value}`);
  }
  return { host: value.slice(0, separator), port: parsePort(value.slice(separator + 1)) };
}

function parsePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Invalid gateway port: ${value}`);
  }
  return port;
}

function rewriteResourceInQuery(url) {
  if (url.searchParams.has("resource")) {
    url.searchParams.set("resource", LOCAL_RESOURCE_URL);
  }
}

function rewriteResourceInBody(body, contentType) {
  const type = String(contentType || "").split(";", 1)[0].trim().toLowerCase();
  if (type === "application/x-www-form-urlencoded") {
    const params = new URLSearchParams(body.toString("utf8"));
    if (params.has("resource")) {
      params.set("resource", LOCAL_RESOURCE_URL);
    }
    return Buffer.from(params.toString(), "utf8");
  }
  if (type === "application/json") {
    try {
      const value = JSON.parse(body.toString("utf8"));
      if (value && typeof value === "object" && !Array.isArray(value) && "resource" in value) {
        value.resource = LOCAL_RESOURCE_URL;
        return Buffer.from(JSON.stringify(value), "utf8");
      }
    } catch {
      // Let DevSpace return its normal malformed-body response.
    }
  }
  return body;
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    req.on("data", (chunk) => {
      length += chunk.length;
      if (length > limit) {
        reject(new Error("OAuth request body is too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function proxyRequest(req, res, requestUrl, body) {
  const target = new URL(requestUrl.pathname + requestUrl.search, LOCAL_MCP.origin);
  const headers = {};
  for (const [name, value] of Object.entries(req.headers)) {
    if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase()) && name.toLowerCase() !== "host") {
      headers[name] = value;
    }
  }
  headers.host = LOCAL_MCP.host;
  if (body !== undefined) {
    headers["content-length"] = String(body.length);
    delete headers["transfer-encoding"];
  }

  return new Promise((resolve) => {
    const upstream = http.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 80,
      method: req.method,
      path: target.pathname + target.search,
      headers,
    }, (upstreamResponse) => {
      const responseHeaders = copyResponseHeaders(upstreamResponse.headers);
      res.writeHead(upstreamResponse.statusCode || 502, responseHeaders);
      upstreamResponse.pipe(res);
      upstreamResponse.once("end", resolve);
    });
    upstream.once("error", (error) => {
      if (!res.headersSent) {
        sendJson(res, 502, { error: "upstream_unavailable" });
      }
      log("error", req, { event: "upstream_error", error: error.message });
      resolve();
    });
    req.once("aborted", () => upstream.destroy());
    if (body !== undefined) {
      upstream.end(body);
    } else {
      req.pipe(upstream);
    }
  }).finally(() => {
    log("info", req, { status: res.statusCode, proxiedPath: requestUrl.pathname });
  });
}

function copyResponseHeaders(headers) {
  const copied = {};
  for (const [name, value] of Object.entries(headers)) {
    if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase()) && value !== undefined) {
      copied[name] = value;
    }
  }
  return copied;
}

function sendJson(res, status, value, headOnly = false) {
  const body = Buffer.from(`${JSON.stringify(value)}\n`, "utf8");
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-length": body.length,
  });
  if (!headOnly) {
    res.end(body);
  } else {
    res.end();
  }
}

function log(level, req, fields = {}) {
  const record = {
    ts: new Date().toISOString(),
    level,
    service: "devspace-oauth-gateway",
    method: req?.method,
    path: req ? new URL(req.url || "/", `http://${LISTEN_ADDR}`).pathname : undefined,
    ...fields,
  };
  process.stdout.write(`${JSON.stringify(record)}\n`);
}
