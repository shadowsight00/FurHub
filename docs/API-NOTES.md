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
