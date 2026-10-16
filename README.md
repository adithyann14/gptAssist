# claudeAssist

A simple WebView wrapper for [Claude](https://claude.ai), converted from
[gptAssist](https://github.com/woheller69/gptassist) (GPLv3).

- **Multiple accounts** with fully separate cookies and site data
- **Unrestricted by default** (Google / Apple / Microsoft sign-in works). Each account can be set to
  **restricted**, which blocks every URL that is not essential (claude.ai, claude.com, anthropic.com, Cloudflare check)
- File upload, voice input, downloads
- **Background running**: a foreground service (notification) keeps the app alive so replies finish while you switch apps. Toggle it in the long-press account dialog ("Background: on/off")

## Usage

| Gesture | Action |
|---|---|
| Tap the hand icon (top right) | Toggle restricted / unrestricted for the current account |
| **Long press** the hand icon | Account list: tap to switch, **Add account**, long press an entry to rename / sign out / delete |
| Swipe up | Hide the hand icon (swipe down at top of page to show it again) |

The restriction setting is remembered per account.

### Backup and restore
Long press the hand icon → **More…** → *Backup all data* / *Restore from backup*.
A backup holds all accounts (names, restricted flag) and their login cookies, optionally encrypted with a
passphrase (AES-256-GCM, PBKDF2). Restoring **replaces** all current accounts. Chat history lives on Claude's
servers; local site storage (localStorage etc.) is not part of a backup, so the web app may need a moment to re-sync.
Treat an unencrypted backup like a password: anyone with the file can use the sessions.

### Downloads
Files go to the Downloads folder. `blob:` / `data:` links (what Claude uses for generated files) are handled in-app;
normal links use the system DownloadManager with a built-in fallback.

### How accounts are separated
- On a system WebView with multi-profile support (WebView 126+), every account gets its own WebView
  *profile*: cookies, localStorage, cache and IndexedDB are fully isolated.
- On older WebViews, the app falls back to swapping the cookies of claude.ai / claude.com / anthropic.com
  and wiping site storage on every switch. Sessions stay separate, but this is less strict.

### E-mail sign-in links
The app registers `https://claude.ai/magic-link`. On Android 12+ enable
*Settings → Apps → claudeAssist → Open by default → Add link* so the link opens in the app
(it loads in the currently active account).

## Build
`./gradlew assembleDebug` (needs the Android SDK; FreeDroidWarn is fetched from JitPack).

## License
GPLv3, same as the original. Parts from GMaps WV (https://gitlab.com/divested-mobile/maps), GPLv3.
