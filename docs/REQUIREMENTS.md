# UnifiedArt — project requirements and acceptance plan

Repository: https://github.com/shadowsight00/UnifiedArt

## Product contract

A standalone installable native Android APK, Kotlin and Compose Material 3, dark by default. e621 and Inkbunny are independent adapters feeding one art library, never separate galleries or a website-switching UI. Search, gallery, favorites, following, saved searches, bookmarks, collections, media and downloads are unified. Preserve attribution and source-specific behavior internally. Stable keys combine source and submission ID.

All browsing stays in the native app: opening artwork, artist, tags or pool must not launch the source website. Source attribution and original source URLs are metadata, not forced external navigation. No custom backend unless a feature specifically requires it.

No app-imposed rating restrictions, SFW-only mode or automatic exclusions solely for adult/sexual themes. Respect user blacklist decisions, account settings, applicable law, and all API/site permissions. Never retrieve inaccessible content or bypass guest/account restrictions.

The owner uses only an Android phone. Repository updates, builds and checks must be cloud based; never require a computer, terminal, Android Studio or manual individual-file uploads. Prefer an authorized GitHub browser workflow; do not rely on a connector that previously lacked write access. No account keys, passwords or signing secrets in source control.

## Phase 1 — functional gallery

- Inspect attached Phase 1 source before changes; verify official API contracts before endpoint work.
- Two independent Explore fields: e621 tags/syntax and Inkbunny text/syntax.
- Query both; normalize metadata; merge and sort one grid; isolate failures.
- Model source, ID, source URL, artists, title, tags, upload timestamp, thumbnail, original media, media type, dimensions, favorite state, pools and multiple files.
- Pagination, infinite scrolling, refresh, cancellable queries, loading and errors, rate limiting and retry.
- Adjustable thumbnail/grid sizing, lazy loading and thumbnail caching; no full files fetched just to fill thumbnails.
- Native full-resolution viewer and cross-source submission navigation.
- GitHub Actions checkout, Java and Android setup, test, debug build and downloadable APK artifact. Resolve build errors; do not claim APK success before a successful build.

## Phase 2 — accounts

- e621 username/API-key or another official method; secure verification, persistent login, account switching, logout, authenticated requests, favorites, blacklist sync and account-specific search.
- Inkbunny official username/password session login, secure persistent SID, renewal where supported, favorites, watched artists, new/unread submissions, blocking/viewing preferences and logout. Never retain plaintext passwords or log credentials.
- Android Keystore-backed encryption, with DataStore for appropriate non-secret settings. Never log credentials or include them in repository/build artifacts/backups.
- Unified account favorites: route optimistic favorite/unfavorite to the correct source; rollback or clearly queue/report failures. Preserve account/source ownership. Separate optional local bookmarks.
- Fetch and apply e621 user blacklist using supported account mechanisms. Investigate Inkbunny accessible account settings; support refresh without inventing sync capabilities. User controls all blacklists.

## Phase 3 — following and media

- One chronological Following feed from Inkbunny native watched/unread submissions and locally managed e621 artist subscriptions if no equivalent native subscriptions exist.
- Artist information, refresh, viewed-state tracking, periodic background e621 retrieval. Loading must not mark all unread submissions read.
- Native full-screen image pinch zoom, double-tap, pan, swipe, GIF and video, Inkbunny file navigation, favorite, original download, tags, artist, source attribution, pool info and metadata.
- Animated GIF/WEBM/MP4 and other supported previews in grid. Silent autoplay only while visible; pause and release offscreen; cap simultaneous playback.
- Autoplay default enabled; disabled, Wi-Fi-only, reduced-data options. Prefer true preview variants, avoid original files for thumbnails. Clearly label formats. Media3/ExoPlayer or equivalent.

## Phase 4 — downloads and collections

- Unified originals manager: images/videos, selected batch download, progress, cancellation, retry, background work, configurable Android directory via user-selected storage, real extensions and collision-safe names.
- e621 pool ZIP: metadata, complete ordered post IDs, resolve downloadable originals, download all available entries, preserve order, ZIP to selected storage, optional manifest (pool name/ID/source URL/post IDs/original names/order).
- Pool ZIP must report deleted/unavailable/failed posts and archive completeness; never silently corrupt archives. Progress, cancellation and rate limiting throughout.
- Collections: saved paired searches, favorite artists, local artwork bookmarks, custom mixed-source collections, e621 pools. Room stores metadata and relationships.
- Search history, saved searches, autocomplete where supported and suggestions.

## Phase 5 — performance, recovery and release

- Primary navigation: Explore, Favorites, Following, Collections, Downloads, Settings. Compact collapsible search, art-first responsive grid, smooth scroll, clear states, minimal dialogs/navigation.
- Coroutines/Flow repository, independent adapters, efficient per-source pagination, cancellation, local metadata cache/offline use where practical, memory/disk image caches, preloading and request backoff.
- Performance checks on large galleries, API failure/recovery, full media compatibility, login/session expiry, preference behavior, downloads/cancellation and UI refinement.
- Secure cloud release signing later; secrets outside git, no release-signing claim before configured and validated. Release APK and phone-friendly documentation.

## Engineering implementation guidance

Recommended stack: Retrofit/OkHttp, Coroutines/Flow, Room, DataStore, Android Keystore, Coil, Media3, WorkManager, Gradle and GitHub Actions. Keep separate API adapters, common artwork model and unified repository. Direct site requests. A public/guest Phase 1 build does not count as account feature completion.

## Completion gates

1. Phase 1 code reviewed and actual cloud build succeeds, download location verified.
2. Continue account work after gate 1, then following/media and downloads/collections.
3. Label implemented, unverified and planned functionality distinctly. A compile alone does not prove live API or physical-device correctness.
