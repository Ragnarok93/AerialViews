# AerialViews APK upgradeability

## Invariants

- Keep `applicationId` as `com.neilturner.aerialviews` for upgrades over existing production-flavor installs.
- Keep the same signing certificate for each installable distribution channel. The GitHub flavor uses `signing/legacy.properties`; retain the corresponding keystore and credentials used for prior GitHub APKs. Play, Amazon, F-Droid and debug variants have distinct signing or package identity rules and are not interchangeable.
- Increase `versionCode` for every distributed release. This branch starts at version code 152 / version name `1.8.7`, above the prior version code 151.
- Prefer installing an update over the existing app; do not uninstall first unless data loss is intended.

## CI versus installable builds

CI runs unit tests, formatting checks and `assembleGithubDebug` to verify compilation. That debug variant has the `.debug` application ID suffix and is not an upgrade package for the normal production app. CI does not publish it as a release artifact. Distributed `github` APKs must be built with the persistent legacy keystore used for previous GitHub releases.

## Compatibility limit

Android only permits in-place updates when both the package ID and signing identity match, and the incoming version is not a downgrade. A build signed with another key cannot update an existing installation, regardless of its version code. Keep signing keys private and backed up; do not replace or regenerate them.
