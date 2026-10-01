#!/bin/bash
# 선정 품질 사전등록 점검 — 1회성(2026-10-16 17:20 KST). 결과를 Discord로 보내고 자기 크론을 지운다.
# 규칙·판정 기준: 리포지토리 scripts/selection-quality/prereg_check.py (컷 변경 금지)
set -u
D=/opt/prereg
LOG=$D/run-$(date -u +%Y%m%d%H%M).log
{
  echo "📋 선정 품질 사전등록 점검 (표본 밖: 진입일 ≥ 20260916, D+10 유니버스 대비)"
  echo "수급 소급: $(curl -s -m 900 -X POST localhost:8080/api/v1/admin/backfill-investor-flow)"
  docker cp $D/export.sql sa-postgres:/tmp/export.sql
  docker exec sa-postgres psql -U stockadvisor -d stockadvisor -q -f /tmp/export.sql
  docker cp sa-postgres:/tmp/ds.csv $D/ds.csv
  python3 $D/prereg_check.py $D/ds.csv
  echo "PASS만 적용 검토 — Claude에게 '선정 품질 점검 결과 보자'로 이어갈 것"
} > "$LOG" 2>&1
if [ "${NOPOST:-0}" != "1" ]; then
  WH=$(grep '^DISCORD_WEBHOOK_URL=' /home/user/stock-advisor/.env | cut -d= -f2- | tr -d '"'"'"'\r')
  python3 - "$LOG" "$WH" <<'PY'
import json, sys, urllib.request
text = open(sys.argv[1], encoding='utf-8').read()
body = json.dumps({"content": "```\n" + text[:1880] + "\n```"}).encode()
req = urllib.request.Request(sys.argv[2], data=body, headers={"Content-Type": "application/json", "User-Agent": "stock-advisor-prereg"})
urllib.request.urlopen(req, timeout=15)
PY
  rm -f /etc/cron.d/prereg-check
fi
