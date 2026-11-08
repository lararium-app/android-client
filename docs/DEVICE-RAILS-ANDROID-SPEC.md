# DEVICE-RAILS-ANDROID-SPEC — Android client device rails (T3)

Status: **FREEZE-PROPOSED (v1)** — 2026-11-02. Conforms to DEVICE-RAILS-SPEC v2 (daemon, shipped).
Merging this document freezes the Android client design for roadmap milestone T3.
Implementation is tracked in the `android-client` repository and MUST land the V-D suite below.

Companions: DEVICE-RAILS-SPEC v2 (daemon authoritative wire contract), ROADMAP.md (T3 track),
EXIT-ALPHA.md (house style, trust posture), ARCHITECTURE.md, PLAY-LANE.md (Play declarations).

## Context

Milestone T3 establishes a reverse channel: the phone acts as an authenticated HTTPS push client exposing
on-device capabilities (`calendar`, `contacts`, `notifications`, `calllog`, `health`, `dialer`, `sms`) to
the hearth. The daemon half is shipped and frozen (`DEVICE-RAILS-SPEC v2`). Tailnet-pull was rejected by
PO directive (2026-11-01); the phone connects outbound to the hearth, maintaining a single persistent
SSE stream for action dispatch and pushing incremental rail deltas over HTTPS.

This specification governs the Android client implementation: a dedicated foreground service for stream
liveness without FCM, a pure-JVM lifecycle state machine, granular runtime permission handling with graceful
degradation, double-gated action approvals, battery-respecting delta sync, and strict local verification.

> [!NOTE]
> *Wire Contract Authority:* The shipped daemon spec (`.agy-material-daemon-spec.md` D2/D4 and Go router
> `internal/surface/devices_routes.go`) defines `rails_declared` as `[{"name","read_only","enabled"}]` with
> tool schemas frozen server-side (`mcp__<device>__<rail>_<verb>`). Where informal endpoint summaries mention
> client-declared tool schemas, the daemon material specification is authoritative and strictly followed.

## Requirements

### A1 — Wire Endpoints and Registration Handshake
The client communicates exclusively with the hearth surface via `LarariumClient` and `Net`:
- `POST /v1/devices/register`: Bearer-authenticated with the pairing token (`lar1_` from `SecureKeyManager`).
  Body: `{"device_id": slug, "display_name": string, "rails_declared": [{"name": string, "read_only": bool}], "fingerprint": string}`.
  Slug must match `^[a-z0-9][a-z0-9_-]{0,31}$` (derived from sanitized `Build.MODEL`).
- Handshake Fail-Closed: Read rails (`calendar`, `contacts`, `notifications`, `calllog`, `health`) MUST declare
  `read_only: true`; action rails (`dialer`, `sms`) MUST declare `read_only: false`. The client derives this flag
  via pure logic before POSTing; any mismatch triggers daemon 400 `rail_mismatch` with zero state saved.
- Handshake Response: 202 Accepted `{"device_id": string, "card_id": string}` indicates pending custody card review
  on the hearth. 409 `duplicate_name` or `pending_add` surfaces an in-app error without retrying.
- `GET /v1/devices/<id>/stream`: Persistent SSE stream opened by the phone for receiving action dispatches.
- `POST /v1/devices/<id>/sync`: Push sync carrying `{"rail": string, "snapshot": json?, "deltas": [delta]?, "seq": int64}`.
- `POST /v1/devices/<id>/actions/<action_id>/receipt`: Resolves action execution with `{"ok": bool, "result": json?, "error": string?}`.
- `GET /v1/devices/events` & `GET /v1/devices/<id>/events`: SSE admin events; client observes `device_state` and `device_gone`.
- `PATCH /v1/devices/<id>/rails`: Card-gated rail toggle initiated by user or daemon admin.
- `DELETE /v1/devices/<id>`: Unpairs/forgets device; client purges local sync cursors and transitions to unpaired.

### A2 — Stream Transport and No-FCM Liveness
No Firebase Cloud Messaging (FCM) or proprietary push services may ever be used.
- Foreground Service: Stream liveness is maintained by a dedicated foreground service (`DeviceStreamService`)
  holding an ongoing system notification. The notification channel enforces `VISIBILITY_PRIVATE` with a content-free
  `publicVersion` ("Lararium device link active") to prevent lock-screen leakage.
- Capped Backoff Reconnection: Connection drops trigger exponential backoff capped at 60s (2s, 4s, 8s, 16s, 32s, 60s).
  Heartbeat pings (`: ping\n\n` every 5s) reset the socket read timeout (120s). Active `ConnectivityManager.NetworkCallback`
  instantly triggers reconnect on network switch (cellular ↔ Wi-Fi), bypassing remaining backoff delay.
- Epoch-Tagged Connections: Every connection attempt generates a monotonically increasing `connection_epoch`. Action
  dispatches arriving from a superseded or severed socket are discarded; receipts are never emitted for obsolete epochs.
- Re-Pair Terminal Gate: HTTP 401 or 403 responses immediately halt all reconnection attempts, mark the client state
  as `Unpaired`, and display a high-priority "Hearth pairing revoked — tap to re-pair" notification.
- UnifiedPush Wake Hint: Optional integration with self-hosted UnifiedPush distributors (e.g. ntfy) serves strictly
  as an opportunistic background wake hint to reconnect; UnifiedPush is never used as an action or data payload transport.

### A3 — Pairing UX and Identity Binding
- Deep-Link Ingestion: Consumes the standard `lararium://pair?host=…&token=…` deep link and QR pairing sheet.
- Devices Management Screen: A dedicated screen in Settings allows the user to inspect device status, edit the display
  name, view rail statuses, and selectively toggle rails on/off before registration.
- Security Policy: All connections validate `EndpointPolicy` (HTTPS enforced universally; cleartext HTTP permitted only
  on loopback, RFC1918 LAN, and CGNAT/Tailnet 100.64.0.0/10). Pairing tokens are stored exclusively in `SecureKeyManager`
  (AES-256-GCM via Android Keystore) and are never logged, exported, or serialized into diagnostics. Per-device rail
  tokens are abolished; the pairing token serves as the singular device identity bearer.

### A4 — Permission Model and Graceful Degradation
- Granular Permissions: Each rail maps to exactly one platform runtime permission:
  - `calendar`: `READ_CALENDAR`
  - `contacts`: `READ_CONTACTS`
  - `notifications`: `BIND_NOTIFICATION_LISTENER_SERVICE` (system listener approval)
  - `calllog`: `READ_CALL_LOG`
  - `health`: `androidx.health.connect.client` read permissions (optional/graceful)
  - `dialer`: `CALL_PHONE`
  - `sms`: `SEND_SMS` only — delivery status comes from the sent/delivered `PendingIntent` broadcasts; `READ_SMS`
    is NOT requested (Play liability; an SMS *read* rail would be a separate future rail with its own spec)
- Least-Privilege Gate: Permissions are never requested until the user explicitly toggles the corresponding rail ON.
- Runtime Revocation Resilience: If the OS or user revokes a permission, the affected rail degrades immediately to
  `Degraded(reason = "permission_revoked")`. The service notifies the hearth via rail toggle/sync if connected, ceases
  querying the platform `ContentProvider`, and logs a structured warning. The app MUST NEVER crash or throw unhandled
  `SecurityException`s on missing permissions; other active rails continue uninterrupted.

### A5 — Read Rails and ContentProvider Push Sync
Read rails query Android framework providers and push structured snapshots and deltas to `POST /v1/devices/<id>/sync`:
- Rail Vocabulary & Capping:
  - `calendar`: Queries `CalendarContract.Events` / `Instances`, time-bounded to `[-30 days, +90 days]`.
  - `contacts`: Queries `ContactsContract.Data`, extracting phone numbers and email addresses.
  - `notifications`: `NotificationListenerService` captures incoming notifications, filtering out Lararium's own
    notifications and ongoing service indicators.
  - `calllog`: Queries `CallLog.Calls`, capturing recent call metadata.
  - `health`: Optional Health Connect provider reading daily steps, heart rate summary, and sleep records.
- In-Process Servicing & Zero Phone Payloads: The hearth serves read queries in-process from its 0600 store. The phone
  never serves inbound read calls. The phone persists only sync cursors (last synced `seq`, provider change timestamps),
  never storing cached copies of rail payloads on device disk.
- Monotonic Sequencing: Every sync POST sends a strictly increasing `seq` per rail. If the daemon returns 409 `stale_seq`,
  the client resets its cursor and pushes a full snapshot with a fresh sequence number.

### A6 — Action Rails and Double-Gated Execution
Action tools (`dialer_call` on `dialer`, `sms_send` on `sms`) mutate the physical world and require dual approvals:
- Gate 1 (Hearth Custody Card): Operator approves the tool call on the hearth side with full JSON argument visibility.
- Gate 2 (On-Device Confirmation Sheet): When `action_dispatch` (`{"action_id", "tool", "args"}`) arrives over the SSE
  stream, the client presents an unavoidable modal confirmation sheet displaying the raw parameters:
  - Dialer: Destination phone number and call trigger confirmation.
  - SMS: Recipient phone number and verbatim text content.
- Execution Timeout & Local Denial: The sheet operates with a 60-second countdown timer. If the user denies the action
  or the timer expires without confirmation, the client rejects execution and posts:
  `POST /v1/devices/<id>/actions/<action_id>/receipt` with `{"ok": false, "error": "denied_local"}` (or `"timeout"`).
- Platform Dispatch: Upon explicit user confirmation:
  - SMS: Sent strictly through `SmsManager.sendTextMessage()` with delivered/sent `PendingIntent` tracking.
  - Dialer: Placed via `Intent.ACTION_CALL` (falling back to `ACTION_DIAL` if `CALL_PHONE` permission is absent).
- Terminal Receipt Guarantee: Every dispatched action MUST resolve with an explicit receipt (`ok: true` with result,
  or `ok: false` with error description). Action frames are never silently dropped or ignored.

### A7 — Battery, Doze, and Synchronization Strategy
- Delta Synchronization: Changes are pushed as incremental deltas (`[{"op": "add"|"update"|"delete", "id", "data"}]`)
  where supported by `ContentObserver`, avoiding costly complete dataset re-queries.
- Periodic Reconciler: A `WorkManager` periodic worker (`DeviceRailSyncWorker`) runs every 15 minutes as a background
  backstop to reconcile stale data during Doze mode without maintaining CPU wakelocks.
- Maximum Staleness Specifications:
  - `notifications`: Immediate push on event receipt (nominal lag ≤ 1s).
  - `calllog`: Immediate push on call state transition to IDLE (nominal lag ≤ 5s).
  - `calendar`: Content observer trigger; max staleness 15 minutes.
  - `contacts`: Content observer trigger; max staleness 1 hour.
  - `health`: Scheduled pass; max staleness 1 hour.
- Wakelock Restrictions: No background wakelocks beyond the foreground service execution lifecycle.

### A8 — Pure-JVM State Machine Architecture
Following the repo's `WatchLogic` and `ChatLane` patterns, device rail lifecycle logic resides in a pure-JVM class
(`DeviceRailLogic`) decoupled completely from Android framework APIs:
- States: `Unpaired`, `Offering`, `Active`, `Syncing`, `Degraded`, `Disabled`.
- Pure Transitions:
  - `OfferRails(selectedRails)`: Evaluates permissions and flags; transitions `Unpaired` → `Offering`.
  - `RegistrationSuccess(deviceId, cardId)`: Transitions `Offering` → `Active`.
  - `SyncTriggered(rail)`: Transitions `Active` → `Syncing`.
  - `SyncCompleted(rail, seq)`: Transitions `Syncing` → `Active`.
  - `PermissionRevoked(rail, reason)`: Transitions `Active` → `Degraded`.
  - `PermissionRestored(rail)`: Transitions `Degraded` → `Active`.
  - `RemoteRailDisabled(rail)` / `UserRailDisabled(rail)`: Transitions `Active` → `Disabled`.
  - `AuthTerminated(401|403)`: Transitions any state → `Unpaired`.
  - `DeviceDeleted`: Purges state, returns to `Unpaired`.
- Android components (`DeviceStreamService`, `DeviceRailSyncWorker`, Compose UI) act as thin wrappers around this engine.

### A9 — Distribution and Play Store Policy
- Build Flavors (superseded by T4 flavor unification: both flavors now compile identical rail code; see
  `docs/PLAY-LANE.md` "Assistant-role path"):
  - `play`: Complies with Google Play Developer Program Policies. SMS and Call Log capabilities declare appropriate
    use-case groups in the Play Console (documented in `docs/PLAY-LANE.md`).
  - `sideload`: Built from identical code without permission restrictions, distributed directly via GitHub Releases.
- Sideload First-Class: Play Store availability is strictly an additional convenience; the sideloaded APK remains
  fully functional with zero Google Play Services dependencies.
- Zero Upstream Residue: All UI strings, notifications, and code adhere strictly to `scripts/branding-hygiene.sh`.

### A10 — Testing Strategy and Verification Pipeline
- Pure-JVM Unit Tests (`app/src/test/`):
  - State machine transition coverage across all state-event permutations.
  - Correct derivation of `read_only` flags across closed first-party rail vocabulary.
  - SSE action frame JSON parsing and malformed payload resilience.
  - Monotonic sequence math, cursor reconciliation, and delta serialization.
- Robolectric Tests:
  - `DeviceStreamService` startup, ongoing notification generation, and binding lifecycle.
  - Runtime permission revocation simulation and degraded state reporting.
  - ContentProvider cursor extraction to wire schema mapping.
- End-to-End Tests:
  - Fake-hearthd test server verification validating registration handshake, SSE streaming, receipt resolution,
    and 400 `rail_mismatch` rejection.

## Exit Criteria (V-D Suite)

- **V-D1 (Pairing & Offer):** Ingesting a `lararium://pair` deep link populates the Devices screen; selecting rails
  computes `read_only: true` for read rails and `read_only: false` for action rails; `POST /v1/devices/register` returns
  202 Accepted with a valid `card_id`.
- **V-D2 (Misflag Prevention):** Pure-logic unit test asserts client-side registration payload builder fails closed
  if any read rail is configured with `read_only = false` or an action rail with `read_only = true`.
- **V-D3 (Foreground Service Liveness):** On registration approval, `DeviceStreamService` starts with an ongoing
  notification (`VISIBILITY_PRIVATE`, content-free public version) and establishes `GET /v1/devices/<id>/stream`.
- **V-D4 (SSE Heartbeat & Reconnection):** Stream survives `: ping` heartbeats; killing network triggers capped
  backoff (2s → 60s); restoring connectivity via `NetworkCallback` reconnects within 2 seconds.
- **V-D5 (Epoch Isolation):** An `action_dispatch` received with a stale connection epoch is dropped immediately
  with no confirmation prompt or receipt emitted.
- **V-D6 (Auth Failure Stop):** Receiving 401 or 403 on stream or sync halts backoff retries, emits "Re-pair required"
  notification, and sets state to `Unpaired`.
- **V-D7 (Push Sync & Monotonicity):** Read rails push snapshot and incremental deltas to `POST /v1/devices/<id>/sync`
  with strictly increasing `seq`; 409 `stale_seq` from daemon triggers automatic full snapshot recovery.
- **V-D8 (Double-Gated Action Prompt):** Receiving `action_dispatch` pops a modal confirmation sheet showing exact
  parameters (phone number, SMS body); tapping Deny posts receipt with `{"ok": false, "error": "denied_local"}`.
- **V-D9 (Action Timeout):** Ignoring the confirmation sheet for 60 seconds automatically dismisses the dialog and
  posts receipt with `{"ok": false, "error": "timeout"}`.
- **V-D10 (Action Execution & Receipt):** Confirming an SMS action dispatches via `SmsManager` and posts `ok: true`
  receipt; an operating system dispatch error posts `{"ok": false, "error": "<reason>"}`. No action is silently lost.
- **V-D11 (Permission Revocation Safety):** Revoking `READ_CALENDAR` via system settings causes `calendar` rail to
  transition to `Degraded` without crashing the app or terminating other active rails.
- **V-D12 (Secret Scrubbing & Pure Logic CI):** CI unit test suite runs without Android emulator, achieving 100%
  coverage of `DeviceRailLogic`; pairing tokens never appear in logcat, notification text, or unencrypted storage.

## Out of Scope

- Wear OS companion application or watch device rails.
- Multi-phone fleet topologies (strictly 1:1 phone-to-hearth pairing for v1).
- Notification action execution on phone (e.g. dismissing notifications or triggering quick replies remotely).
- FCM (Firebase Cloud Messaging) or third-party hosted notification relays.
- Server-side modifications to hearthd (the daemon contract is frozen and shipped).
- Per-rail encryption-at-rest beyond Android Keystore / EncryptedSharedPreferences (deferred to v3).
- Remote phone dialing initiation without physical user confirmation.

## Changelog

- v1 (2026-11-02): Initial client freeze-proposed specification conforming to DEVICE-RAILS-SPEC v2 (daemon shipped).
