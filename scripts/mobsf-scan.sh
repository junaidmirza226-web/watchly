#!/usr/bin/env bash
# =============================================================================
# Watchly — MobSF APK scan
#
# Uploads a built APK to a LOCAL / SELF-HOSTED MobSF instance via its REST
# API, runs a static scan, and saves the JSON (+ PDF if available) reports.
# Nothing is sent to any third-party service: MobSF_URL defaults to localhost.
#
# Usage:
#   export MOBSF_API_KEY="<key from MobSF UI>"
#   ./mobsf-scan.sh path/to/app-release.apk
#
# Environment:
#   MOBSF_API_KEY   (required) API key from the MobSF web UI.
#                   NEVER hardcode it here, NEVER commit it. The script
#                   refuses to run without it.
#   MOBSF_URL       (optional) Base URL of your MobSF instance.
#                   Default: http://127.0.0.1:8000
#   MOBSF_OUT_DIR   (optional) Where reports are written.
#                   Default: ./mobsf-results/<YYYYMMDD-HHMMSS>
#
# Exit codes: 0 = scan completed and reports saved.
#             Non-zero = something failed (no key, MobSF unreachable,
#             upload/scan/report error). Reports already downloaded are kept.
#
# Requires: curl, and python3 OR jq (for pretty-printing / hash extraction).
# API reference: MobSF REST API v1 — /api/v1/upload, /api/v1/scan,
#                /api/v1/report_json, /api/v1/download_pdf
#                Auth header:  Authorization: <API key>
# =============================================================================
set -euo pipefail

# --- Inputs ------------------------------------------------------------------
APK_PATH="${1:-}"
if [[ -z "$APK_PATH" ]]; then
    echo "Usage: MOBSF_API_KEY=... $0 path/to/app.apk" >&2
    exit 2
fi
if [[ ! -f "$APK_PATH" ]]; then
    echo "ERROR: APK not found: $APK_PATH" >&2
    exit 2
fi

MOBSF_URL="${MOBSF_URL:-http://127.0.0.1:8000}"
MOBSF_URL="${MOBSF_URL%/}" # strip trailing slash

if [[ -z "${MOBSF_API_KEY:-}" ]]; then
    echo "ERROR: MOBSF_API_KEY is not set." >&2
    echo "  Get it from the MobSF web UI (${MOBSF_URL}) and run:" >&2
    echo "    export MOBSF_API_KEY=\"<your key>\"" >&2
    exit 2
fi

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required." >&2; exit 2; }

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${MOBSF_OUT_DIR:-./mobsf-results/${TIMESTAMP}}"
mkdir -p "$OUT_DIR"

AUTH_HEADER="Authorization: ${MOBSF_API_KEY}"

# Extract a top-level string field from a JSON file: json_field <file> <key>
json_field() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2"
    elif command -v jq >/dev/null 2>&1; then
        jq -r --arg k "$2" '.[$k] // ""' "$1"
    else
        echo "ERROR: need python3 or jq to parse MobSF responses." >&2
        exit 2
    fi
}

# --- 0. Is MobSF up? ---------------------------------------------------------
echo "==> Checking MobSF at ${MOBSF_URL} ..."
if ! curl -sS -o /dev/null --max-time 10 "$MOBSF_URL/"; then
    echo "ERROR: MobSF is not reachable at ${MOBSF_URL}." >&2
    echo "  Start your local MobSF first, e.g.:" >&2
    echo "    docker run -it --rm -p 8000:8000 opensecurity/mobile-security-framework-mobsf:latest" >&2
    echo "  (or your existing self-hosted instance), then re-run." >&2
    exit 1
fi

# --- 1. Upload ---------------------------------------------------------------
echo "==> Uploading $(basename "$APK_PATH") ..."
curl -sS --fail \
    -H "$AUTH_HEADER" \
    -F "file=@${APK_PATH}" \
    "$MOBSF_URL/api/v1/upload" \
    -o "$OUT_DIR/upload.json"

HASH="$(json_field "$OUT_DIR/upload.json" hash)"
if [[ -z "$HASH" ]]; then
    echo "ERROR: upload response had no hash. Response saved at $OUT_DIR/upload.json" >&2
    echo "  Common causes: wrong API key (401), or the file is not a valid APK." >&2
    exit 1
fi
echo "    Upload OK. Scan hash: $HASH"

# --- 2. Scan -----------------------------------------------------------------
echo "==> Running static scan (this can take a few minutes) ..."
curl -sS --fail \
    -H "$AUTH_HEADER" \
    --data-urlencode "hash=${HASH}" \
    "$MOBSF_URL/api/v1/scan" \
    -o "$OUT_DIR/scan.json"
echo "    Scan finished."

# --- 3. Reports --------------------------------------------------------------
echo "==> Downloading JSON report ..."
curl -sS --fail \
    -H "$AUTH_HEADER" \
    --data-urlencode "hash=${HASH}" \
    "$MOBSF_URL/api/v1/report_json" \
    -o "$OUT_DIR/report.json"

echo "==> Downloading PDF report (best effort) ..."
if curl -sS --fail \
    -H "$AUTH_HEADER" \
    --data-urlencode "hash=${HASH}" \
    "$MOBSF_URL/api/v1/download_pdf" \
    -o "$OUT_DIR/report.pdf"; then
    echo "    PDF saved."
else
    echo "    NOTE: PDF download failed — continuing; the JSON report is the source of truth."
    rm -f "$OUT_DIR/report.pdf"
fi

# --- 4. Summary --------------------------------------------------------------
echo ""
echo "Done. Reports in: $OUT_DIR"
echo "  - upload.json   scan.json   report.json   report.pdf (if available)"
echo "  - Full interactive report: open ${MOBSF_URL} and search for hash ${HASH}"
echo ""
echo "Next: work through the checklist in CHECKLIST.md (same folder as this"
echo "script) against report.json before this build is released."
echo "Reminder: do not commit the API key, and treat report files as"
echo "sensitive — they list your app's internals. Add mobsf-results/ to"
echo ".gitignore unless your team deliberately archives scan reports."
