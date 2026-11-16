# UnifiedArt

Native Kotlin / Jetpack Compose Android art browser merging e621 and Inkbunny into one gallery.

## Current implementation (Phase 2 candidate)

- Independent e621 tag and Inkbunny keyword fields, combined timestamp-sorted results.
- Concurrent sources publish independently, with individual pagination, error state and retry.
- Infinite scrolling, explicit load-more fallback, refresh, cancellation, bounded retry and per-source request spacing.
- Source-prefixed stable IDs; artist, tags, timestamp, original media, dimensions, page count, favorite and pool metadata.
- Native full-screen viewer; no website redirects or browser intents.
- Cross-source swipe navigation, pinch/double-tap zoom, panning, GIF and Media3 video viewing.
- Inkbunny original-file detail resolution and ordered multi-page navigation.
- Artist/tag/pool actions run searches inside the app.
- Adjustable grid columns, dark UI, loading/empty/error states, Coil thumbnail cache, recent query recall.
- GitHub Actions installs Java 17, Android SDK and Gradle 8.9; runs JVM tests, lint and debug assembly; uploads APK and check reports.

Phase 1 passed GitHub Actions compilation, JVM tests, lint and APK assembly: https://github.com/shadowsight00/UnifiedArt/actions/runs/43953549116

Phase 2 adds Keystore-encrypted e621 API-key and Inkbunny SID connections, verification, disconnect/account switching, merged account favorites, optimistic e621 favorite editing with rollback, source blacklist synchronization and native account settings. Account passwords are not persisted. Local build checks pass; authenticated account flows still require real-device/account validation. Inkbunny favorite editing and full preference export are not documented by its public API and are explicitly identified as unavailable in the UI.

## Phone-only installation after a successful build

Open this repository's **Actions** tab, open the successful **Build Android APK** run, then download **UnifiedArt-debug-apk** from Artifacts. Extract the ZIP on Android and open `app-debug.apk`. Android may ask to allow installation from the app used to open it. There is no computer, Android Studio, terminal, or individual-file upload step.

## Access behavior

The app imposes no adult-rating exclusions. e621 uses public access or authenticated requests when connected. Inkbunny uses its documented guest-session login and rating-selection endpoint, enabling the supported guest ratings; guest-blocked/private submissions remain inaccessible. Registered Inkbunny sessions preserve the account settings returned at login. Session expiry requires reconnecting; the app never silently substitutes a guest session for an expired registered connection. Source-side `hidden` flags are respected.

A source can be unreachable independently. A successful compile does not prove that either API is reachable on a particular network.

## Next milestones

See [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) for the complete planned feature scope and [docs/API-NOTES.md](docs/API-NOTES.md) for reviewed API contracts and limitations. Animated gallery thumbnails, a followed-artists feed, background downloads and Room collections are not implemented yet. No placeholder tabs claim these features exist.

## Development

SDK 35, minimum Android 8 (API 26), AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01. CI uses a pinned Gradle installation rather than an absent wrapper. All dependencies come from Google Maven / Maven Central. No credentials, passwords, signing keys or backend are required for public browsing. Credentials are entered only inside the installed app and are never supplied to cloud builds.
