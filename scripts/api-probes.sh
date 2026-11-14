#!/usr/bin/env bash
# =============================================================================
# Watchly — weekly API probes (cycle 6).
#
# Probes every live source Watchly uses, checking reachability and the
# basic response shape the app depends on. Prints one compact table.
#
# Usage:
#   ./scripts/api-probes.sh
#
# Optional keys (read from the environment ONLY — never printed, never
# committed; without them the key-gated APIs are probed unauthenticated
# and an auth-error response with the right shape counts as reachable):
#   TMDB_API_KEY  OMDB_API_KEY  TRAKT_CLIENT_ID
#   OPENSUBTITLES_API_KEY  FANART_API_KEY
#
# Result column:
#   PASS        endpoint answered with the expected status + shape
#   PASS (auth) no key in env; the API answered with its auth error in
#               the expected shape, so it is reachable and behaving
#   BLOCKED     the probing network itself is refused (e.g. a
#               Cloudflare IP block) — a property of where the probe
#               runs, not of the API; reported loudly, not a failure
#   FAIL        anything else (unreachable, wrong status, wrong shape)
#
# Exit code: 0 when nothing FAILs, 1 otherwise.
# =============================================================================
set -u

MAX_TIME=45
UA_BROWSER="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
YOUTUBE_API_KT="$SCRIPT_DIR/../app/src/main/java/com/watchly/app/data/remote/YouTubeApi.kt"

FAILURES=0
ROWS=""

# fetch <url> [extra curl args...] -> sets STATUS and BODY
fetch() {
    local url="$1"; shift
    BODY="$(curl -s --max-time "$MAX_TIME" -w $'\n%{http_code}' "$@" "$url" 2>/dev/null)"
    STATUS="${BODY##*$'\n'}"
    BODY="${BODY%$'\n'*}"
    if [ -z "$STATUS" ]; then STATUS="000"; fi
}

# record <source> <result> <detail>
record() {
    ROWS+="$(printf '%-28s %-11s %s' "$1" "$2" "$3")\n"
    if [ "$2" = "FAIL" ]; then FAILURES=$((FAILURES + 1)); fi
}

# check <source> <status> <body> <ok-statuses> <required-substring> [auth-note]
check() {
    local source="$1" status="$2" body="$3" ok="$4" needle="$5"
    if [ "$status" = "000" ]; then
        record "$source" "FAIL" "unreachable (curl timeout/error)"
        return
    fi
    case " $ok " in
        *" $status "*)
            if [ -n "$needle" ] && ! printf '%s' "$body" | grep -qF "$needle"; then
                record "$source" "FAIL" "HTTP $status but body lacks '$needle'"
            else
                record "$source" "${6:-PASS}" "HTTP $status"
            fi
            ;;
        *)
            record "$source" "FAIL" "HTTP $status (expected: $ok)"
            ;;
    esac
}

echo "Watchly API probes — $(date -u '+%Y-%m-%d %H:%M UTC')"
echo

# --- TMDB (key-gated; no-key shape = 401 JSON status_message) ---------------
if [ -n "${TMDB_API_KEY:-}" ]; then
    fetch "https://api.themoviedb.org/3/configuration?api_key=${TMDB_API_KEY}"
    check "TMDB (key)" "$STATUS" "$BODY" "200" '"images"'
else
    fetch "https://api.themoviedb.org/3/configuration"
    check "TMDB (no key)" "$STATUS" "$BODY" "401" '"status_message"' "PASS (auth)"
fi

# --- TVmaze (open) -----------------------------------------------------------
fetch "https://api.tvmaze.com/shows/1"
check "TVmaze" "$STATUS" "$BODY" "200" '"name"'

# --- AniList (open GraphQL) --------------------------------------------------
BODY="$(curl -s --max-time "$MAX_TIME" -w $'\n%{http_code}' \
    -H 'Content-Type: application/json' \
    -d '{"query":"{ Page(perPage: 1) { media(type: ANIME) { title { romaji } } } }"}' \
    "https://graphql.anilist.co" 2>/dev/null)"
STATUS="${BODY##*$'\n'}"; BODY="${BODY%$'\n'*}"
if [ -z "$STATUS" ]; then STATUS="000"; fi
if [ "$STATUS" = "403" ] && printf '%s' "$BODY" | grep -qi 'cloudflare'; then
    # AniList sits behind Cloudflare, which blocks some datacenter IPs
    # outright (this build VM included — the app itself works fine
    # from a phone). That's the probing network being refused, not
    # an API outage: report it distinctly, don't fail the run.
    record "AniList" "BLOCKED" "HTTP 403 Cloudflare IP block on this network"
else
    check "AniList" "$STATUS" "$BODY" "200" '"romaji"'
fi

# --- Internet Archive (open) -------------------------------------------------
fetch "https://archive.org/advancedsearch.php?q=mediatype%3Amovies&rows=1&output=json"
check "Internet Archive" "$STATUS" "$BODY" "200" '"numFound"'

# --- YouTube RSS (open; channels parsed from the app's curated list) ---------
if [ -f "$YOUTUBE_API_KT" ]; then
    while IFS= read -r cid; do
        fetch "https://www.youtube.com/feeds/videos.xml?channel_id=${cid}" -A "$UA_BROWSER"
        check "YouTube RSS ${cid:0:12}…" "$STATUS" "$BODY" "200" '<feed'
    done < <(grep -o 'channelId = "UC[A-Za-z0-9_-]*"' "$YOUTUBE_API_KT" | sed 's/.*"\([^"]*\)"/\1/')
else
    record "YouTube RSS" "FAIL" "YouTubeApi.kt not found next to this script"
fi

# --- Trakt (key-gated; Cloudflare bans the default curl UA, so send a
# --- browser UA exactly like the app/connector do) ---------------------------
TRAKT_ARGS=(-H "trakt-api-version: 2" -A "$UA_BROWSER")
if [ -n "${TRAKT_CLIENT_ID:-}" ]; then
    fetch "https://api.trakt.tv/movies/trending" "${TRAKT_ARGS[@]}" -H "trakt-api-key: ${TRAKT_CLIENT_ID}"
    check "Trakt (key)" "$STATUS" "$BODY" "200" '"watchers"'
else
    fetch "https://api.trakt.tv/movies/trending" "${TRAKT_ARGS[@]}"
    check "Trakt (no key)" "$STATUS" "$BODY" "400 401 403" '' "PASS (auth)"
fi

# --- OMDb (key-gated; no-key shape = 401 "Invalid API key!") -----------------
if [ -n "${OMDB_API_KEY:-}" ]; then
    fetch "https://www.omdbapi.com/?i=tt0468569&apikey=${OMDB_API_KEY}"
    check "OMDb (key)" "$STATUS" "$BODY" "200" '"Response":"True"'
else
    fetch "https://www.omdbapi.com/?i=tt0468569"
    check "OMDb (no key)" "$STATUS" "$BODY" "401" 'No API key provided' "PASS (auth)"
fi

# --- OpenSubtitles (key-gated; terms require the app User-Agent) -------------
OS_ARGS=(-H "User-Agent: Watchly v1.0")
if [ -n "${OPENSUBTITLES_API_KEY:-}" ]; then
    fetch "https://api.opensubtitles.com/api/v1/infos/user" "${OS_ARGS[@]}" -H "Api-Key: ${OPENSUBTITLES_API_KEY}"
    check "OpenSubtitles (key)" "$STATUS" "$BODY" "200" '"data"'
else
    fetch "https://api.opensubtitles.com/api/v1/infos/user" "${OS_ARGS[@]}"
    check "OpenSubtitles (no key)" "$STATUS" "$BODY" "401 403" '' "PASS (auth)"
fi

# --- Fanart.tv (key-gated; 155 = The Dark Knight, Fanart's example id) -------
if [ -n "${FANART_API_KEY:-}" ]; then
    fetch "https://webservice.fanart.tv/v3/movies/155?api_key=${FANART_API_KEY}"
    check "Fanart (key)" "$STATUS" "$BODY" "200" '"name"'
else
    fetch "https://webservice.fanart.tv/v3/movies/155"
    check "Fanart (no key)" "$STATUS" "$BODY" "401" '' "PASS (auth)"
fi

printf '%-28s %-11s %s\n' "SOURCE" "RESULT" "DETAIL"
printf '%b' "$ROWS"

if [ "$FAILURES" -gt 0 ]; then
    echo
    echo "$FAILURES probe(s) FAILED"
    exit 1
fi
echo
echo "All probes passed."
