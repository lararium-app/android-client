# Lararium product roadmap

> Status: agreed by the PO on 2026-10-29, right after v0.9.6-alpha shipped
> and the Google Play developer account was paid for. This is the cross-repo
> "north star" — issues own the detail, this owns the order and the exits.
> We stay alpha until the daemon ships v1.0; see [EXIT-ALPHA](EXIT-ALPHA.md).

## North star

Replace the OEM assistant with one the user owns: their hardware, their
model, their rules. Every track below either makes Lararium do more of the
assistant's job, or makes handing it that job feel safe. When a feature
would need trust we haven't earned yet, it ships behind a door the user
opens deliberately — never as a default.

## Standing policy (applies to every track)

- **Secrets never enter model context.** Connectors, the vault, and the
  browser use credentials at the wire, by handle — the loop references,
  it never holds.
- **Purchases are always-ask.** No "always-allow" scope may ever cross a
  payment, mirroring the send-mail rule from the purge. No raw card
  numbers (PAN) stored anywhere, ever; virtual single-merchant cards only.
- **2FA codes and SMS OTPs are user-completed steps.** The agent may wait
  for one; it may not read one.
- **Status pushes, not polls.** Connector/vault state rides the SSE event
  rails; foreground polling is the fallback, never the design.
- **Sideload stays first-class.** Play is an additional lane, never a
  dependency; the signed APK on the release page keeps shipping forever.
- **Schema-driven clients.** Connector config forms are rendered from
  daemon-declared schemas so adding a connector never requires an APK
  update.
- Zero telemetry, zero third-party analytics stays a hard invariant
  (README "What Lararium will never do").

## T0 — Play Store lane

Ship to a Play **internal track** first, then closed beta. The signing
pipeline and stable upload keystore already exist (v0.9.5+ releases).

Needed: `bundleRelease` (AAB) in CI, R8 keep-rule pass for the release
build, an upload step guarded by a service-account secret, and a
`docs/PLAY-LANE.md` runbook for the console-side checklist (app entry,
data safety, content rating, privacy-policy URL, tester group).

**Exit:** an internal-track build installs from a Play link on three
device families and updates in place across version codes.

## T1 — Credential vault

A hearth-side store for logins the agent may *use without seeing*
(Muse-style, minus the cloud). Handles + audit + per-handle rate limits
enforced at the custodia doors; phone UI is admin-only: write-only fields,
masked last-4, never an echo. Backed by the daemon keystore, single-user,
with PGP export/import later.

**Exit:** a web-login connector authenticates using a vault handle with
zero occurrences of the secret in session events, logs, or model context
(verified by redaction tests).

## T2 — Connector lifecycle

Today the catalog is inventory-only (`GET /v1/mcp/connectors`). Add
schema-driven CRUD: add/edit gated by a custody card that shows the
connector's fingerprint and requested scopes, delete always allowed,
re-auth flow for expired credentials.

New in-app **Connectors screen**: state machine per connector
(`not connected / needs re-auth / read-only / read+act / disabled`),
last-used time, and duplicate-aware add (existing connector offers Edit).

**Exit:** a user adds, re-scopes, and removes a connector entirely from
the phone, and every state change appears in the app within seconds
without polling.

## T3 — Device rails (reverse channel)

The phone exposes itself to the hearth as an MCP server (`/v1/devices`,
planned milestone). This is how Lararium reads the user's world without
it ever leaving their hardware: Calendar, Contacts, Notifications, Call
Log, Health Connect as read rails; Dialer and SMS as action rails that
raise custody cards. Wave 2 is Google Workspace via one OAuth connector
template; wave 3 is the home stack (Home Assistant, Tailscale, budgeting)
that commercial assistants don't have.

Play policy note: rails touching SMS/call-log require permission
declarations in the console; the sideload flavor ships them unfiltered.

**Exit:** "what's my afternoon look like" answers from the user's own
calendar, on a real phone, with the rail's scope visible and revocable.

## T4 — Assistant role takeover

The OEM-replacement headline: claim `ROLE_ASSISTANT` so the power button
and hold-gesture open Lararium instead of Google. Staged: (1) role +
existing overlay as the target, (2) wake word via an on-device tiny KWS
(Google's hotword is not licensable; Vosk is too heavy for 24/7),
(3) screen context via role-provided screenshots, fallback share-sheet
→ "Ask Lararium". Circle-to-search itself is proprietary with no
third-party hook — we serve the same want, not their gesture.

**Exit:** on two OEM skins, summoning the assistant reaches Lararium
with screen context, and revoking the role degrades gracefully to the
overlay.

## T5 — Watch the agent work

An "Agent at work" screen: the browser/worker emits a structured live
trace over the existing session-events SSE — step, target, intent,
redacted params — with custody cards popping inline mid-task. A live
screencast (CDP frames from the hearth's browser lane) layers on later;
the trace is the product, the video is the garnish.

**Exit:** a web task runs end-to-end while the phone shows the full trace,
zero secrets visible in it, with the pause-and-cancel buttons working.

## T6 — App hardening

Biometric gate (AndroidX BiometricPrompt, device-credential fallback) on
app open and on the scary verbs: connector add/edit, vault writes,
approval resolves, anything payment-shaped. Pair tokens move from plain
DataStore into the Android keystore so a stolen unlocked phone is not a
hearth skeleton key.

**Exit:** cold start requires biometrics; pair token survives a backup
restore only as ciphertext; all existing flows pass.

## T7 — Purchases (last, loudest rules)

Agents that buy things are trusted-scope features; we earn them after
T1–T6. Shape: user issues single-merchant virtual cards with hard spend
caps and hands those to the agent; the daemon enforces a monthly budget
at the doors; every purchase is an always-ask custody card with the full
trace attached. Track (don't bet on) Visa/Mastercard agent-payment rails
and AP2 for delegated checkout; merchant coverage is still thin.

**Exit:** a dogfood coffee is bought end-to-end with a capped virtual
card, the custody card showed merchant + amount + trace, and the vault
proved the agent never held the card number.

## Non-goals for the alpha line

- No third-party connector marketplace or user-authored skills with tool
  access before T7 ships (prompt-injection surface).
- No conversational phone-answering until the platform API surface for
  third-party screening apps is verified; basic screening research is a
  spike, not a commitment.
- No account system of any kind. Pairing is the account.
