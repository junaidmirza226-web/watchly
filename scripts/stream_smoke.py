#!/usr/bin/env python3
"""
Watchly — stream source smoke probe.

Probes the exact embed/direct URL patterns the app builds (see
StreamAggregator.buildEmbedUrlForEntry) for known titles and reports,
per source: reachability, redirects, and whether the payload exposes
direct media (.m3u8/.mp4), an iframe player, or dubbed-audio markers.
Used by .github/workflows/stream-smoke.yml for on-demand, real-network
evidence of what the player can actually reach. Report-only: exits 1
only when EVERY source is unreachable.
"""
import re
import sys
import urllib.request
import urllib.error

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

# Known ids used for the probe: Fight Club (movie, TMDB 550),
# Breaking Bad S1E1 (TV, TMDB 1396).
SOURCES = [
    ("VidSrc.to", "https://vidsrc.to/embed/movie/550"),
    ("VidSrc.me", "https://vidsrc.me/embed/movie/550"),
    ("MultiEmbed", "https://multiembed.mov/?video_id=550&tmdb=1"),
    ("VidLink", "https://vidlink.pro/movie/550?autoplay=true&primaryColor=D4AF37"),
    ("AutoEmbed", "https://autoembed.co/movie/tmdb/550"),
    ("2Embed", "https://2embed.mov/embed/550"),
    ("Embed.su", "https://embed.su/embed/movie/550"),
    ("MoviesAPI", "https://moviesapi.club/movie/550"),
    ("OttService(Netflix)", "https://vidsrc.vip/embed/movie/550?s=netflix"),
    ("VidSrc.to TV", "https://vidsrc.to/embed/tv/1396/1/1"),
    ("MultiEmbed TV", "https://multiembed.mov/?video_id=1396&tmdb=1&s=1&e=1"),
    ("MultiEmbed Hindi", "https://multiembed.mov/?video_id=550&tmdb=1&lang=hindi"),
]

M3U8 = re.compile(r"https?://[^\s\"'<>\\]+\.m3u8[^\s\"'<>\\]*")
MP4 = re.compile(r"https?://[^\s\"'<>\\]+\.mp4[^\s\"'<>\\]*")
IFRAME = re.compile(r"<iframe[^>]+src=", re.IGNORECASE)
DUB = re.compile(r"hindi|tamil|telugu|dual[ -]?audio|multi[ -]?audio|dubbed", re.IGNORECASE)


def probe(name: str, url: str) -> tuple[bool, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,*/*"})
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            body = resp.read(2_000_000).decode("utf-8", "ignore")
            final = resp.geturl()
            m3u8s = M3U8.findall(body)
            mp4s = MP4.findall(body)
            iframes = len(IFRAME.findall(body))
            dubs = sorted({m.group(0).lower() for m in DUB.finditer(body)})[:6]
            marks = []
            if m3u8s:
                marks.append(f"{len(m3u8s)} m3u8")
            if mp4s:
                marks.append(f"{len(mp4s)} mp4")
            if iframes:
                marks.append(f"{iframes} iframe")
            if dubs:
                marks.append("dub:" + ",".join(dubs))
            detail = " / ".join(marks) if marks else "shell page (player loads via JS)"
            if final != url:
                detail += f" — redirects to {final.split('?')[0]}"
            return True, f"HTTP {resp.status} — {detail}"
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001 — report anything
        return False, f"{type(e).__name__}: {e}"


def main() -> int:
    print("::notice::Watchly stream smoke — probing real provider endpoints")
    print("| Source | Result | Detail |")
    print("| --- | --- | --- |")
    ok = 0
    for name, url in SOURCES:
        reachable, detail = probe(name, url)
        ok += 1 if reachable else 0
        status = "✅" if reachable else "❌"
        print(f"| {name} | {status} | {detail} |")
    total = len(SOURCES)
    print(f"\n**{ok}/{total} sources reachable.**")
    if ok == 0:
        print("::error::No streaming source responded — the player cannot work.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
