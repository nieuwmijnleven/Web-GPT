# Security model

DevSpace is a trusted local coding principal, not a restricted read-only file API. The service account can invoke `/bin/bash`, Git, package managers, build tools, and any other executable reachable with its OS permissions inside the allowed project ACL. The model therefore relies on layered controls:

- loopback-only binding at `127.0.0.1:9191`;
- loopback-only OAuth-aware gateway at `127.0.0.1:9292` with a fixed DevSpace upstream;
- DevSpace OAuth owner approval and bearer verification;
- explicit allowed roots configured by `DEVSPACE_ALLOWED_ROOTS` (for example `/srv/devspace-workspaces`) with symlink/path containment in DevSpace;
- dedicated `devspace` account with no login shell and no sudo grant;
- systemd `NoNewPrivileges`, private per-service scratch directory, restrictive umask, and bounded writable paths;
- outbound-only Secure MCP Tunnel; no public ingress;
- separate tunnel administration and runtime credentials;
- ChatGPT app action review and native confirmation for mutating/open-world operations;
- journald logging with shell command previews disabled.

The ACL needed for the dedicated account is intentionally scoped to the configured workspace and the installed DevSpace package. It is not a claim that arbitrary shell execution is safe: repository prompt injection, package installation, Git remotes, databases, containers, network access, and service operations remain high-impact capabilities.

Secrets live outside Git: the DevSpace owner token is in `/var/lib/devspace/.devspace/auth.json` mode 0600; tunnel runtime settings belong in `/etc/devspace/openai-mcp-tunnel.env` mode 0640 root:devspace. Neither service unit embeds a secret. The gateway logs method/path/status only and never logs OAuth request bodies. Logs do not enable shell command previews or raw HTTP bodies.
