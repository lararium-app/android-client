# Exiting alpha — the path to beta

Alpha said "the pieces work on a developer's phone". Beta must say "a
stranger installs this APK, pairs it with their hearth, and it works
without ever opening Settings." This file is the contract: when every
box is checked with a linked artifact (test, dogfood transcript, CI
run, or release note), we tag beta.

## Versioning plan

- **The client exits alpha with the daemon.** When hearthd ships
  v1.0.0, the client ships v1.0.0 and the `-alpha` suffix drops. Until
  then every client release stays on the 0.9.x-alpha line — the gates
  below close *before* the 1.0 tag, not after.
- The stable signing cert is unchanged, so 0.9.x installs upgrade in
  place — pairing, tokens, and history survive the version bump.

## A. First run is honest (blocker)

- [ ] **Pairing makes chat work with no other step.** Redeeming a
      `pair1_` code (QR or deep link) is all a chat needs: the paired
      hearth is the chat lane by definition, not a provider the user
      must also select. Today pairing stores the endpoint but a legacy
      provider default can still capture the send, so a paired
      stranger's first chat goes nowhere.
      Guard: unit test asserting a freshly paired store produces a
      ready chat lane, and an unpaired store produces the friendly
      not-paired error rather than a network attempt.
- [ ] **A fresh install is pair-first.** No shipped path asks a new
      user for an API key or a server URL before they have seen what
      the app does with a hearth. Unpaired chat says
      "Not paired with your hearth yet" and routes the user to Pair.
- [ ] **First run names what a hearth is** and links the install guide;
      no dead-end screens that require an API key to function.

## B. One product, not two (blocker — purge decided by the PO)

- [ ] **The hearth is the only model lane.** Remove the fork-era
      providers (hosted aggregator, Groq, generic self-hosted, local
      llama.cpp settings), their API-key dialogs, and the provider
      toggle itself — with one lane there is nothing to switch between.
      Legacy values already stored in preferences must be ignored
      cleanly, never crash. On-device speech recognition and system
      text-to-speech stay — they are input and output, not a second
      brain.
- [ ] **Zero upstream residue in user-visible strings.** No references
      to another project's key files or names anywhere the user can
      see. Gate: `scripts/branding-hygiene.sh` literal list extended in
      CI so residue cannot reappear.
- [ ] Internal identifiers for the removed lanes are deleted, not
      renamed-in-place; the neutrality scan passes at the tag.

## C. Trust posture holds (already at bar — re-verify at tag)

- [x] Keystore-encrypted token storage; cleartext confined to
      loopback/RFC1918/tailnet by a unit-tested policy with DNS-rebinding
      re-verification.
- [x] No FCM, no analytics, no ad-ID, `usesCleartextTraffic=false`.
- [x] Lock-screen privacy: approval cards `VISIBILITY_SECRET`, reply
      notifications `VISIBILITY_PRIVATE` with a redacted public version,
      action buttons unlock-gated.
- [ ] At the tag: `EndpointPolicyTest` green, gitleaks and CodeQL clean
      on the Security tab, dependency review job green.

## D. Works on a real phone against a real hearth (blocker)

- [ ] Dogfood matrix run against a live v1.0.0-beta hearth, transcript
      in `docs/DOGFOOD.md`: pair by QR and by link; cold start after
      process death rehydrates the chat; network killed mid-turn →
      reconnect with no duplicated bubble; pending approval answered
      from the notification; badge clears; in-place upgrade from
      v0.9.5-alpha without re-pairing.
- [ ] Background freshness decided: the 15-minute watch worker plus
      refresh-on-open is accepted for beta (push channel is a named
      post-beta milestone, not a silent gap).

## E. Quality gates

- [ ] Unit suite green in CI (tests run before assemble; a red test
      blocks the APK) with zero flakes across the final week.
- [ ] Every removal PR carries a dead-reference sweep in review; every
      feature PR keeps the pure-logic-test pattern (logic JVM-testable
      without an emulator).
- [ ] Release-note claims are grep-verified against the shipped code
      before publishing.

## F. Ship mechanics (proven)

- [x] Tag flow: version bump via PR → `git tag` → CI runs tests, builds
      with the stable keystore, publishes the GitHub Release with the
      signed APK and SHA-256 digests.
- [ ] Play Store listing is explicitly **out of scope** for beta
      (separate decision: developer account, AAB pipeline, keystore
      custody). Beta distribution is GitHub Releases with in-place
      updates.

## Non-goals for beta (named so they don't creep in)

- Reverse device channel (phone as a tool belt for the hearth)
- Push notifications beyond polling (UnifiedPush)
- Connectors management UI and memory editor on the phone
- Tablets, foldables, ChromeOS, and i18n beyond the current strings
- Any cloud relay — the phone talks only to the user's own hearth
