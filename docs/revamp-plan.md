# Watchly — Premium Revamp Plan (MovieBox-grade, our features, zero ads)

> For review. Nothing is built until you approve.
> One immediate note: the update button in your screenshot tried to install a
> build **identical to what you already have (1.5.1.79)** — so the latest
> builds never reached you. That bug is item #1 below. To get current today:
> install **v1.5.1.85** directly (link in chat).

---

## The bar we're aiming at

What makes MovieBox feel premium is not features — it's **certainty**:

1. Tap Play → video is on screen in seconds. Always.
2. Tap Download → the file saves. No playing first, no excuses.
3. Nothing technical is ever visible. No sources, no servers, no jargon.
4. The app is fast: posters appear instantly, lists scroll smoothly, nothing stutters.
5. It tells the truth: if something isn't available, it says so in one plain line.

And our edge over MovieBox: **zero ads** (they show their own; we show none),
plus our features — Library, Watchlist, My Lists, History, downloads with
pause/resume, subtitles in every language, all dubbed audio, backup file.

---

## Pillar 1 — The Interface (MovieBox feel, Watchly identity)

**1.1 Home** — big auto-rotating hero banner + rows of large posters:
Continue Watching · Trending Now · New Releases · Hindi · Urdu · Tamil ·
Telugu · Anime · TV. (Rows adapt to what you watch.) No walls of text,
no promo commentary. Bottom tabs stay: Home · Movies · Shows · Anime · My Library.

**1.2 Details** — hero art, title, year · rating · runtime, one short line,
then the buttons: **Play** (big, gold), Download, Watchlist, Add to, Trailer.
Cast + "More like this" below. Nothing else — no sources, no "where to watch",
no notes. *(Mostly done already — this is a verification pass.)*

**1.3 Player** — full-screen-first, minimal controls: play/seek, subtitles,
**audio (all dubs)**, quality, speed, next episode. Source switching exists
but is **invisible until wanted** — one small swap icon, and it never names
a provider (see Decision A). Dead sources switch away by themselves.

**1.4 My Library** — Downloads (big tiles, progress rings, pause/resume),
Watchlist, History, My Lists, Settings group (real settings only — already done).

**1.5 Search** — instant results as a big poster grid.

## Pillar 2 — The engine (it just works)

**2.1 Playback** *(core shipped in v1.5.1.85: real-file capture, native-first,
dead-page failover, stuck-player boot)* — remaining:
- **Preload at details-open**: the file is found *while you read the page*,
  so Play is instant (the MovieBox "open and it plays" feeling).
- **Memory**: the source that worked last time is tried first.
- **Next episode** auto-resolves before you tap.
- **All dubbed versions** offered in the player's Audio menu where sources
  carry them (your standing requirement).

**2.2 Download** *(core shipped: download-without-playing, HLS assembly)* —
remaining: speed display, reliable pause/resume, delete-with-file, plain
status lines. No storage cap (done).

**2.3 Honest states** — one plain line when something isn't available;
retry buttons where retry makes sense. Never technical wording.

## Pillar 3 — Premium feel

- **Speed**: cold start under ~1.5 s, instant posters (caching), skeletons
  instead of blank screens, 60 fps scrolling everywhere.
- **Motion**: poster tap → details zoom, details → player fade, smooth tabs.
- **Continuity**: progress bars on every poster, resume exactly where you left.

## Pillar 4 — Trust (updates & releases)

**4.1 Fix the update button (your screenshot)** — hardening, in order:
1. Never open the installer for a build equal to the installed one —
   show "You're already on the latest version" instead.
2. Verify the downloaded file's version **exactly matches** what the banner
   promised, or abort with a plain reason.
3. The check at tap time gets cache-busting + one retry, and a visible
   "Checking…" state so the button is never silent.
4. A "Version" line in Settings: current version + Check for updates.
5. If it ever misbehaves again, the update card shows *which* build it
   found and *what* it downloaded — no more guessing.

**4.2 Releases** stay as they are: immutable assets, byte-for-byte proof,
real-device test labs before every publish.

---

## What we will NOT do (locked, per your earlier decisions)

- **No ads** — not ours, not the sources' (stricter than MovieBox).
- **No source names** anywhere outside the hidden player switcher.
- No "where to watch", no accounts/cloud promises, no TV work,
  no commentary or instructional text anywhere.

---

## Suggested order

| Phase | What | Result |
|---|---|---|
| **1** | Update fix + playback preload/memory/next-episode + download polish | It just works, day to day |
| **2** | Interface pass (home rows, player chrome, hidden sources) + speed/motion | Feels premium on open |
| **3** | Dubs fan-out + continuity polish + offline niceties | The last 10 % |

Each phase ships as its own tested release (all suites + device labs green
before you see it).

---

## Decisions I need from you

- **A. Source switching in the player**: (i) one hidden swap icon that just
  cycles (most MovieBox-like), or (ii) a hidden sheet listing neutral
  "Stream 1 · Stream 2 · …" with quality hints, never provider names?
- **B. Home rows**: are the suggested rows right (esp. the language rows —
  Hindi/Urdu/Tamil/Telugu), and do you want Anime as its own tab (current) or a row?
- **C. Update notice**: keep the gold banner on Home, or make it a small
  pill in Settings only (cleaner home)?
- **D. Anything to add/remove/reprioritize** in the three phases?
