# API review — 2026-11-16

## e621

The official help URL https://e621.net/help/api could not be retrieved from this environment (the search service returned 402 and the network returned a Site Unavailable page). Do not represent the help page as having been successfully read.

The official server implementation was read directly instead:
- https://github.com/e621ng/e621ng/blob/master/app/controllers/posts_controller.rb
- https://github.com/e621ng/e621ng/blob/master/app/blueprints/post_legacy_blueprint.rb

These confirm public JSON index access with `tags`, `page`, `limit`, the `posts` collection, and the file/preview/tags/pools/is_favorited legacy fields. Missing media URLs are intentional for unavailable posts; never synthesize CDN URLs to bypass permissions.

Every API and media request supplies a descriptive UnifiedArt User-Agent with the project URL. API calls are serialized with a 650 ms minimum interval. HTTP 429 and server/network failures retry at most twice with cancellable backoff; Retry-After numeric seconds are honored up to 60 seconds per retry. No content-rating filter is appended to e621 queries. User-entered syntax is preserved.

## Inkbunny

Official documentation read: https://wiki.inkbunny.net/wiki/API

- POST `api_login.php` with `username=guest`, no password. Reuse SID; renew only after documented invalid/expired SID error 2.
- POST `api_userrating.php` is the documented guest rating selector. Enable tags 2–5 for unrestricted guest browsing. It cannot unlock guest-blocked submissions. Do not call this to override a registered account's preferences.
- POST `api_search.php`: `sid`, `text`, `keywords=yes`, `orderby=create_datetime`, `submissions_per_page=40`, `page`, `get_rid=yes`. Subsequent pages reuse RID. Native username searches use `username`.
- Inkbunny text is its own keyword query language, not e621 metatag syntax. The second input passes text as entered.
- POST `api_submissions.php`: `sid`, `submission_ids`, `show_pools=yes`. Search only contains basic metadata; request details when a submission is opened.
- Original files use `files[].file_url_full`, sorted by `submission_file_order`; MIME type and full dimensions come from each file.
- Thumbnails use only documented thumbnail fields, never full-resolution fallbacks in the grid.
- Dates include explicit offsets; convert to epoch seconds before merging with e621 timestamps.
- `hidden` is source blocking, not an app blacklist. Hide its preview and media.
- Asset requests send `Referer: https://inkbunny.net`. Public assets do not receive SID. Authenticated private-asset handling is a Phase 2 requirement.
- API calls are serialized with 1100 ms minimum spacing. No logs contain SID or API response bodies.

## Remaining research before Phase 2/3

Verify current official mechanisms for favorites mutations, account-blacklist retrieval, native watched/unread submissions, viewed-state updates, and accessible block/viewing preferences. The reviewed Inkbunny page does not justify inventing a universal preference-sync or favorites-write endpoint. Feed loading must never bulk-mark unread content read. Retain manual account preference refresh where supported and document unsupported settings.

## Validation boundary

The included tests exercise data normalization, source identity, timezone conversion, hidden flags and multi-page ordering using synthetic metadata. No authenticated account, live API response, physical device or release-signing behavior has been validated yet. GitHub Actions has not run until repository access is available.

## Phase 2 additions

Reviewed official e621 server controllers for Users and Favorites and the client blacklist Filter/FilterToken/FilterUtilities implementations in the e621ng/e621ng repository. Account verification requires the self-only `blacklisted_tags` field from GET `/users/:name.json` with HTTP Basic username/API-key authorization. GET `/favorites.json?user_id=...` reads account favorites; POST `/favorites.json` with `post_id` adds a favorite; DELETE `/favorites/:id.json` removes it. Read-only requests and account operations share one source-specific rate limiter.

The Inkbunny documented `favs_user_id` search filter reads favorites. Registered sessions reuse SID without the guest-rating mutation. Invalid registered sessions report reconnect instead of falling back to guest. Registered logout calls the official logout endpoint and removes the encrypted local connection. Full account-block preference export and favorite mutations were not found in the reviewed API documentation; these are not claimed as supported.

The e621 blacklist matcher handles AND lines, optional OR tokens, negation, comments, wildcard tags, rating/type/status, uploader IDs/names, pools, favorites and documented numeric comparisons/ranges. It only evaluates user-supplied account rules; no app-owned rating blacklist is installed.

## Following, previews and local storage additions

Inkbunny watchlist uses `api_watchlist.php` and its documented `watches` array. Following uses `unread_submissions=yes` without any unread mutation. View tracking is local. Inkbunny autocomplete uses `api_search_autosuggest.php`, `keyword`, the current account's `ratingsmask` (all supported ratings for the app's guest mode), and the documented `results[].value` response. e621 tag suggestions use the official Tags controller's `search[name_matches]` filter.

Reviewed the official e621 Pool controller/model and Post `video_sample_list` implementation. Pool metadata returns `post_ids` in intended order. Gallery video previews use the smallest available `sample.alternates.samples` variant, never the original/alternate full video just to animate a thumbnail. Sources without a documented preview variant keep their thumbnail; this avoids fetching full-resolution media in the grid.

All media tokens are stripped before metadata is cached. An Inkbunny SID is added at request time only for private assets on the exact official host, never arbitrary URLs or public CDN assets. Private assets are excluded from the image disk cache, and cache identities include the connected source account. Saved artwork requires live availability verification before media viewing. Eight JVM tests cover source identity/merging, timestamp offsets, blocked/multi-file normalization, blacklist expressions, token routing and cache serialization.

Downloads use Android's persisted Storage Access Framework folder grant and WorkManager foreground tasks. Pool file fetches stage to one temporary file at a time; failed media never enters a ZIP as a partial entry. Archive-writing errors fail and clean up the entire archive; source failures are recorded in manifest entries. Real accounts, large media, device background policy, storage providers and release signing remain runtime-validation work.
