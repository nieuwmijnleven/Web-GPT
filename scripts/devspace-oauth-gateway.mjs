#!/usr/bin/env node

import http from "node:http";
import process from "node:process";

/*
 * Internal services
 *
 * DevSpace MCP/OAuth server:
 *   http://127.0.0.1:9191
 *
 * This gateway:
 *   http://127.0.0.1:9292
 *
 * Public OAuth endpoint:
 *   https://auth.example.com
 */

const LOCAL_MCP_URL =
  process.env.DEVSPACE_MCP_URL ||
  "http://127.0.0.1:9191/mcp";

const LISTEN_ADDR =
  process.env.OAUTH_GATEWAY_LISTEN_ADDR ||
  "127.0.0.1:9292";

/*
 * Public URL advertised to ChatGPT.
 *
 * Example:
 *   OAUTH_PUBLIC_BASE_URL=https://auth.example.com
 */
const OAUTH_PUBLIC_BASE_URL =
  process.env.OAUTH_PUBLIC_BASE_URL || "";

/*
 * Public resource identifier.
 *
 * By default:
 *   https://auth.example.com/mcp
 *
 * This is separate so that it can later be changed to a
 * tunnel/resource URL without changing the OAuth issuer.
 */
const MCP_PUBLIC_RESOURCE_URL =
  process.env.MCP_PUBLIC_RESOURCE_URL ||
  (OAUTH_PUBLIC_BASE_URL
    ? `${stripTrailingSlash(OAUTH_PUBLIC_BASE_URL)}/mcp`
    : "");

const LOCAL_MCP =
  parseHttpUrl(LOCAL_MCP_URL, "DEVSPACE_MCP_URL");

const LOCAL_RESOURCE_URL = LOCAL_MCP.href;

const PUBLIC_OAUTH_BASE =
  OAUTH_PUBLIC_BASE_URL
    ? parsePublicBaseUrl(
        OAUTH_PUBLIC_BASE_URL,
        "OAUTH_PUBLIC_BASE_URL",
      )
    : null;

const PUBLIC_RESOURCE =
  MCP_PUBLIC_RESOURCE_URL
    ? parsePublicUrl(
        MCP_PUBLIC_RESOURCE_URL,
        "MCP_PUBLIC_RESOURCE_URL",
      )
    : null;

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

const OAUTH_METADATA_PATHS = new Set([
  "/.well-known/oauth-protected-resource",
  "/.well-known/oauth-protected-resource/mcp",
  "/.well-known/oauth-authorization-server",
  "/.well-known/openid-configuration",
]);

const server = http.createServer((req, res) => {
  void handleRequest(req, res).catch((error) => {
    if (!res.headersSent) {
      sendJson(res, 502, {
        error: "gateway_error",
      });
    } else {
      res.destroy();
    }

    log("error", req, {
      error:
        error instanceof Error
          ? error.message
          : String(error),
    });
  });
});

server.on("clientError", (error, socket) => {
  log("warn", null, {
    event: "client_error",
    error: error.message,
  });

  socket.destroy();
});

const { host, port } =
  parseListenAddress(LISTEN_ADDR);

server.listen(port, host, () => {
  log("info", null, {
    event: "listening",
    address: `${host}:${port}`,
    localMcp: LOCAL_RESOURCE_URL,
    publicOAuthBase:
      PUBLIC_OAUTH_BASE?.href || null,
    publicResource:
      PUBLIC_RESOURCE?.href || null,
  });
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => {
    server.close(() => process.exit(0));

    setTimeout(
      () => process.exit(1),
      10_000,
    ).unref();
  });
}

async function handleRequest(req, res) {
  const requestUrl = new URL(
    req.url || "/",
    `http://${LISTEN_ADDR}`,
  );

  if (
    requestUrl.pathname === "/healthz" &&
    req.method === "GET"
  ) {
    sendJson(res, 200, {
      ok: true,
      name: "devspace-oauth-gateway",
      publicOAuthBase:
        PUBLIC_OAUTH_BASE?.href || null,
      publicResource:
        PUBLIC_RESOURCE?.href || null,
    });

    return;
  }

  /*
   * OAuth requests coming from ChatGPT may contain:
   *
   *   resource=https://public-resource/...
   *
   * The internal DevSpace server knows itself as:
   *
   *   http://127.0.0.1:9191/mcp
   *
   * Rewrite public resource -> internal resource before proxying.
   */
  const shouldRewriteResource = [
    "/authorize",
    "/token",
    "/revoke",
  ].includes(requestUrl.pathname);

  if (
    shouldRewriteResource &&
    ["GET", "POST"].includes(req.method)
  ) {
    const body =
      req.method === "POST"
        ? await readBody(
            req,
            MAX_AUTH_BODY_BYTES,
          )
        : undefined;

    if (body !== undefined) {
      const rewritten =
        rewriteResourceInBody(
          body,
          req.headers["content-type"],
        );

      await proxyRequest(
        req,
        res,
        requestUrl,
        rewritten,
      );

      return;
    }

    rewriteResourceInQuery(requestUrl);
  }

  await proxyRequest(
    req,
    res,
    requestUrl,
  );
}

function parseHttpUrl(value, name) {
  const url = new URL(value);

  if (url.protocol !== "http:") {
    throw new Error(
      `${name} must use http:// for the loopback upstream`,
    );
  }

  url.hash = "";
  url.search = "";

  return url;
}

function parsePublicBaseUrl(value, name) {
  const url = parsePublicUrl(
    value,
    name,
  );

  url.pathname = "/";
  url.search = "";
  url.hash = "";

  return url;
}

function parsePublicUrl(value, name) {
  const url = new URL(value);

  if (url.protocol !== "https:") {
    throw new Error(
      `${name} must use https://`,
    );
  }

  url.hash = "";

  return url;
}

function stripTrailingSlash(value) {
  return String(value).replace(/\/+$/, "");
}

function parseListenAddress(value) {
  if (value.startsWith("[")) {
    const closing = value.indexOf("]");

    if (
      closing < 0 ||
      value[closing + 1] !== ":"
    ) {
      throw new Error(
        `Invalid OAUTH_GATEWAY_LISTEN_ADDR: ${value}`,
      );
    }

    return {
      host: value.slice(1, closing),
      port: parsePort(
        value.slice(closing + 2),
      ),
    };
  }

  const separator =
    value.lastIndexOf(":");

  if (separator <= 0) {
    throw new Error(
      `Invalid OAUTH_GATEWAY_LISTEN_ADDR: ${value}`,
    );
  }

  return {
    host: value.slice(0, separator),
    port: parsePort(
      value.slice(separator + 1),
    ),
  };
}

function parsePort(value) {
  const port = Number(value);

  if (
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) {
    throw new Error(
      `Invalid gateway port: ${value}`,
    );
  }

  return port;
}

/*
 * ChatGPT/public OAuth request:
 *
 *   resource=https://auth.example.com/mcp
 *
 * Internal DevSpace expects:
 *
 *   resource=http://127.0.0.1:9191/mcp
 */
function rewriteResourceInQuery(url) {
  if (url.searchParams.has("resource")) {
    url.searchParams.set(
      "resource",
      LOCAL_RESOURCE_URL,
    );
  }
}

function rewriteResourceInBody(
  body,
  contentType,
) {
  const type = String(
    contentType || "",
  )
    .split(";", 1)[0]
    .trim()
    .toLowerCase();

  if (
    type ===
    "application/x-www-form-urlencoded"
  ) {
    const params =
      new URLSearchParams(
        body.toString("utf8"),
      );

    if (params.has("resource")) {
      params.set(
        "resource",
        LOCAL_RESOURCE_URL,
      );
    }

    return Buffer.from(
      params.toString(),
      "utf8",
    );
  }

  if (type === "application/json") {
    try {
      const value = JSON.parse(
        body.toString("utf8"),
      );

      if (
        value &&
        typeof value === "object" &&
        !Array.isArray(value) &&
        "resource" in value
      ) {
        value.resource =
          LOCAL_RESOURCE_URL;

        return Buffer.from(
          JSON.stringify(value),
          "utf8",
        );
      }
    } catch {
      /*
       * Let DevSpace return its normal
       * malformed-body response.
       */
    }
  }

  return body;
}

function readBody(req, limit) {
  return new Promise(
    (resolve, reject) => {
      const chunks = [];
      let length = 0;

      req.on("data", (chunk) => {
        length += chunk.length;

        if (length > limit) {
          reject(
            new Error(
              "OAuth request body is too large",
            ),
          );

          req.destroy();
          return;
        }

        chunks.push(chunk);
      });

      req.on("end", () =>
        resolve(
          Buffer.concat(chunks),
        ),
      );

      req.on("error", reject);
    },
  );
}

function proxyRequest(
  req,
  res,
  requestUrl,
  body,
) {
  const target = new URL(
    requestUrl.pathname +
      requestUrl.search,
    LOCAL_MCP.origin,
  );

  const headers = {};

  for (
    const [name, value]
    of Object.entries(req.headers)
  ) {
    const lower =
      name.toLowerCase();

    if (
      !HOP_BY_HOP_HEADERS.has(lower) &&
      lower !== "host"
    ) {
      headers[name] = value;
    }
  }

  /*
   * Internal server must see its own Host.
   */
  headers.host = LOCAL_MCP.host;

  if (body !== undefined) {
    headers["content-length"] =
      String(body.length);

    delete headers[
      "transfer-encoding"
    ];
  }

  return new Promise((resolve) => {
    const upstream = http.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || 80,
        method: req.method,
        path:
          target.pathname +
          target.search,
        headers,
      },
      (upstreamResponse) => {
        const shouldRewriteMetadata =
          Boolean(
            PUBLIC_OAUTH_BASE &&
            OAUTH_METADATA_PATHS.has(
              requestUrl.pathname,
            ),
          );

        /*
         * Metadata responses must be buffered
         * because localhost URLs need to be
         * rewritten before reaching ChatGPT.
         */
        if (shouldRewriteMetadata) {
          handleMetadataResponse(
            req,
            res,
            requestUrl,
            upstreamResponse,
            resolve,
          );

          return;
        }

        /*
         * For ordinary responses we still
         * rewrite OAuth-related response headers,
         * especially WWW-Authenticate.
         */
        const responseHeaders =
          copyAndRewriteResponseHeaders(
            upstreamResponse.headers,
          );

        res.writeHead(
          upstreamResponse.statusCode ||
            502,
          responseHeaders,
        );

        upstreamResponse.pipe(res);

        upstreamResponse.once(
          "end",
          resolve,
        );
      },
    );

    upstream.once(
      "error",
      (error) => {
        if (!res.headersSent) {
          sendJson(res, 502, {
            error:
              "upstream_unavailable",
          });
        }

        log("error", req, {
          event: "upstream_error",
          error: error.message,
        });

        resolve();
      },
    );

    req.once("aborted", () =>
      upstream.destroy(),
    );

    if (body !== undefined) {
      upstream.end(body);
    } else {
      req.pipe(upstream);
    }
  }).finally(() => {
    log("info", req, {
      status: res.statusCode,
      proxiedPath:
        requestUrl.pathname,
    });
  });
}

function handleMetadataResponse(
  req,
  res,
  requestUrl,
  upstreamResponse,
  resolve,
) {
  const chunks = [];

  upstreamResponse.on(
    "data",
    (chunk) => {
      chunks.push(chunk);
    },
  );

  upstreamResponse.once(
    "end",
    () => {
      const originalBody =
        Buffer.concat(chunks);

      let outputBody =
        originalBody;

      try {
        const metadata =
          JSON.parse(
            originalBody.toString(
              "utf8",
            ),
          );

        const rewritten =
          rewriteOAuthMetadata(
            requestUrl.pathname,
            metadata,
          );

        outputBody =
          Buffer.from(
            `${JSON.stringify(
              rewritten,
            )}\n`,
            "utf8",
          );
      } catch (error) {
        /*
         * If upstream returned non-JSON,
         * leave it untouched.
         */
        log("warn", req, {
          event:
            "metadata_rewrite_failed",
          error:
            error instanceof Error
              ? error.message
              : String(error),
        });
      }

      const responseHeaders =
        copyAndRewriteResponseHeaders(
          upstreamResponse.headers,
        );

      delete responseHeaders[
        "content-length"
      ];

      delete responseHeaders[
        "content-encoding"
      ];

      delete responseHeaders.etag;

      responseHeaders[
        "content-length"
      ] = String(
        outputBody.length,
      );

      responseHeaders[
        "cache-control"
      ] = "no-store";

      res.writeHead(
        upstreamResponse.statusCode ||
          502,
        responseHeaders,
      );

      if (req.method === "HEAD") {
        res.end();
      } else {
        res.end(outputBody);
      }

      resolve();
    },
  );

  upstreamResponse.once(
    "error",
    (error) => {
      if (!res.headersSent) {
        sendJson(res, 502, {
          error:
            "upstream_response_error",
        });
      }

      log("error", req, {
        event:
          "upstream_response_error",
        error: error.message,
      });

      resolve();
    },
  );
}

/*
 * Rewrite OAuth discovery metadata.
 */
function rewriteOAuthMetadata(
  pathname,
  value,
) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    !PUBLIC_OAUTH_BASE
  ) {
    return value;
  }

  const publicBase =
    stripTrailingSlash(
      PUBLIC_OAUTH_BASE.href,
    );

  const rewritten = {
    ...value,
  };

  /*
   * RFC 9728 Protected Resource Metadata
   */
  if (
    pathname ===
      "/.well-known/oauth-protected-resource" ||
    pathname ===
      "/.well-known/oauth-protected-resource/mcp"
  ) {
    if (PUBLIC_RESOURCE) {
      rewritten.resource =
        PUBLIC_RESOURCE.href;
    }

    rewritten.authorization_servers = [
      `${publicBase}/`,
    ];

    return rewritten;
  }

  /*
   * RFC 8414 Authorization Server Metadata
   * / OIDC discovery
   */
  if (
    pathname ===
      "/.well-known/oauth-authorization-server" ||
    pathname ===
      "/.well-known/openid-configuration"
  ) {
    rewritten.issuer =
      `${publicBase}/`;

    rewritten.authorization_endpoint =
      `${publicBase}/authorize`;

    rewritten.token_endpoint =
      `${publicBase}/token`;

    if (
      "registration_endpoint" in
      rewritten
    ) {
      rewritten.registration_endpoint =
        `${publicBase}/register`;
    }

    if (
      "revocation_endpoint" in
      rewritten
    ) {
      rewritten.revocation_endpoint =
        `${publicBase}/revoke`;
    }

    if (
      "introspection_endpoint" in
      rewritten
    ) {
      rewritten.introspection_endpoint =
        `${publicBase}/introspect`;
    }

    return rewritten;
  }

  return rewritten;
}

/*
 * Rewrite OAuth-related response headers.
 *
 * This is CRITICAL because /mcp currently returns:
 *
 * WWW-Authenticate:
 *   Bearer ...
 *   resource_metadata="http://127.0.0.1:9191/..."
 *
 * ChatGPT must receive the public URL instead.
 */
function copyAndRewriteResponseHeaders(
  headers,
) {
  const copied = {};

  for (
    const [name, value]
    of Object.entries(headers)
  ) {
    const lower =
      name.toLowerCase();

    if (
      HOP_BY_HOP_HEADERS.has(lower) ||
      value === undefined
    ) {
      continue;
    }

    if (
      lower ===
      "www-authenticate"
    ) {
      copied[name] =
        rewriteAuthenticateHeader(
          value,
        );

      continue;
    }

    if (
      lower === "location"
    ) {
      copied[name] =
        rewritePublicLocation(
          value,
        );

      continue;
    }

    copied[name] = value;
  }

  return copied;
}

function rewriteAuthenticateHeader(
  value,
) {
  if (
    !PUBLIC_OAUTH_BASE ||
    value === undefined
  ) {
    return value;
  }

  if (Array.isArray(value)) {
    return value.map((item) =>
      rewriteAuthenticateHeader(
        item,
      ),
    );
  }

  const text = String(value);

  /*
   * Rewrite any upstream localhost OAuth
   * metadata URL into the public OAuth domain.
   */
  return text.replace(
    /resource_metadata="http:\/\/127\.0\.0\.1:\d+(\/[^"]*)"/gi,
    (_match, path) =>
      `resource_metadata="${stripTrailingSlash(
        PUBLIC_OAUTH_BASE.href,
      )}${path}"`,
  );
}

/*
 * OAuth /authorize may respond with Location.
 *
 * Rewrite redirects pointing at the internal
 * OAuth origin back to the public domain.
 */
function rewritePublicLocation(
  value,
) {
  if (
    !PUBLIC_OAUTH_BASE ||
    value === undefined
  ) {
    return value;
  }

  if (Array.isArray(value)) {
    return value.map((item) =>
      rewritePublicLocation(item),
    );
  }

  const text =
    String(value);

  if (
    text.startsWith(
      LOCAL_MCP.origin,
    )
  ) {
    return (
      stripTrailingSlash(
        PUBLIC_OAUTH_BASE.href,
      ) +
      text.slice(
        LOCAL_MCP.origin.length,
      )
    );
  }

  return text;
}

function sendJson(
  res,
  status,
  value,
  headOnly = false,
) {
  const body =
    Buffer.from(
      `${JSON.stringify(value)}\n`,
      "utf8",
    );

  res.writeHead(status, {
    "content-type":
      "application/json; charset=utf-8",
    "cache-control":
      "no-store",
    "content-length":
      body.length,
  });

  if (!headOnly) {
    res.end(body);
  } else {
    res.end();
  }
}

function log(
  level,
  req,
  fields = {},
) {
  const record = {
    ts: new Date().toISOString(),
    level,
    service:
      "devspace-oauth-gateway",
    method: req?.method,
    path: req
      ? new URL(
          req.url || "/",
          `http://${LISTEN_ADDR}`,
        ).pathname
      : undefined,
    ...fields,
  };

  process.stdout.write(
    `${JSON.stringify(record)}\n`,
  );
}