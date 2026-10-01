"""선정 품질 사전등록 규칙의 포워드 검증 (2026-10-01 등록).

사용: python prereg_check.py ds.csv
  ds.csv = export.sql 산출물. **표본 밖(alert_date >= OOS_FROM) 데이터만** 판정에 쓴다.

⚠️ 규칙·임계·판정 기준은 2026-10-01에 표본 내(alert_date <= 20260915) 탐색으로 정했다.
   결과를 본 뒤 임계를 바꾸면 사전등록이 무의미해진다 — 바꾸려면 새 규칙으로 다시 등록하고 다시 기다릴 것.
"""
import csv
import collections
import statistics as st
import sys

OOS_FROM = '20260916'   # 표본 내 D+10 데이터의 마지막 진입일이 20260915
KEY = 'x10'             # 주지표: 진입일 종가 → D+10 종가, 유니버스 동일가중 대비 초과수익(gross)

# (이름, 전략, 특성, 하위 컷(<=), 상위 컷(>=), 표본 내 상위−하위(%p), 실행안)
RULES = [
    ('L 시총',       'REVERSAL_L',       'cap', 3975,  15400, -5.91, 'L: 시총 >= 1.5조 진입 제외'),
    ('L 체결강도',   'REVERSAL_L',       'ex',  68.82, 99.18, -2.09, 'L: 체결강도 >= 99 진입 제외'),
    ('L OBI',        'REVERSAL_L',       'obi', 11.22, 54.52, +3.10, 'L: 호가불균형 하위 진입 제외'),
    ('G 당일등락',   'RSI_REVERSAL_G',   'chg', 1.79,  5.07,  +3.53, 'G: 당일등락 <= 1.8% 진입 제외'),
    ('G 거래량배수', 'RSI_REVERSAL_G',   'vr',  0.84,  1.50,  +3.04, 'G: 거래량배수 <= 0.84 진입 제외'),
    ('G 외인순매수', 'RSI_REVERSAL_G',   'fr',  -3.36, 11.65, +2.29, 'G: 외인 순매도 진입 제외'),
    ('D 외인순매수', 'INDEX_RELATIVE_D', 'fr',  -8.93, 6.23,  +1.93, 'D: 외인 순매도 진입 제외'),
    ('J 거래량배수', 'VALUE_REVERSAL_J', 'vr',  0.94,  1.63,  +5.11, 'J: 거래량배수 <= 0.94 진입 제외'),
    ('J PBR',        'VALUE_REVERSAL_J', 'pbr', 0.64,  1.26,  +7.89, 'J: PBR <= 0.64 진입 제외'),
]
# 판정(사전등록): 표본 밖에서 ① 같은 날 매칭 거래일 >= 8 ② 부호가 표본 내와 같고 크기 >= 표본 내의 1/3
#                ③ 하루씩 빼도(LOO) 부호 유지 ④ 같은 부호인 날이 과반
MIN_DAYS = 8


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def load(path):
    rows = list(csv.DictReader(open(path, encoding='utf-8')))
    for r in rows:
        c0 = num(r['c0'])
        for k in ('5', '10', '15'):
            ck, u = num(r['c' + k]), num(r['u' + k])
            r['x' + k] = (ck / c0 - 1) * 100 - u if (ck and c0 and u is not None) else None
    return rows


def day_diffs(lo, hi):
    a, b = collections.defaultdict(list), collections.defaultdict(list)
    for r in lo:
        if r[KEY] is not None:
            a[r['alert_date']].append(r[KEY])
    for r in hi:
        if r[KEY] is not None:
            b[r['alert_date']].append(r[KEY])
    return {d: st.mean(b[d]) - st.mean(a[d]) for d in a if d in b}


def main(path):
    rows = [r for r in load(path) if r['c'] == 'f' and r['alert_date'] >= OOS_FROM]
    print(f'표본 밖 진입분(alert_date >= {OOS_FROM}, {KEY} 가용): '
          f'{sum(1 for r in rows if r[KEY] is not None)}건')
    for name, strat, feat, lo_cut, hi_cut, ins, action in RULES:
        e = [r for r in rows if r['strategy'] == strat]
        lo = [r for r in e if num(r[feat]) is not None and num(r[feat]) <= lo_cut]
        hi = [r for r in e if num(r[feat]) is not None and num(r[feat]) >= hi_cut]
        dd = day_diffs(lo, hi)
        if len(dd) < MIN_DAYS:
            print(f'{name:10} 판정보류 — 매칭 거래일 {len(dd)} < {MIN_DAYS} (하위 {len(lo)}·상위 {len(hi)}건)')
            continue
        m = st.mean(dd.values())
        loo = [st.mean(v for d2, v in dd.items() if d2 != d) for d in dd]
        same = sum(1 for v in dd.values() if (v > 0) == (ins > 0))
        ok = ((m > 0) == (ins > 0) and abs(m) >= abs(ins) / 3
              and all((x > 0) == (ins > 0) for x in loo) and same * 2 > len(dd))
        print(f'{name:10} {"PASS" if ok else "FAIL"}  표본밖 {m:+.2f}%p (표본내 {ins:+.2f}) '
              f'LOO[{min(loo):+.2f},{max(loo):+.2f}] 같은부호 {same}/{len(dd)}일 → {action}')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'ds.csv')
