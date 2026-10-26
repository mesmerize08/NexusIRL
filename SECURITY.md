# Security policy

This app handles stream keys, platform tokens and remote-control passwords. Please report
vulnerabilities privately.

## Reporting a vulnerability

Use **GitHub's private vulnerability reporting** for this repository: open the Security tab and
choose "Report a vulnerability". Please don't open a public issue for security problems.

Please include the affected version or commit, the steps to reproduce, and the impact. We aim to
acknowledge reports within 7 days.

## Scope

Examples of what's in scope:
- leaks of stream keys, tokens or passwords: storage, logs, exports, screenshots, network
- unauthenticated or weakly authenticated control of the app over the network: web remote
  control, remote-control assistant, Moblink, ingest servers
- deep links, QR codes or imported settings files that change settings or start streams without
  confirmation
- browser-widget pages that can reach app data

The design is described in [docs/threat-model.md](docs/threat-model.md) and in
[docs/PORT_PLAN.md](docs/PORT_PLAN.md) §9.

## Supported versions

Only the latest release receives security fixes. Update when a new release is published.

## Fixed security issues

| Fixed in | Issue |
|---|---|
| 0.2.1 | With the web remote control turned on, a device on the same network could make the app close by dropping a connection mid-response (no access link needed). |
