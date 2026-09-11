# Khabar — RSS news reader

An offline-first RSS/Atom reader for Android. **No backend, no API key, no login, no monthly bill.**

The app talks straight to the feed URLs you choose, over plain HTTPS, and keeps everything it
downloads on the phone. There is no server of ours in the loop — nothing to host, nothing to pay
for, nothing that can go down and take your reading list with it.

```
publisher ka public RSS feed
            ↓   (direct HTTPS GET from the phone)
        Khabar app
            ↓
   phone ka Room database  →  offline padho
```

---

## What it does

| | |
|---|---|
| **Add a feed** | Paste a feed URL, a site URL, or just `example.com`. If the page is not itself a feed, Khabar reads the page's `<link rel="alternate">` tags, falls back to the paths every blog engine uses (`/feed`, `/rss.xml`, `/index.xml`, …), verifies each candidate by actually parsing it, and asks you to choose only when a site really publishes more than one. |
| **Starter feeds** | A built-in list (BBC हिंदी, NDTV, The Hindu, Indian Express, Mint, TOI, BBC World, Al Jazeera, Hacker News, Ars Technica, The Verge, Android Developers, NASA, ESPNcricinfo, xkcd) so a fresh install is not an empty box. |
| **Timeline** | All feeds merged, newest first, with **All / Unread / Saved** tabs, a per-feed filter and search across titles and summaries. |
| **Offline reading** | Article bodies are stored, not just headlines. The built-in reader renders the feed's HTML — headings, lists, quotes, code, links and images — without a WebView, so it works on the metro with no signal. |
| **Save** | Bookmark anything. Saved articles are never pruned and never deleted by "clear cache". |
| **Read / unread** | Auto-marked on open (switchable), plus mark-all-read. |
| **Background refresh** | WorkManager pulls every feed on your schedule (off, or every 1–48 hours), optionally Wi-Fi only. |
| **OPML** | Import your subscriptions from Feedly / Inoreader / any other reader, and export them back out. Nothing is locked in. |
| **Original article** | One tap opens it in your browser; share sends the link anywhere. |
| **Appearance** | Light / dark / follow-system, and a reader text size slider. |

Conditional GETs (`ETag` / `If-Modified-Since`) mean a refresh of an unchanged feed costs one `304`
and no body — which matters when you are on mobile data.

---

## Getting the APK

Every push builds it. Download from either:

* **Releases** — each successful build publishes `Khabar-debug.apk` and `Khabar-release.apk`
  under a tag `khabar-apk-<n>`.
* **Actions → Build Khabar APK → Artifacts → `Khabar-apk`.**

Both are installable by sideload. `release` is signed with the debug key unless a keystore is
configured, so install one or the other, not both — Android refuses an update signed by a
different key.

To sign release builds with a real key, set `KHABAR_KEYSTORE`, `KHABAR_KEYSTORE_PASSWORD`,
`KHABAR_KEY_ALIAS` and `KHABAR_KEY_PASSWORD` in the workflow environment from repository secrets.

There is nothing to configure on first run. Open the app, add a feed, read.

---

## How it is built

```
rssreader/app/src/main/kotlin/com/khabar/reader/
  feed/      RSS 2.0 / Atom 1.0 / RDF parsing, date parsing, URL handling,
             feed discovery, HTML → text and HTML → renderable blocks
  data/      Room entities, DAOs, database, DataStore preferences
  net/       OkHttp client and the conditional-GET fetcher
  repo/      FeedRepository (subscribe, refresh, dedup, prune), OPML, starter feeds
  work/      WorkManager periodic refresh
  ui/        Compose screens, theme, one ViewModel
```

Kotlin 2.0 · Jetpack Compose (Material 3) · Room · OkHttp · WorkManager · DataStore · Coil.
minSdk 24, targetSdk 35. No paid service and no third-party account is required by any of them.

### The parser is deliberately plain JVM code

`feed/` imports nothing from `android.*` — only the Kotlin stdlib and the JDK's
`javax.xml.parsers`. That is what lets the messy parts run as ordinary JUnit tests in CI
(`./gradlew testDebugUnitTest`) instead of needing an emulator, and real feeds are messy:
undeclared HTML entities, bare `&` inside URLs, a BOM in front of the prolog, an encoding
declaration that contradicts the bytes, raw unescaped HTML inside `<description>`, RDF feeds whose
`<item>`s are siblings of `<channel>`, and about eighty different date formats. The test suite is
built from feeds that actually do these things.

### What is stored, and where

Everything is in the app's private storage: a Room database (`khabar.db`) for feeds and articles,
and a DataStore file for settings. Nothing is uploaded anywhere. Uninstalling removes it all;
Android's own backup can carry both to a new phone.

---

## Honest caveats

* **The feeds are not ours.** Publishers move, rename and retire feed URLs, and some put only a
  teaser in the feed rather than the full article — Khabar shows what the feed contains and links
  out for the rest. A dead feed shows its error on the Feeds screen; it does not break the app.
* **Cleartext HTTP is allowed.** A large minority of blogs and regional news sites still publish
  their feed over plain `http://`. Refusing them would look like a bug, so cleartext is enabled
  and the feed URL is always shown to you as typed. Prefer `https://` where the site offers it.
* **No full-page scraping.** Khabar renders the feed's own content. Sites that publish summary-only
  feeds stay summary-only; the "open original" button is the answer, not a workaround.
* **Costs that are not zero:** your mobile data, and the one-time Google Play registration fee if
  you ever publish it there. Sideloading the APK costs nothing.
