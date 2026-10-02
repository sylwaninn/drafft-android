<!--
Title: type(scope): lowercase description, no final period. It becomes the squash commit on staging.
  types: feat, fix, docs, style, refactor, test, chore. `type!:` for a breaking change.
Base: staging. Never main (main only moves through the release workflow).
No AI attribution line in the description or in any commit (CI refuses it, scripts/ci/pr_check.sh).
Delete these comments and any section that doesn't apply.
-->

## Summary

<!-- What changes for the person using drafft, in two or three sentences. Then why. -->

## Changes

<!-- The main changes, one line each, with the types or files that carry them. -->

-

## Testing

<!-- What you ran and what you saw. Say what you did not run. -->

- [ ] `./gradlew -p tools/jvmcheck compileKotlin test`
- [ ] `python3 scripts/check-strings.py`
- [ ] `python3 scripts/ci/design_lint.py` and `python3 scripts/ci/i18n_lint.py`
- [ ] `./gradlew assembleStagingDebug`
- [ ] Run on staging (`installStagingDebug`), steps:
  1.

## Screenshots

<!-- UI changes: before / after, light and dark, the longest language (FR, DE or NL) when a label moved. -->

## Notes

- **Telemetry:** <!-- events, screens, errors and alerts added (docs/telemetry.md), or "none, because ..." -->
- **Wording:** <!-- strings added or changed in the 7 languages after WORDING.md section 10, or "no user-facing text" -->
- **Privacy:** <!-- new personal data, third party or retention change (legal pages in drafft-web), or "none" -->
- **Companion pull requests:** <!-- the other drafft repositories, or "none" -->
- **Both apps:** <!-- drafft-ios already behaves this way, its companion pull request, or why Android only -->
- **Breaking change:** <!-- what a client or the backend must do, or "none" -->
