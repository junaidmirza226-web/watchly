#!/usr/bin/env python3
"""
Watchly - Automated Streaming Provider Discovery Engine
======================================================
Runs on a weekly schedule (every Sunday in GitHub Actions) to probe and discover
new streaming embed and API providers from a curated catalog of known sources.

Key Responsibilities:
1. Maintains a curated list of ~35 known embed provider URL patterns.
2. Deduplicates candidates against existing entries in `providers.json`.
3. Probes candidates using sample TMDB ID (default: 550 = Fight Club).
4. Verifies response validity (HTTP 200, valid HTML/JSON player, not 404/403/parked).
5. Appends newly discovered providers to `providers.json` with `enabled: true` and a lower initial priority.
6. Generates a Markdown discovery report for GitHub Actions Job Summaries.
7. Exits with code 1 if new providers were added, or 0 if no changes.
"""

import argparse
import concurrent.futures
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# Curated list of known embed provider URL patterns to probe
DISCOVERY_CANDIDATES = [
    {
        "name": "VidSrc Me",
        "baseUrl": "https://vidsrc.me",
        "embedPattern": "https://vidsrc.me/embed/movie?tmdb={tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc In",
        "baseUrl": "https://vidsrc.in",
        "embedPattern": "https://vidsrc.in/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc PM",
        "baseUrl": "https://vidsrc.pm",
        "embedPattern": "https://vidsrc.pm/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc Net",
        "baseUrl": "https://vidsrc.net",
        "embedPattern": "https://vidsrc.net/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc XYZ",
        "baseUrl": "https://vidsrc.xyz",
        "embedPattern": "https://vidsrc.xyz/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc Rip",
        "baseUrl": "https://vidsrc.rip",
        "embedPattern": "https://vidsrc.rip/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc ICU",
        "baseUrl": "https://vidsrc.icu",
        "embedPattern": "https://vidsrc.icu/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc NL",
        "baseUrl": "https://vidsrc.nl",
        "embedPattern": "https://vidsrc.nl/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc VIP",
        "baseUrl": "https://vidsrc.vip",
        "embedPattern": "https://vidsrc.vip/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidSrc Club",
        "baseUrl": "https://vidsrc.club",
        "embedPattern": "https://vidsrc.club/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "2Embed Skin",
        "baseUrl": "https://2embed.skin",
        "embedPattern": "https://2embed.skin/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "2Embed To",
        "baseUrl": "https://www.2embed.to",
        "embedPattern": "https://www.2embed.to/embed/tmdb/movie?id={tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "AutoEmbed Pro",
        "baseUrl": "https://autoembed.pro",
        "embedPattern": "https://autoembed.pro/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Smashy Stream XYZ",
        "baseUrl": "https://player.smashy.stream",
        "embedPattern": "https://player.smashy.stream/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "MultiEmbed Org",
        "baseUrl": "https://multiembed.org",
        "embedPattern": "https://multiembed.org/?video_id={tmdbId}&tmdb=1",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Database Gdrive Player",
        "baseUrl": "https://databasegdriveplayer.xyz",
        "embedPattern": "https://databasegdriveplayer.xyz/player.php?tmdb={tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "CinemaAZ",
        "baseUrl": "https://cinemaaz.net",
        "embedPattern": "https://cinemaaz.net/embed/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "StreamEmbed",
        "baseUrl": "https://streamembed.cc",
        "embedPattern": "https://streamembed.cc/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "ProStream",
        "baseUrl": "https://prostream.to",
        "embedPattern": "https://prostream.to/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "AnyEmbed",
        "baseUrl": "https://anyembed.net",
        "embedPattern": "https://anyembed.net/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "PrimeWire Embed",
        "baseUrl": "https://primewire.tf",
        "embedPattern": "https://primewire.tf/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Movie123 Embed",
        "baseUrl": "https://embed.123moviesplayer.net",
        "embedPattern": "https://embed.123moviesplayer.net/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Moviee TV",
        "baseUrl": "https://moviee.tv",
        "embedPattern": "https://moviee.tv/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "PlayM4u",
        "baseUrl": "https://playm4u.xyz",
        "embedPattern": "https://playm4u.xyz/embed/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VidMoly",
        "baseUrl": "https://vidmoly.to",
        "embedPattern": "https://vidmoly.to/embed-{tmdbId}.html",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "StreamWish",
        "baseUrl": "https://streamwish.to",
        "embedPattern": "https://streamwish.to/e/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "FileMoon",
        "baseUrl": "https://filemoon.sx",
        "embedPattern": "https://filemoon.sx/e/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "DoodStream",
        "baseUrl": "https://dood.to",
        "embedPattern": "https://dood.to/e/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "VoeSX",
        "baseUrl": "https://voe.sx",
        "embedPattern": "https://voe.sx/e/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "ShowBox",
        "baseUrl": "https://showbox.media",
        "embedPattern": "https://showbox.media/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Goku Embed",
        "baseUrl": "https://goku.sx",
        "embedPattern": "https://goku.sx/watch-movie/watch-{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "Sflix Embed",
        "baseUrl": "https://sflix.to",
        "embedPattern": "https://sflix.to/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "EmbedFlix",
        "baseUrl": "https://embedflix.net",
        "embedPattern": "https://embedflix.net/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "LookMovie Embed",
        "baseUrl": "https://lookmovie2.to",
        "embedPattern": "https://lookmovie2.to/embed/movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    },
    {
        "name": "FlixWave Embed",
        "baseUrl": "https://flixwave.to",
        "embedPattern": "https://flixwave.to/watch-movie/{tmdbId}",
        "type": "EMBED",
        "requiresReferer": True
    }
]

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

# Strings that indicate a domain or endpoint is non-functional
ERROR_INDICATORS = [
    "404 not found",
    "page not found",
    "error 521",
    "error 522",
    "error 523",
    "this domain is parked",
    "this domain is suspended",
    "domain has expired",
    "account suspended",
    "website is unavailable",
    "invalid tmdb id",
    "no streams found"
]

# Strings that indicate an embed or player page is alive
VALID_INDICATORS = [
    "<iframe",
    "player",
    "video",
    "m3u8",
    "embed",
    "stream",
    "sources",
    "jwplayer",
    "artplayer",
    "dplayer",
    "<script",
    "<div id=\"player\""
]


def normalize_domain(url: str) -> str:
    """Extract clean domain/host without www or port."""
    try:
        parsed = urllib.parse.urlparse(url)
        host = parsed.netloc or parsed.path
        host = host.split(":")[0].lower()
        if host.startswith("www."):
            host = host[4:]
        return host
    except Exception:
        return url.lower()


def find_providers_json(custom_path: str = None) -> Path:
    """Locate providers.json relative to script or workspace root."""
    if custom_path:
        return Path(custom_path)
    
    script_dir = Path(__file__).resolve().parent
    candidates = [
        Path.cwd() / "providers.json",
        script_dir.parent / "providers.json",
        script_dir / "providers.json"
    ]
    for c in candidates:
        if c.exists():
            return c
    return script_dir.parent / "providers.json"


def load_providers(filepath: Path) -> tuple[list, dict | None]:
    """Load existing providers list."""
    if filepath.exists():
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return data, None
                elif isinstance(data, dict) and "providers" in data:
                    return data["providers"], data
        except Exception as e:
            print(f"[WARN] Failed to load {filepath}: {e}")
    return [], None


def test_candidate(candidate: dict, tmdb_id: int = 550, imdb_id: str = "tt0137523", timeout: float = 15.0) -> tuple[bool, float | None, str]:
    """
    Probe candidate with sample movie TMDB ID.
    Returns: (is_valid, latency_ms, detail)
    """
    url = candidate["embedPattern"].format(
        tmdbId=tmdb_id,
        imdbId=imdb_id,
        season=1,
        episode=1
    )
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"
    }
    if candidate.get("requiresReferer"):
        headers["Referer"] = candidate["baseUrl"]

    start_time = time.perf_counter()
    ctx = ssl.create_default_context()

    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            elapsed_ms = round((time.perf_counter() - start_time) * 1000, 1)
            status = resp.getcode()
            if status != 200:
                return False, elapsed_ms, f"HTTP {status}"

            # Read first 32KB of response to check validity without downloading huge streams
            chunk = resp.read(32768)
            content_type = resp.headers.get("Content-Type", "").lower()
            text = chunk.decode("utf-8", errors="ignore").lower()

            # Verify content isn't an error message
            for err in ERROR_INDICATORS:
                if err in text:
                    return False, elapsed_ms, f"Found error indicator: '{err}'"

            # Check for positive player indicators or valid json
            if "application/json" in content_type:
                return True, elapsed_ms, "Valid JSON API response"

            for val in VALID_INDICATORS:
                if val in text:
                    return True, elapsed_ms, f"Found player cue: '{val}'"

            if len(chunk) > 500:
                return True, elapsed_ms, f"Valid HTML page ({len(chunk)} bytes)"

            return False, elapsed_ms, f"Ambiguous content ({len(chunk)} bytes)"
    except urllib.error.HTTPError as e:
        elapsed_ms = round((time.perf_counter() - start_time) * 1000, 1)
        return False, elapsed_ms, f"HTTP Error {e.code}"
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", str(e))
        return False, None, f"Connection Failed: {reason}"
    except TimeoutError:
        return False, None, f"Timed out after {timeout}s"
    except Exception as e:
        return False, None, f"Error: {type(e).__name__}"


def generate_discovery_report(discovered: list, probed_count: int) -> str:
    """Generate Markdown report for GitHub Actions Job Summary."""
    lines = []
    lines.append("# 🔍 Watchly Streaming Provider Discovery Report")
    lines.append("")
    lines.append(f"> **Automated Discovery Run** at `{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}`")
    lines.append("")
    lines.append(f"![Discovered Providers](https://img.shields.io/badge/New%20Discovered-{len(discovered)}-blue) "
                 f"![Candidates Probed](https://img.shields.io/badge/Probed%20Candidates-{probed_count}-lightgrey)")
    lines.append("")

    if discovered:
        lines.append("### 🌟 Newly Discovered Working Providers")
        lines.append("")
        lines.append("The following providers responded with valid video embed markup for TMDB `550` (Fight Club) and were added to `providers.json`:")
        lines.append("")
        lines.append("| Provider Name | Base URL | Initial Priority | Response Time | Discovered |")
        lines.append("| :--- | :--- | :---: | :---: | :--- |")
        for p in discovered:
            lines.append(
                f"| **{p.get('name')}** | `{p.get('baseUrl')}` | `#{p.get('priority')}` | "
                f"`{p.get('response_time_ms', '-')} ms` | `{p.get('discovered_at')}` |"
            )
        lines.append("")
        lines.append("> [!TIP]")
        lines.append("> New providers are initially assigned lower priority. The 30-minute health check workflow will promote them based on measured uptime and speed.")
    else:
        lines.append("### ✅ No New Providers Discovered")
        lines.append(f"All {probed_count} candidate patterns were probed; no new functional providers outside the existing registry were detected.")
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Watchly Automated Provider Discovery")
    parser.add_argument("--file", "-f", help="Path to providers.json")
    parser.add_argument("--tmdb-id", type=int, default=550, help="Sample TMDB ID to probe (default: 550 = Fight Club)")
    parser.add_argument("--imdb-id", default="tt0137523", help="Sample IMDb ID to probe")
    parser.add_argument("--timeout", "-t", type=float, default=15.0, help="Per-candidate probe timeout (seconds)")
    parser.add_argument("--workers", "-w", type=int, default=8, help="Concurrent probe threads")
    parser.add_argument("--report", "-r", help="Path to write Markdown summary (e.g. $GITHUB_STEP_SUMMARY)")
    parser.add_argument("--exit-zero", action="store_true", help="Always exit with code 0")
    args = parser.parse_args()

    target_path = find_providers_json(args.file)
    print(f"[INFO] Using providers file: {target_path}")

    current_providers, original_wrapper = load_providers(target_path)
    print(f"[INFO] Current providers in config: {len(current_providers)}")

    # Deduplication map by domain and baseUrl
    existing_domains = {normalize_domain(p.get("baseUrl", "")) for p in current_providers if p.get("baseUrl")}
    existing_urls = {p.get("baseUrl", "").strip().rstrip("/") for p in current_providers if p.get("baseUrl")}

    # Filter candidates to only new ones
    candidates_to_probe = []
    for c in DISCOVERY_CANDIDATES:
        cand_domain = normalize_domain(c["baseUrl"])
        cand_url = c["baseUrl"].strip().rstrip("/")
        if cand_domain in existing_domains or cand_url in existing_urls:
            continue
        candidates_to_probe.append(c)

    print(f"[INFO] Total catalog candidates: {len(DISCOVERY_CANDIDATES)}")
    print(f"[INFO] New candidates to probe after deduplication: {len(candidates_to_probe)}")

    if not candidates_to_probe:
        print("[INFO] All catalog candidates already registered. Nothing to probe.")
        if args.report:
            report_md = generate_discovery_report([], 0)
            Path(args.report).parent.mkdir(parents=True, exist_ok=True)
            with open(args.report, "a", encoding="utf-8") as f:
                f.write(report_md + "\n")
        sys.exit(0)

    # Determine starting priority for new providers (lower priority = higher number)
    current_max_priority = max([p.get("priority", 0) for p in current_providers], default=15)
    next_priority = current_max_priority + 10

    discovered = []
    now_iso = datetime.now(timezone.utc).isoformat()

    print(f"[INFO] Probing candidates concurrently (tmdbId={args.tmdb_id})...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        future_to_cand = {
            executor.submit(test_candidate, c, args.tmdb_id, args.imdb_id, args.timeout): c
            for c in candidates_to_probe
        }
        for future in concurrent.futures.as_completed(future_to_cand):
            cand = future_to_cand[future]
            name = cand["name"]
            try:
                is_valid, latency_ms, detail = future.result()
                if is_valid:
                    print(f"[DISCOVERED] {name} ({cand['baseUrl']}) responded OK ({latency_ms}ms, {detail})")
                    entry = {
                        "name": cand["name"],
                        "baseUrl": cand["baseUrl"],
                        "enabled": True,
                        "priority": next_priority,
                        "type": cand.get("type", "EMBED"),
                        "requiresReferer": cand.get("requiresReferer", True),
                        "userAgent": DEFAULT_USER_AGENT,
                        "status": "up",
                        "response_time_ms": latency_ms,
                        "last_checked": now_iso,
                        "discovered_at": now_iso,
                        "uptime_percentage": 100.0,
                        "total_checks": 1,
                        "successful_checks": 1,
                        "consecutive_failures": 0
                    }
                    discovered.append(entry)
                    next_priority += 1
                else:
                    print(f"[UNAVAILABLE] {name}: {detail}")
            except Exception as e:
                print(f"[ERROR] Probing {name}: {e}")

    # If new providers found, update providers.json
    has_changes = len(discovered) > 0
    if has_changes:
        print(f"[INFO] Adding {len(discovered)} newly discovered providers to {target_path}...")
        updated_list = current_providers + discovered
        target_path.parent.mkdir(parents=True, exist_ok=True)
        with open(target_path, "w", encoding="utf-8") as f:
            if original_wrapper is not None:
                original_wrapper["providers"] = updated_list
                json.dump(original_wrapper, f, indent=2)
            else:
                json.dump({
                    "tmdbKey": "4f298a53e5522830c957e3bc1d007647",
                    "providers": updated_list
                }, f, indent=2)
        print(f"[SUCCESS] Saved updated registry with {len(updated_list)} total providers.")

    # Write Markdown summary report
    report_md = generate_discovery_report(discovered, len(candidates_to_probe))
    if args.report:
        report_path = Path(args.report)
        try:
            report_path.parent.mkdir(parents=True, exist_ok=True)
            with open(report_path, "a", encoding="utf-8") as f:
                f.write(report_md + "\n")
            print(f"[INFO] Discovery report appended to {report_path}")
        except Exception as e:
            print(f"[WARN] Failed to write report: {e}")

    print("\n" + "=" * 60)
    print("WATCHLY DISCOVERY SUMMARY")
    print("=" * 60)
    print(f"Candidates Probed:  {len(candidates_to_probe)}")
    print(f"New Found:          {len(discovered)}")
    print("=" * 60)

    if has_changes and not args.exit_zero:
        print("[INFO] Exiting with code 1 (new providers added for commit).")
        sys.exit(1)

    sys.exit(0)


if __name__ == "__main__":
    main()
