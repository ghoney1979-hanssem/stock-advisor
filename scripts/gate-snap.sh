#!/bin/bash
# 게이트 표본(n) 시점별 스냅샷 — 2026-09-28 진단용(임시).
#
# 왜: 같은 버킷(KOSDAQ·강세)의 게이트 표본이 같은 날 09:02 n=79·net +1.09% → 16:21 n=41·net +4.11%로
#     바뀐 원인을 못 밝혔다. "표본이 줄었나(마크/반사실)" vs "판정 경로가 fallback→엄격으로 전환됐나"를
#     가리려면 하루 여러 시점의 국면별 n이 필요하다.
#
# 🔴 strategy-gate 가 아니라 strategy-gate-by-regime 을 쓴다:
#    전자는 조회만 해도 히스테리시스 openState 를 갱신하는 부작용이 있어(CLAUDE.md 기록) 하루 6번이면
#    실매매 판정에 영향이 갈 수 있다. 후자는 시뮬이라 KOSPI/KOSDAQ 상태를 건드리지 않고(INVERSE 줄만 갱신)
#    국면 3종(BULL/NEUTRAL/BEAR)의 n을 모두 주므로 경로 전환과 표본 감소를 분리할 수 있다.
#
# 설치(VM): ⚠️ Windows 체크아웃에서 scp 하면 CRLF가 섞여 "bad interpreter"로 죽는다 — sed 를 먼저 돌릴 것.
#           sudo sed -i 's/\r$//' gate-snap.sh gate-snap.cron
#           sudo install -m 755 gate-snap.sh /usr/local/bin/gate-snap.sh
#           sudo install -m 644 gate-snap.cron /etc/cron.d/gate-snap
# 제거:     sudo rm /etc/cron.d/gate-snap /usr/local/bin/gate-snap.sh
OUT=/home/user/stock-advisor/logs/gate-snap.jsonl
/usr/bin/python3 - >> "$OUT" 2>>/home/user/stock-advisor/logs/gate-snap.err <<'PY'
import json, urllib.request, datetime
B = "http://localhost:8080/api/v1/admin/"
def get(p, t=150):
    try:
        with urllib.request.urlopen(B + p, timeout=t) as r:
            return json.load(r)
    except Exception as e:
        return {"error": str(e)}
g = get("strategy-gate-by-regime")
gate = [[x["strategy"], x["market"], x.get("regimeTrend"), x.get("samples"),
         x.get("netAvgReturnPct"), x.get("allowed")] for x in g] if isinstance(g, list) else g
m = get("multiday-marks", 90)
marks = [[x["strategy"], x["rows"], x["outcomes"], x.get("maxMarkDays")] for x in m] if isinstance(m, list) else m
r = get("market-regime", 30)
regime = [[x["market"], x.get("trend"), x.get("volatility"), x.get("asOf")] for x in r] if isinstance(r, list) else r
print(json.dumps({
    "ts": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "regime": regime,
    "status": get("daily-history-status", 30),
    "risk": get("risk-status", 30),
    "gate": gate,
    "marks": marks,
}, ensure_ascii=False))
PY
