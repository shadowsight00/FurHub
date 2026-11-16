# Secure release-signing process

The manual **Build signed release APK** workflow uses Java 17 and the Android SDK, runs release tests/lint, signs the release variant and verifies the APK signature before artifact upload.

It requires four owner-controlled repository Actions secrets:

| Secret | Value |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Base64 bytes of the owner's release keystore |
| ANDROID_KEYSTORE_PASSWORD | Keystore password |
| ANDROID_KEY_ALIAS | Signing-key alias |
| ANDROID_KEY_PASSWORD | Signing-key password |

The keystore is reconstructed only in the runner's temporary directory and deleted in an always-run cleanup step. Passwords flow only through masked environment variables. The workflow fails before building if signing secrets are missing. No release key has been generated, uploaded, or committed by this project. Preserve the owner's release key securely for future updates.

Do not configure production signing or describe a release as ready until the owner has approved key custody and the debug app has passed physical-device/account testing. Debug and release signing identities differ; a phone may require uninstalling a differently signed debug build before installing a release, which removes its local app data.
