## Description

What does this change, and why? Link the issue it closes with `Closes #N`.

## Checklist

Keep this list in the description — it structures the review.

- [ ] Conventional Commit title
- [ ] `./gradlew compileGithubReleaseKotlin compilePlayReleaseKotlin` clean
- [ ] `./gradlew testGithubDebugUnitTest testPlayDebugUnitTest` green
- [ ] Wire behavior matches the daemon specs
- [ ] Tests added
- [ ] User-visible changes documented

## AI assistance

Was substantial AI assistance used in writing this code? (yes/no — one line)
