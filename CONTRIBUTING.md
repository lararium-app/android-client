# Contributing to Lararium Android

Thanks for pulling up a chair. This document is the short version of how
contributions work here.

## Ground rules

1. **Bugs and ideas → issues.** Open an issue first. Security issues go
   privately to security@lararium.io, never to issues.
2. **Wire contracts.** Wire contracts come from the daemon repo specs.
3. **One concern per PR.** Conventional Commit titles
   (`feat: …`, `fix: …`, `docs: …`). The PR title becomes the merge
   commit subject.
4. **CI must be green:**

   ```bash
   ./gradlew compileGithubReleaseKotlin compilePlayReleaseKotlin \
     testGithubDebugUnitTest testPlayDebugUnitTest
   ```

## Development setup

- JDK 17
- Android SDK located via `ANDROID_HOME` or Android Studio's generated
  `local.properties` — which is machine-specific and must never be committed
- Product flavors `github` and `play`
- No machine-specific data anywhere in the tree or in commit messages — no
  absolute home paths, local usernames, or hostnames. Keep editor, OS, and
  tool scratch ignores in your global gitignore
  (`git config --global core.excludesFile`), not in `.gitignore`.

## Pull request checklist

Please keep the checklist in your PR description (deleting it slows triage):

- [ ] Conventional Commit title
- [ ] `./gradlew compileGithubReleaseKotlin compilePlayReleaseKotlin testGithubDebugUnitTest testPlayDebugUnitTest` green
- [ ] Behavior matches wire contracts from daemon repo specs
- [ ] New behavior has a test; fixes have a regression test
- [ ] Docs updated where user-visible behavior changed

## AI-assisted contributions

You may use AI tools while contributing. What you submit, you own — in the
responsibility sense:

- You must be able to explain every line you propose, and defend it in
  review.
- Do not paste generated code you have not read.
- Disclose substantial AI assistance in the PR description (one line is
  fine). PR descriptions themselves should be written by a human.

Fabricated content — invented APIs, invented test results, invented
hardware behavior — is the fastest path to a closed PR. We verify.

## License

By contributing you confirm the contribution is licensed under the
project's MIT license (inbound = outbound).
