# UnifiedArt for Windows

Standalone Windows 10/11 x64 desktop app. Electron supplies the desktop window and Chromium media playback; artwork, accounts, and search are native app screens rather than embedded source websites.

## Download

Get the Windows release from GitHub Releases:
- `UnifiedArt-Windows-0.1.0-Setup.exe`: installer with a desktop shortcut.
- `UnifiedArt-Windows-0.1.0-Portable.exe`: run without an installer.

No Node.js, browser extension, or development tools are needed to use either build. The app is not code-signed, so Windows may display an unknown-publisher notice.

## Included

One mixed-source chronological gallery; separate source query fields and source toggles; independent pagination, retry, and cancellation; account favorites; locally followed e621 artists and Inkbunny unread watched submissions; account login encrypted with Windows DPAPI; e621 account blacklist and Inkbunny hidden flags; full-resolution images, zoom/pan, GIFs and Chromium-supported videos; Inkbunny page navigation; tag/artist/pool browsing inside the app; bookmarks, mixed-source collections, saved artists/pools/searches; autocomplete/history; original and batch downloads, progress/cancel/retry; ordered e621 pool ZIPs with manifests and reported skips; thumbnail sizing and preview controls.

Blacklisted entries are omitted. Inkbunny noncustom thumbnail fields are supported. Only preview files supplied by the source animate the grid; full originals are loaded only in the viewer or downloads. At most two visible video previews play simultaneously. Account credentials and private-asset session tokens remain in the main process; renderer pages have no Node.js access. Passwords used for Inkbunny login are not retained. No accounts or collections automatically sync with Android.

## Current limits

Inkbunny's API does not document adding/removing site favorites or exporting all blocking preferences. Use bookmarks for local favorites; reconnect after changing Inkbunny settings. Viewed state is local and loading the Following feed never clears native unread submissions. Following refresh is manual. Downloads continue while the desktop app remains open and interrupted tasks can be retried after restarting. Unsupported codecs show a playback error with original download available. The Windows package is unsigned; authenticated accounts, all device codecs and download-folder permissions still need real-user validation.

## Development

`npm ci`, `npm test`, `npm run check`, `npm start`. `npm run dist:win` produces NSIS and portable x64 EXEs on the Windows CI runner. `npm run smoke` verifies startup, renderer sandboxing and the IPC boot contract without credentials. See the Windows workflow for cloud validation and packaging.
