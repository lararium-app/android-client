# Architecture Overview

Privacy-focused Android assistant (`app.lararium`). It is the hands-free front
end for your **paired hearth**: a home server that runs the model, memory, and
tools. The phone captures voice or text, shows the answer, and surfaces the
hearth's approval cards — no cloud accounts, no API keys, no model selection.

## Project Structure

```
app/src/main/
├── java/app/lararium/
│   ├── AssistantApplication.kt
│   ├── MainActivity.kt                    # single-Activity Compose host
│   ├── service/
│   │   ├── AssistantService.kt            # central orchestrator + connection machine
│   │   ├── AssistantAccessibilityService.kt
│   │   ├── AssistantVoiceInteractionService.kt
│   │   ├── AssistantVoiceInteractionSessionService.kt
│   │   └── AssistantTileService.kt
│   ├── hearth/
│   │   ├── LarariumClient.kt              # HTTP + SSE transport, auth, pairing endpoints
│   │   ├── HearthChatClient.kt            # chat lane over streamTurn
│   │   ├── ChatLane.kt                    # pure ready/not-paired decision
│   │   ├── PairingStore.kt                # QR/pairing state
│   │   └── Models.kt                      # wire models
│   ├── approvals/                         # custody-approval cards + resolver
│   ├── watch/                             # WorkManager badge/watch worker
│   ├── llm/ChatSession.kt                 # conversation context (used by hearth lane)
│   ├── audio/
│   │   ├── VoskTranscriber.kt             # on-device STT
│   │   ├── SpeechRecognizerManager.kt     # Android system STT
│   │   ├── VoiceActivityDetector.kt
│   │   └── AudioCaptureManager.kt
│   ├── tts/TTSManager.kt
│   ├── badge/AppBadge.kt
│   ├── storage/ChatHistoryManager.kt
│   ├── security/
│   │   ├── SecureKeyManager.kt            # hearth token + endpoint in Android Keystore
│   │   ├── EndpointPolicy.kt              # loopback/private-host rules
│   │   └── LinkPolicy.kt
│   └── ui/
│       ├── CompactAssistantUI.kt          # assistant overlay
│       ├── SettingsScreen.kt              # Connection (pair), voice, TTS, orientation
│       ├── SettingsManager.kt
│       ├── VoskLanguageManagerScreen.kt
│       └── StateIndicator.kt
```

## Activation Flow

```
Volume Up+Down / Triple Power / Accessibility button / Default assistant / QS tile
    → AssistantService (foreground)
    → CompactAssistantUI (Compose overlay)
```

## Query Processing Pipeline

```
User input (text or voice→text, on-device STT)
    → sanitizeQuery
    → ChatLane decision
        ├─ NotPaired → friendly setup hint, zero network attempts
        └─ Ready     → POST /v1/sessions/{id}/messages (SSE)
                        → deltas stream into the overlay + ChatSession
                        → approval events raise custody cards
    → TTS (optional)
```

The hearth chooses the model, prompt, and tools; the client only sends the
user's text (and an approval reply when answering a card).

## Connection Lifecycle

`AssistantService` owns a small state machine (idle → connecting → streaming →
complete/error) with an epoch counter: deltas arriving on a superseded epoch
are dropped, and the send gate retries once on a dead connection before
reporting an error. Pairing stores the endpoint and token via
`SecureKeyManager`; the network policy (see below) restricts where it may talk.

## Speech

| Path | Class | Notes |
|------|-------|-------|
| Vosk | `VoskTranscriber` | On-device, downloadable language models |
| System | `SpeechRecognizerManager` | Android built-in recognizer |
| TTS | `TTSManager` | Android `TextToSpeech` |

Both STT paths are on-device by design; a legacy stored preference falls back
to Vosk rather than failing.

## Security

- Hearth token + endpoint encrypted in the Android Keystore (`SecureKeyManager`).
- `network_security_config.xml` denies cleartext globally; only literal
  loopback hosts may use cleartext (the paired hearthd on-device/LAN case).
- `EndpointPolicy` enforces loopback/private addresses at the client layer,
  re-checking resolved addresses against DNS-rebinding.
- TLS for everything else; no analytics, no tracking, no third-party keys.

## Context Management

`ChatSession` keeps the running conversation the hearth sees (bounded, trimmed
from the oldest messages). The server is authoritative for memory across
sessions; the client copy drives the visible transcript.

## Build

- **minSdk** 26, **targetSdk** 36, **arm64-v8a** (Vosk native libs).
- No NDK build required — everything compiles with the Android SDK alone.
- Release: ProGuard enabled; CI builds a debug APK on every push.
- `scripts/branding-hygiene.sh` fails the build on inherited third-party
  branding or provider literals in `app/src/main`.
