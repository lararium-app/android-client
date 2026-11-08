# Lararium — Android client

Android front-end for [Lararium](https://github.com/lararium-app/lararium): an
open-source, self-hosted personal AI agent — the shrine for your digital
hearth. Your phone is the sensor, the voice, and the approval gate; your own
`hearthd` daemon is the brain. No third-party cloud, no telemetry, no accounts.

Based on [gos-ai](https://github.com/mx37/gos-ai) by Maxim (MIT): full credit
for the original app architecture, assistant UX, and on-device voice pipeline.
Lararium points that foundation at the Lararium surface protocol — streaming
chat sessions, custody approval cards, and device rails.

⚠️ DISCLAIMER: THIS IS A COMMUNITY-OPEN-SOURCE PROJECT. IT IS NOT AFFILIATED WITH, ENDORSED BY, OR OFFICIALLY SUPPORTED BY THE GRAPHENEOS PROJECT OR ITS TEAM.

Works on any Android 8.0+ device (minSdk 26); tested on Android 16 (Pixel 9 Pro,
GrapheneOS).

## Why this exists

An always-available assistant without surrendering your data to a cloud
brain-in-a-box. Pair the app with your own `hearthd` and the phone stays a
sensor/actuator UI: chat streams token-by-token, and gated actions raise
custody approval cards you answer from your pocket. All agent logic, memory,
and policy live on hardware you own.

## Features

- **Many ways in**: Volume Up + Down, triple power press, accessibility button,
  default assistant, Quick Settings tile
- **Streaming chat** over Server-Sent Events with markdown rendering
- **Custody approvals on your phone** — review and resolve pending action cards
  from your hearth (approve once, always, or deny)
- **Voice**: Vosk (offline), Android system STT; optional TTS
- **Images & PDFs**: attach images or extract text from PDFs to include with
  your query

## AI backend

Assistant answers come directly from your paired **Lararium hearth** (`hearthd`)
server over local network or secure transport. All model inference, tool
execution, and conversation memory reside on the hearth daemon; the client
carries no third-party cloud backends, embedded model weights, or external API keys.

## Quick start

1. **Install** — two lanes, pick one per device:
   - **Google Play closed beta**: open
     [play.google.com/apps/testing/app.lararium](https://play.google.com/apps/testing/app.lararium)
     on the phone, tap **Join tests**, then **Install**. No email list —
     anyone with the link can opt in, and leaving is one tap.
   - **Sideload**: install the release APK (`lararium-<tag>.apk`) from
     [Releases](../../releases) (each release lists its APK SHA-256) or
     build locally.

   Both lanes publish builds signed with the same stable key and compile
   the same rail code; the dialer, SMS, and call-log rails are available
   everywhere and degrade at runtime when their permission is not granted
   (see the Assistant-role path in `docs/PLAY-LANE.md`).
2. **Pair** with your hearth: run `hearthd pair` on the server and open the
   emitted `lararium://pair?…` link on the phone, or set the server URL and
   pairing code manually in Settings.
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
./gradlew assembleGithubDebug        # app/build/outputs/apk/github/debug/app-github-debug.apk
```

CI builds debug APKs on every push to `main` (Actions → *Build APK*).

## Privacy

- Bearer tokens cross TLS; cleartext is permitted only for loopback / RFC1918
  / CGNAT-tailnet hosts, enforced in app code and re-checked after DNS
  resolution (defeats rebinding)
- Auth tokens and hearth endpoints stored in EncryptedSharedPreferences backed
  by the Android Keystore (AES-256-GCM)
- PII redaction before queries are sent to the hearth (device IDs, phones, emails)
- No Firebase, no analytics, no ad identifiers, no FCM push
- Minimal permissions: microphone, internet, foreground service

## Known issues

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
