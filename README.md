# Lararium — Android client

Android front-end for [Lararium](https://github.com/lararium-app/lararium): an
open-source, self-hosted personal AI agent — the shrine for your digital
hearth. Your phone is the sensor, the voice, and the approval gate; your own
`hearthd` daemon is the brain. No third-party cloud, no telemetry, no accounts.

Based on [gos-ai](https://github.com/mx37/gos-ai) by Maxim (MIT): full credit
for the original app architecture, assistant UX, on-device voice pipeline, and
local llama.cpp integration. Lararium points that foundation at the Lararium
surface protocol — streaming sessions, tool-call cards, Knock-First approvals,
and doors & policy.

⚠️ DISCLAIMER: THIS IS A COMMUNITY-OPEN-SOURCE PROJECT. IT IS NOT AFFILIATED WITH, ENDORSED BY, OR OFFICIALLY SUPPORTED BY THE GRAPHENEOS PROJECT OR ITS TEAM.

Works on any Android 8.0+ device (minSdk 26); tested on Android 16 (Pixel 9 Pro,
GrapheneOS).

## Why this exists

An always-available assistant without surrendering your data to a cloud
brain-in-a-box. Pair the app with your own `hearthd` and the phone stays a
sensor/actuator UI: chat streams token-by-token, tool and cell operations
render as cards, and gated actions knock — an approval card you answer from
your pocket. All agent logic, memory, and policy live on hardware you own.

## Features

- **Many ways in**: Volume Up + Down, triple power press, accessibility button,
  default assistant, Quick Settings tile
- **Streaming chat** over Server-Sent Events with markdown rendering and
  tool-call / cell-operation cards
- **Knock-First approvals on your phone** — spend, send, delete, or any gated
  door asks before it acts (approve / deny, plus once-always-deny door cards)
- **Doors & policy panel** — view and edit the live rule set
  (auto / ask / deny per pattern)
- **Local AI fallback** — on-device llama.cpp GGUF when the hearth is out of
  reach; native libs built into CI APKs for arm64
- **Voice**: Vosk (offline), Android system STT, or cloud Whisper; optional TTS
- **Images & PDFs** with vision models, web search & weather agent tools

## AI backends

| Provider | Description |
|----------|-------------|
| **Lararium hearth** (default) | Your own `hearthd` surface — pair link or base URL + API key |
| **On-device (offline)** | Local GGUF via bundled llama.cpp — no internet needed |
| **OpenRouter (advanced)** | Hosted cloud fallback — useful for testing vision models |

## Quick start

1. **Install** the APK from [Releases](../../releases) (each release lists its
   APK SHA-256) or build locally.
2. **Pair** with your hearth: run `hearthd pair` on the server and open the
   emitted `lararium://pair?…` link on the phone, or set the server URL and API
   key manually in Settings.
3. **Set as default assistant**:
   ```
   Settings → Apps → Default apps → Digital assistant app → Lararium
   ```
4. **Enable accessibility** (for the volume-button shortcut):
   ```
   Settings → Accessibility → Lararium → Enable
   ```

## Build from source

```bash
git clone https://github.com/lararium-app/android-client.git
cd android-client
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

CI builds debug APKs on every push to `main` (Actions → *Build APK*).

## Privacy

- Bearer tokens cross TLS; cleartext is permitted only for loopback / RFC1918
  / CGNAT-tailnet hosts, enforced in app code and re-checked after DNS
  resolution (defeats rebinding)
- API keys stored in EncryptedSharedPreferences backed by the Android Keystore
  (AES-256-GCM)
- PII redaction before any optional cloud request (device IDs, phones, emails)
- No Firebase, no analytics, no ad identifiers, no FCM push
- Minimal permissions: microphone, internet, foreground service

## Known issues

- Local AI requires building or obtaining native `arm64-v8a` llama libraries
  (see LOCAL_AI_SETUP)
- Local models have no web search, weather, or vision
- TTS quality depends on the engine installed on the device

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — code structure and data flow
- [Roadmap](docs/ROADMAP.md) — where the product is heading and why
- [Exit-alpha criteria](docs/EXIT-ALPHA.md) — what "alpha" means here

## Contributing

Bug reports, feature suggestions, and pull requests welcome.

## License

MIT — see [LICENSE](LICENSE). Original gos-ai codebase copyright (c) Maxim;
Lararium contributions MIT the Lararium authors.

## Acknowledgments

- **[mx37 / gos-ai](https://github.com/mx37/gos-ai)** — the upstream project
  this client is based on; the maintainer has blessed this direction
- **GrapheneOS** — privacy-focused Android
- **Vosk** — on-device speech recognition
- **llama.cpp** — on-device LLM inference
- **Brave / Exa** — optional search APIs
