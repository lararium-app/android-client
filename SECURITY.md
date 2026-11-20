# Security Policy

## Supported Versions

| Version              | Supported          |
|----------------------|--------------------|
| latest alpha release | :white_check_mark: |
| < latest             | :x:                |

Lararium Android is pre-1.0 software: security fixes land on `main` and ship in
the latest alpha release only. Before reporting, please confirm you are running
the latest alpha release and reproduce against it.

## Reporting a Vulnerability

**Do not open a public issue for security problems.**

Report privately by email to **security@lararium.io** (a role mailbox —
never a personal account). Include:

- a description of the issue and the affected component (e.g. APK/AAB builds, pairing tokens stored in the Android Keystore-backed SecureKeyManager, or the device-rails service)
- steps to reproduce, with versions (device model, Android version, app version, and flavor)
- any proof-of-concept material, scrubbed of personal data

What to expect:

- acknowledgment within **72 hours**
- a first severity/impact assessment within **7 days**
- a public advisory (GitHub Security Advisory) once a fix is released,
  with credit to the reporter unless you prefer anonymity

We follow coordinated disclosure: fixes ship before details become
public, and we will not take legal action against good-faith research.

## What we consider in scope

Anything that affects the security of the Android client (APK/AAB builds) or breaks its security guarantees:

- Extraction or exposure of pairing tokens stored in the Android Keystore-backed SecureKeyManager
- Vulnerabilities, unauthorized access, or command manipulation in the device-rails service
- Pairing, authentication, or lock screen/auth gate bypass
- Silent data exfiltration or unauthorized access to sensitive local app storage

## Out of scope

- Attacks requiring physical device compromise, rooted environments, or ADB debugging where the Android OS security model is already broken
- Resource exhaustion from your own workloads or local denial of service
- Vulnerabilities in upstream Android OS or third-party dependencies without demonstrable impact on the app
