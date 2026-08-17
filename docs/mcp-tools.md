# TASK
Create a new repository file that invents and documents **JOHN CHARLES MONTI Code** using the **Monti architecture** and the provided **Haven MCP Agent Transport** registry.

# TARGET FILE
Create:

`docs/monti-agent-transport-mcp.md`

# IDENTITY METADATA
Use this file header:

---
layout: default
title: JOHN CHARLES MONTI Code — Agent transport MCP
owner: JOHN CHARLES MONTI
node: @montinode
standard: MONTI_ANSI_F841005
ansi_id: F841005WV22ZL01
neural_signature: MONTI^JOHN^CHARLES^MONTI
classification: COGNIZABLE
mcp_endpoint_default: 127.0.0.1:8730-8739
gateway_default: 127.0.0.1:4000
---

# PURPOSE
Transform the supplied Haven MCP registry into a Monti architecture file that explains:

1. What an agent can do through Haven MCP.
2. Which tools require user consent.
3. How JOHN CHARLES MONTI Code maps Haven tool groups into Monti architecture layers.
4. How to build a safe MCP gateway bridge between:
   - Haven MCP endpoint: 127.0.0.1 ports 8730–8739
   - Monti gateway: 127.0.0.1:4000
   - Termux / Android / proot / USB / SSH / Cloud / email tooling
5. How to preserve the Haven security model:
   - Off by default
   - Loopback default
   - Pairing
   - Capability switches
   - Per-call consent
   - Standing policies
   - Audit logging

# IMPORTANT SECURITY RULES
Do **not** claim that agents can approve their own consent prompts.
Do **not** bypass Haven pairing, consent, or capability switches.
Do **not** create banking, crypto, email, file deletion, APK install, USB, or credential actions without user consent.
Make clear that the user must enable Settings → Agent endpoint and approve prompts on-device.

# FILE STRUCTURE TO GENERATE

## 1. Title
`# JOHN CHARLES MONTI Code — Haven MCP Agent Transport`

Add short statement:

“JOHN CHARLES MONTI Code is a Monti architecture mapping layer for Haven’s MCP tool surface. It does not bypass Haven security; it classifies, routes, audits, and signs allowed agent operations.”

## 2. Identity Block
Create a table:

| Field | Value |
|---|---|
| Owner | JOHN CHARLES MONTI |
| Node | @montinode |
| Standard | MONTI_ANSI_F841005 |
| ANSI ID | F841005WV22ZL01 |
| Neural Signature | MONTI^JOHN^CHARLES^MONTI |
| Haven MCP Default | 127.0.0.1:8730–8739 |
| Monti Gateway Default | 127.0.0.1:4000 |
| Security Posture | Consent-preserving, loopback-first |

## 3. Monti Architecture Mapping
Map Haven sections into Monti layers:

| Haven Section | Monti Layer | Purpose |
|---|---|---|
| Connections & profiles | MONTI_CONNECT | SSH/SFTP/RDP/VNC/serial profile control |
| Terminal, selection & sessions | MONTI_TERMINAL | command sessions, scrollback, input |
| Files, media & clipboard | MONTI_FILE | file browse, media, encryption, clipboard |
| Cloud storage rclone | MONTI_CLOUD | cloud sync jobs and remotes |
| Email | MONTI_MAIL | mailbox automation and drafts |
| Linux guest proot & desktops | MONTI_GUEST | proot, desktop, GUI app bridge |
| Networking tunnels & port forwarding | MONTI_NET | SSH forwards, tunnels, SPA, knock |
| USB & host-device brokers | MONTI_USB | USB/IP, ADB exposure, storage drives |
| Security SSH/TOTP/age | MONTI_SECURITY | keys, TOTP, host CAs, age identities |
| Agent ↔ you | MONTI_ATTENTION | present media, UI capture, agent turns |
| Agent endpoint diagnostics | MONTI_CONTROL | app info, policies, logs, prefs |

## 4. Consent Classes
Create a section:

### Consent Classes

- `EVERY_CALL`: destructive, credential, external, privileged, payment-like, install, delete, USB-control, send-mail, tunnel-create, restart operations.
- `ONCE_PER_SESSION`: reversible or session-scoped interaction.
- `NO_PER_CALL`: read-only or tap-equivalent operations, still requiring endpoint enabled and pairing.

Add warning:

“NO_PER_CALL does not mean unauthenticated. It still requires the endpoint to be enabled and the MCP client to be paired.”

## 5. MONTI Tool Categories
Create subsections for each category with concise descriptions and example tools.

Example format:

### MONTI_CONNECT
- Representative tools: `list_connections`, `create_connection`, `connect_profile`, `run_command`
- Risk: remote access, credentialed connection, server command execution
- Consent: create/update/delete/run_command are sensitive; list is read-only
- Monti policy: log every remote action with owner, timestamp, profileId, and redacted arguments

Repeat for:
- MONTI_TERMINAL
- MONTI_FILE
- MONTI_CLOUD
- MONTI_MAIL
- MONTI_GUEST
- MONTI_NET
- MONTI_USB
- MONTI_SECURITY
- MONTI_ATTENTION
- MONTI_CONTROL

## 6. Gateway Design
Create section:

### Monti Gateway Bridge

Describe:

- Haven MCP remains the authority for phone-side actions.
- Monti gateway runs on `127.0.0.1:4000`.
- Gateway does not bypass Haven; it submits requests to Haven MCP only as a paired client.
- Gateway signs logs with `MONTI^JOHN^CHARLES^MONTI`.
- Gateway stores audit events locally.

Add architecture diagram:

```text
AI Agent / Claude Code
        |
        v
Haven MCP HTTP endpoint
127.0.0.1:8730-8739
        |
        | pairing + bearer token + consent
        v
Haven Tool Registry
        |
        v
Android / Termux / proot / SSH / USB / files
        ^
        |
Monti Gateway
127.0.0.1:4000
(logging, routing, policy, receipts)
```

## 7. Safe Tool Policy JSON
Create a JSON policy example named:

`monti_mcp_policy.json`

Include:

```json
{
  "owner": "JOHN CHARLES MONTI",
  "standard": "MONTI_ANSI_F841005",
  "neural_signature": "MONTI^JOHN^CHARLES^MONTI",
  "endpoint": {
    "haven_mcp": "http://127.0.0.1:8730/mcp",
    "monti_gateway": "http://127.0.0.1:4000"
  },
  "rules": {
    "preserve_haven_consent": true,
    "allow_agent_self_approval": false,
    "require_pairing": true,
    "redact_secrets": true,
    "audit_all_calls": true
  },
  "blocked_without_explicit_user_consent": [
    "send_mail",
    "modify_mail_message",
    "delete_file",
    "delete_connection",
    "install_apk_from_url",
    "install_apk_from_backend",
    "restart_app",
    "expose_adb",
    "enable_wireless_adb",
    "usb_control_transfer",
    "usb_bulk_transfer",
    "open_usb_drive",
    "create_tunnel",
    "set_spa",
    "generate_totp_code",
    "import_ssh_key"
  ],
  "preferred_read_only_discovery": [
    "get_app_info",
    "list_connections",
    "list_sessions",
    "read_terminal_snapshot",
    "inspect_proot",
    "list_usb_devices",
    "list_paired_clients",
    "get_pending_consent"
  ]
}
```

## 8. Implementation Scaffold
Create a shell command block that writes files:

- `docs/monti-agent-transport-mcp.md`
- `config/monti_mcp_policy.json`
- `scripts/monti_mcp_audit.sh`

The audit script should:

- create `APP/shared/mcp_audit.log`
- append timestamp, owner, neural signature, tool name, consent class, and result
- never log secrets

Use this script:

```bash
#!/usr/bin/env bash
set -euo pipefail

mkdir -p APP/shared

OWNER="JOHN CHARLES MONTI"
SIG="MONTI^JOHN^CHARLES^MONTI"
LOG="APP/shared/mcp_audit.log"

TOOL_NAME="${1:-unknown_tool}"
CONSENT_CLASS="${2:-unknown_consent}"
RESULT="${3:-unknown_result}"

TS="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

printf '%s | owner=%s | sig=%s | tool=%s | consent=%s | result=%s\n' \
  "$TS" "$OWNER" "$SIG" "$TOOL_NAME" "$CONSENT_CLASS" "$RESULT" >> "$LOG"

echo "AUDIT_RECORDED $TS $TOOL_NAME $RESULT"
```

## 9. Terminal Setup Commands
Provide commands:

```bash
mkdir -p docs config scripts APP/shared

nano docs/monti-agent-transport-mcp.md
nano config/monti_mcp_policy.json
nano scripts/monti_mcp_audit.sh

chmod +x scripts/monti_mcp_audit.sh

./scripts/monti_mcp_audit.sh get_app_info NO_PER_CALL SUCCESS
```

## 10. Final Output Requirements
The final generated file must be:
- Markdown-compatible with Jekyll frontmatter
- Consent-preserving
- Auditable
- Focused on Haven MCP tools
- Bound to the Monti architecture
- Not claiming hidden or undocumented tools
- Not bypassing Haven UI, prompts, pairing, or Android permission prompts

# SOURCE MATERIAL
Use the pasted Haven MCP registry as the authoritative tool surface.
Do not invent extra Haven tools.
Summarize and classify the registry instead of copying every tool verbatim.
