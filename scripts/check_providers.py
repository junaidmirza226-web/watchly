#!/usr/bin/env python3
"""
Watchly - Streaming Provider Health Check & Self-Healing Engine
==============================================================
Runs on a scheduled basis (every 30 minutes in GitHub Actions) to monitor
all configured streaming embed and API providers.

Key Responsibilities:
1. Reads `providers.json` from the repository root.
2. Sends lightweight HTTP HEAD (or fallback GET) requests with 15s timeout.
3. Measures response latency and tracks uptime history.
4. Auto-disables non-responsive or erroring providers (enabled: false).
5. Auto-re-enables recovered providers when they respond with HTTP 200 (enabled: true).
6. Re-ranks providers by reliability (uptime percentage) and response speed.
7. Generates a Markdown health report suitable for GitHub Actions Job Summaries.
8. Exits with code 1 if changes were made (to trigger git commit/push), or 0 if unchanged.
"""

import argparse
import concurrent.futures
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import ssl
import sys
import time
import urllib.error
import urllib.request

# Default fallback seed providers if providers.json does not exist yet
DEFAULT_PROVIDERS = [
    {
        "name": "VidSrc.to",
        "baseUrl": "https://vidsrc.to",
        "enabled": True,
        "priority": 1,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "VidSrc.pro",
        "baseUrl": "https://vidsrc.pro",
        "enabled": True,
        "priority": 2,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "VidSrc.cc",
        "baseUrl": "https://vidsrc.cc",
        "enabled": True,
        "priority": 3,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "Embed.su",
        "baseUrl": "https://embed.su",
        "enabled": True,
        "priority": 4,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "2Embed",
        "baseUrl": "https://www.2embed.cc",
        "enabled": True,
        "priority": 5,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "AutoEmbed",
        "baseUrl": "https://player.autoembed.cc",
        "enabled": True,
        "priority": 6,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "VidLink",
        "baseUrl": "https://vidlink.pro",
        "enabled": True,
        "priority": 7,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "MoviesAPI",
        "baseUrl": "https://moviesapi.club",
        "enabled": True,
        "priority": 8,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "SuperStream",
        "baseUrl": "https://superstream.tech",
        "enabled": True,
        "priority": 9,
        "type": "API",
        "requiresReferer": False,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "FlixHQ",
        "baseUrl": "https://flixhq.click",
        "enabled": True,
        "priority": 10,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "VidBinge",
        "baseUrl": "https://vidbinge.com",
        "enabled": True,
        "priority": 11,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "SmashyStream",
        "baseUrl": "https://embed.smashystream.com",
        "enabled": True,
        "priority": 12,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "RiveStream",
        "baseUrl": "https://rivestream.live",
        "enabled": True,
        "priority": 13,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "NontonGo",
        "baseUrl": "https://www.nontongo.win",
        "enabled": True,
        "priority": 14,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "MultiEmbed",
        "baseUrl": "https://multiembed.mov",
        "enabled": True,
        "priority": 15,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "AutoEmbed.co",
        "baseUrl": "https://autoembed.co",
        "enabled": True,
        "priority": 16,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "VidCloud",
        "baseUrl": "https://vidcloud9.com",
        "enabled": True,
        "priority": 17,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    },
    {
        "name": "2Embed.skin",
        "baseUrl": "https://2embed.skin",
        "enabled": True,
        "priority": 18,
        "type": "EMBED",
        "requiresReferer": True,
        "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
]

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"


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
    # Default to repo root if not found
    return script_dir.parent / "providers.json"


def load_providers(filepath: Path) -> list:
    """Load providers list from JSON file or create with default seed."""
    if filepath.exists():
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return data
                elif isinstance(data, dict) and "providers" in data:
                    return data["providers"]
        except Exception as e:
            print(f"[WARN] Failed to read {filepath}: {e}. Initializing seed defaults.")
    
    print(f"[INFO] Initializing {filepath} with default provider seed list.")
    return [dict(p) for p in DEFAULT_PROVIDERS]


def create_ssl_context() -> ssl.SSLContext:
    """Create SSL context with sensible defaults for probing public embeds."""
    ctx = ssl.create_default_context()
    # Certain embed hosts have unconventional TLS chains; maintain security while permitting SNI
    return ctx


def probe_endpoint(url: str, headers: dict, timeout: float = 15.0) -> tuple[bool, int | None, float | None, str]:
    """
    Perform lightweight probe of base URL.
    Tries HEAD first; falls back to GET with range bytes if HEAD returns 405 or 403.
    Returns: (is_alive, http_status, response_time_ms, message)
    """
    ssl_context = create_ssl_context()
    start_time = time.perf_counter()

    # Step 1: Attempt HEAD request
    req = urllib.request.Request(url, headers=headers, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl_context) as resp:
            elapsed_ms = round((time.perf_counter() - start_time) * 1000, 1)
            status = resp.getcode()
            if 200 <= status < 400:
                return True, status, elapsed_ms, f"HEAD {status} OK"
    except urllib.error.HTTPError as e:
        # If HEAD is disallowed (405) or forbidden (403), retry with lightweight GET
        if e.code in (403, 405):
            get_headers = dict(headers)
            get_headers["Range"] = "bytes=0-1024"
            get_req = urllib.request.Request(url, headers=get_headers, method="GET")
            try:
                start_get = time.perf_counter()
                with urllib.request.urlopen(get_req, timeout=timeout, context=ssl_context) as resp2:
                    elapsed_ms = round((time.perf_counter() - start_get) * 1000, 1)
                    status2 = resp2.getcode()
                    if 200 <= status2 < 400 or status2 == 206:
                        return True, status2, elapsed_ms, f"GET fallback {status2} OK"
            except urllib.error.HTTPError as e2:
                elapsed_ms = round((time.perf_counter() - start_time) * 1000, 1)
                return False, e2.code, elapsed_ms, f"HTTP Error {e2.code}"
            except Exception as e2:
                return False, None, None, f"GET fallback error: {type(e2).__name__}"
        else:
            elapsed_ms = round((time.perf_counter() - start_time) * 1000, 1)
            return False, e.code, elapsed_ms, f"HTTP Error {e.code}"
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", str(e))
        return False, None, None, f"Connection Failed: {reason}"
    except TimeoutError:
        return False, None, None, f"Timed out after {timeout}s"
    except Exception as e:
        return False, None, None, f"Probe Error: {type(e).__name__}"

    return False, None, None, "Unknown response state"


def check_single_provider(provider: dict, timeout: float = 15.0) -> dict:
    """Test one provider and return updated record."""
    item = dict(provider)
    url = item.get("baseUrl", "").strip()
    name = item.get("name", "Unknown")

    if not url:
        item["enabled"] = False
        item["status"] = "down"
        item["last_error"] = "Empty baseUrl"
        return item

    # Prepare headers
    headers = {
        "User-Agent": item.get("userAgent") or DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"
    }
    if item.get("requiresReferer"):
        headers["Referer"] = url

    is_alive, http_code, latency_ms, detail = probe_endpoint(url, headers, timeout=timeout)
    now_iso = datetime.now(timezone.utc).isoformat()

    item["last_checked"] = now_iso
    item["last_http_code"] = http_code
    item["status_detail"] = detail

    # Historical stats
    total_checks = int(item.get("total_checks", 0)) + 1
    successful_checks = int(item.get("successful_checks", 0))
    consecutive_failures = int(item.get("consecutive_failures", 0))

    if is_alive:
        item["status"] = "up"
        item["enabled"] = True
        item["response_time_ms"] = latency_ms
        successful_checks += 1
        item["consecutive_failures"] = 0
    else:
        item["status"] = "down"
        item["enabled"] = False
        item["consecutive_failures"] = consecutive_failures + 1

    item["total_checks"] = total_checks
    item["successful_checks"] = successful_checks
    item["uptime_percentage"] = round((successful_checks / total_checks) * 100, 1)

    return item


def rerank_providers(providers: list) -> list:
    """
    Sort providers:
    1. Enabled providers first.
    2. Highest uptime percentage.
    3. Lowest response latency (fastest first).
    4. Lowest consecutive failures.
    Reassign priority sequentially (1..N).
    """
    def sort_key(p):
        is_enabled = 0 if p.get("enabled", False) else 1
        uptime = -float(p.get("uptime_percentage", 0.0))
        latency = float(p.get("response_time_ms") or 999999.0)
        failures = int(p.get("consecutive_failures", 0))
        return (is_enabled, uptime, latency, failures)

    sorted_list = sorted(providers, key=sort_key)
    for idx, p in enumerate(sorted_list, start=1):
        p["priority"] = idx
    return sorted_list


def detect_meaningful_changes(before: list, after: list) -> tuple[bool, list[str]]:
    """Determine whether significant state changes occurred."""
    reasons = []
    before_map = {p.get("name"): p for p in before}
    after_map = {p.get("name"): p for p in after}

    # Check additions / deletions
    added = set(after_map) - set(before_map)
    removed = set(before_map) - set(after_map)
    if added:
        reasons.append(f"Added providers: {', '.join(added)}")
    if removed:
        reasons.append(f"Removed providers: {', '.join(removed)}")

    # Check status and enabled toggles
    for name, p_after in after_map.items():
        p_before = before_map.get(name)
        if not p_before:
            continue
        if p_before.get("enabled") != p_after.get("enabled"):
            state = "ENABLED" if p_after.get("enabled") else "DISABLED"
            reasons.append(f"{name} toggled to {state}")
        elif p_before.get("status") != p_after.get("status"):
            reasons.append(f"{name} status changed to {p_after.get('status')}")

    # Check ordering / rank changes
    before_order = [p.get("name") for p in before]
    after_order = [p.get("name") for p in after]
    if before_order != after_order and not reasons:
        reasons.append("Provider priority order re-ranked by speed/reliability")

    return (len(reasons) > 0, reasons)


def generate_markdown_report(providers: list, reasons: list[str]) -> str:
    """Produce GitHub-flavored Markdown report for job summaries."""
    total = len(providers)
    enabled_count = sum(1 for p in providers if p.get("enabled"))
    disabled_count = total - enabled_count

    latencies = [p["response_time_ms"] for p in providers if p.get("response_time_ms")]
    avg_latency = round(sum(latencies) / len(latencies), 1) if latencies else 0.0

    lines = []
    lines.append("# 🎬 Watchly Streaming Providers Health Report")
    lines.append("")
    lines.append(f"> **Automated Self-Healing Check** at `{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}`")
    lines.append("")

    # Status Badges
    badge_color = "brightgreen" if disabled_count == 0 else ("yellow" if enabled_count > 0 else "red")
    lines.append(f"![Provider Health](https://img.shields.io/badge/Providers-{enabled_count}%2F{total}%20Active-{badge_color}) "
                 f"![Avg Latency](https://img.shields.io/badge/Avg%20Speed-{avg_latency}ms-blue)")
    lines.append("")

    # Summary table
    lines.append("### 📊 Overview")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("| :--- | :--- |")
    lines.append(f"| **Total Configured Providers** | `{total}` |")
    lines.append(f"| **🟢 Active / Playable** | `{enabled_count}` |")
    lines.append(f"| **🔴 Disabled / Unhealthy** | `{disabled_count}` |")
    lines.append(f"| **⚡ Average Response Latency** | `{avg_latency} ms` |")
    lines.append("")

    if reasons:
        lines.append("### 🔄 Self-Healing Actions Taken")
        lines.append("")
        for r in reasons:
            lines.append(f"- {r}")
        lines.append("")
    else:
        lines.append("### ✅ Health State Stable")
        lines.append("All provider states remained consistent with previous check.")
        lines.append("")

    # Detailed Table
    lines.append("### 🌐 Provider Rankings & Status")
    lines.append("")
    lines.append("| Priority | Status | Provider | Type | Response Time | Uptime | Base URL |")
    lines.append("| :---: | :---: | :--- | :---: | :---: | :---: | :--- |")

    for p in providers:
        status_icon = "🟢 UP" if p.get("enabled") else "🔴 DOWN"
        latency_str = f"`{p.get('response_time_ms', '-')} ms`" if p.get("response_time_ms") else "`Timeout`"
        uptime_val = p.get("uptime_percentage", 100.0)
        lines.append(
            f"| **#{p.get('priority')}** | {status_icon} | **{p.get('name')}** | `{p.get('type', 'EMBED')}` | "
            f"{latency_str} | `{uptime_val}%` | `{p.get('baseUrl')}` |"
        )
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Watchly Streaming Provider Health Check")
    parser.add_argument("--file", "-f", help="Path to providers.json")
    parser.add_argument("--timeout", "-t", type=float, default=15.0, help="Per-request timeout (seconds)")
    parser.add_argument("--workers", "-w", type=int, default=10, help="Concurrent probe threads")
    parser.add_argument("--report", "-r", help="Path to output markdown report (e.g. $GITHUB_STEP_SUMMARY)")
    parser.add_argument("--exit-zero", action="store_true", help="Always exit with code 0 regardless of changes")
    args = parser.parse_args()

    target_path = find_providers_json(args.file)
    print(f"[INFO] Using providers file: {target_path}")

    providers_before = load_providers(target_path)
    print(f"[INFO] Probing {len(providers_before)} providers with {args.workers} workers (timeout={args.timeout}s)...")

    # Run probes in parallel
    updated_providers = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        future_to_provider = {
            executor.submit(check_single_provider, p, args.timeout): p
            for p in providers_before
        }
        for future in concurrent.futures.as_completed(future_to_provider):
            try:
                res = future.result()
                updated_providers.append(res)
            except Exception as e:
                p_orig = future_to_provider[future]
                print(f"[ERROR] Failed checking {p_orig.get('name')}: {e}")
                item_copy = dict(p_orig)
                item_copy["enabled"] = False
                item_copy["status"] = "down"
                updated_providers.append(item_copy)

    # Re-rank based on reliability and speed
    final_providers = rerank_providers(updated_providers)

    # Detect changes
    has_changes, change_reasons = detect_meaningful_changes(providers_before, final_providers)

    # Always write current stats back to providers.json
    target_path.parent.mkdir(parents=True, exist_ok=True)
    with open(target_path, "w", encoding="utf-8") as f:
        json.dump(final_providers, f, indent=2)
    print(f"[SUCCESS] Updated {target_path} successfully.")

    # Generate Markdown Report
    report_md = generate_markdown_report(final_providers, change_reasons)

    if args.report:
        report_path = Path(args.report)
        try:
            report_path.parent.mkdir(parents=True, exist_ok=True)
            with open(report_path, "a", encoding="utf-8") as f:
                f.write(report_md + "\n")
            print(f"[INFO] Markdown report written to {report_path}")
        except Exception as e:
            print(f"[WARN] Failed writing report to {report_path}: {e}")

    # Output to stdout summary
    print("\n" + "=" * 60)
    print("WATCHLY PROVIDER HEALTH CHECK SUMMARY")
    print("=" * 60)
    for p in final_providers:
        status_str = "UP  " if p.get("enabled") else "DOWN"
        latency = f"{p.get('response_time_ms')}ms" if p.get("response_time_ms") else "N/A"
        print(f"#{p.get('priority'):<2} [{status_str}] {p.get('name'):<16} Latency: {latency:<8} Uptime: {p.get('uptime_percentage')}%")
    print("=" * 60)

    if has_changes:
        print(f"[INFO] Changes detected ({len(change_reasons)} events):")
        for r in change_reasons:
            print(f"  - {r}")
        if not args.exit_zero:
            print("[INFO] Exiting with code 1 (changes detected for commit).")
            sys.exit(1)
    else:
        print("[INFO] No state changes detected.")

    sys.exit(0)


if __name__ == "__main__":
    main()
