#!/usr/bin/env sh
# samsara Web API 薄封装（bash/sh）
# 用法：
#   export SAMSARA_URL='http://127.0.0.1:8720'
#   export SAMSARA_TOKEN='...'
#   ./samsara.sh GET /api/status
#   ./samsara.sh POST /api/announcements/preview '{"id":"welcome"}'

set -eu

BASE="${SAMSARA_URL:-http://127.0.0.1:8720}"
BASE="${BASE%/}"
: "${SAMSARA_TOKEN:?SAMSARA_TOKEN 未设置}"

METHOD="${1:?用法: samsara.sh <METHOD> <PATH> [JSON_BODY]}"
PATH_PART="${2:?用法: samsara.sh <METHOD> <PATH> [JSON_BODY]}"
BODY="${3:-}"

if [ -n "$BODY" ]; then
  curl -sS -X "$METHOD" "$BASE$PATH_PART" \
    -H "Authorization: Bearer $SAMSARA_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$BODY"
else
  curl -sS -X "$METHOD" "$BASE$PATH_PART" \
    -H "Authorization: Bearer $SAMSARA_TOKEN"
fi
