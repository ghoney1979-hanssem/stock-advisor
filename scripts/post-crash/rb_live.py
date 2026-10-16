"""폭락 후 진입 — 라이브 청산 규칙(PositionExitService.simulateMultidayExit 포팅)으로 유니버스 동일가중 net 측정.
prod: arm 5 / drop 2 / maxHold 60 / stop 12 / limitUp 29. 비용 0.22%.
"""
import gzip, sys, csv
from array import array
from collections import defaultdict

ARM, DROP, MAXH, STOP, LIMUP, COST = 5.0, 2.0, 60, 12.0, 29.0, 0.22
path = sys.argv[1]

stocks = {}
cur = None
with gzip.open(path, 'rt') as f:
    for row in csv.reader(f):
        code = row[0]
        if code != cur:
            cur = code
            st = stocks[code] = [array('l'), array('q'), array('q'), array('q'), array('q'), array('q')]
        st[0].append(int(row[1])); st[1].append(int(row[2])); st[2].append(int(row[3]))
        st[3].append(int(row[4])); st[4].append(int(row[5])); st[5].append(int(row[6]))
print('loaded', len(stocks), file=sys.stderr)

def eligible(st, i):
    if i < 1: return None
    pc, c, v = st[4][i-1], st[4][i], st[5][i]
    if pc < 1000 or c <= 0 or c * v < 5e8: return None
    r = c / pc - 1
    if abs(r) >= 0.29: return None   # 상한가·하한가 근접 종가는 체결 불가로 제외
    return r

# 1) 유니버스 일별 평균 등락률
acc = defaultdict(lambda: [0.0, 0])
for st in stocks.values():
    for i in range(1, len(st[0])):
        r = eligible(st, i)
        if r is None: continue
        a = acc[st[0][i]]; a[0] += r; a[1] += 1
days = sorted(d for d, a in acc.items() if a[1] >= 300 and d >= 20160101)
ret = {d: acc[d][0] / acc[d][1] * 100 for d in days}

cls = defaultdict(list)
for k, d in enumerate(days):
    r = ret[d]
    p3 = [ret[days[j]] for j in range(max(0, k-3), k)]
    m3 = min(p3) if p3 else 0
    p1 = p3[-1] if p3 else 0
    if k % 10 == 0: cls['A 기준(10일마다 표본)'].append(d)
    if r <= -3: cls['B 폭락일 종가'].append(d)
    if p1 <= -3 and r >= 1: cls['C 폭락 익일 반등(+1↑)'].append(d)
    if m3 <= -3 and r >= 1: cls['E 3일내 폭락 후 반등(+1↑)'].append(d)
    if m3 <= -3 and r < 0: cls['F 3일내 폭락 후 하락지속'].append(d)
    if m3 <= -3 or r <= -3: cls['G 폭락창 전체(당일~3일)'].append(d)
want = set(d for v in cls.values() for d in v)

def sim(st, i):
    """simulateMultidayExit 포팅 — 반환 (청산가, 보유일, 사유)"""
    buy = st[4][i]; peak = buy; prev = buy; last = None
    n = len(st[0])
    for h in range(1, MAXH + 1):
        j = i + h
        if j >= n: break
        o, hi, lo, c = st[1][j], st[2][j], st[3][j], st[4][j]
        if c <= 0: continue
        last = (c, h, 'end')
        armed = peak >= buy * (1 + ARM / 100)
        if lo > 0:
            if armed:
                lvl = round(peak * (1 - DROP / 100))
                if lo <= lvl: return (min(o, lvl) if o > 0 else lvl, h, 'trail')
            if lo <= buy * (1 - STOP / 100):
                lvl = round(buy * (1 - STOP / 100))
                return (min(o, lvl) if o > 0 else lvl, h, 'stop')
        if hi > 0 and prev > 0:
            lvl = round(prev * (1 + LIMUP / 100))
            if hi >= lvl and lvl > buy: return (max(o, lvl) if o > 0 else lvl, h, 'limup')
        peak = max(peak, hi if hi > 0 else c)
        if c <= buy * (1 - STOP / 100): return (c, h, 'stop')
        armed = peak >= buy * (1 + ARM / 100)
        if armed and c <= peak * (1 - DROP / 100): return (c, h, 'trail')
        if h >= MAXH: return (c, h, 'maxhold')
        prev = c
    return last

# 2) 이벤트일별 동일가중 집계
day_stats = {}   # d -> (avg net live, avg hold15 net, avg held, stop%, trail%, n)
idx_cache = {}
for code, st in stocks.items():
    dates = st[0]
    pos = {d: i for i, d in enumerate(dates) if d in want}
    for d, i in pos.items():
        if eligible(st, i) is None: continue
        e = sim(st, i)
        if e is None: continue
        buy = st[4][i]
        live = (e[0] / buy - 1) * 100 - COST
        j15 = min(i + 15, len(dates) - 1)
        h15 = (st[4][j15] / buy - 1) * 100 - COST if j15 > i else None
        s = day_stats.setdefault(d, [0.0, 0.0, 0, 0, 0, 0, 0])
        s[0] += live; s[2] += e[1]; s[3] += e[2] == 'stop'; s[4] += e[2] == 'trail'; s[5] += 1
        if h15 is not None: s[1] += h15; s[6] += 1

last_day = days[-1]
print(f'기준 최종일 {last_day}  (진입일 +60거래일 미도달 표본은 마지막 관측 종가로 평가)')
print(f"{'구분':<26}{'일수':>5}{'live net':>10}{'승일%':>7}{'15일보유':>10}{'평균보유':>8}{'손절%':>7}{'트레일%':>8}")
for name in sorted(cls):
    ds = [d for d in cls[name] if d in day_stats and day_stats[d][5] >= 100]
    if not ds: continue
    lv = [day_stats[d][0] / day_stats[d][5] for d in ds]
    hv = [day_stats[d][1] / day_stats[d][6] for d in ds if day_stats[d][6]]
    held = sum(day_stats[d][2] / day_stats[d][5] for d in ds) / len(ds)
    stp = sum(day_stats[d][3] / day_stats[d][5] for d in ds) / len(ds) * 100
    trl = sum(day_stats[d][4] / day_stats[d][5] for d in ds) / len(ds) * 100
    print(f"{name:<26}{len(ds):>5}{sum(lv)/len(lv):>10.2f}{100*sum(x>0 for x in lv)/len(lv):>7.0f}"
          f"{sum(hv)/len(hv):>10.2f}{held:>8.1f}{stp:>7.1f}{trl:>8.1f}")

print('\n연도별 live net (E 반등일 / G 폭락창 / A 기준)')
for y in range(2016, 2027):
    row = []
    for name in ('E 3일내 폭락 후 반등(+1↑)', 'G 폭락창 전체(당일~3일)', 'A 기준(10일마다 표본)'):
        ds = [d for d in cls[name] if d // 10000 == y and d in day_stats and day_stats[d][5] >= 100]
        v = [day_stats[d][0] / day_stats[d][5] for d in ds]
        row.append(f"{sum(v)/len(v):7.2f}(n={len(v):2d})" if v else '      -      ')
    print(y, '  '.join(row))

print('\nE 반등일 개별 (최근 20)')
for d in [d for d in cls['E 3일내 폭락 후 반등(+1↑)'] if d in day_stats][-20:]:
    s = day_stats[d]
    print(d, f"ret {ret[d]:+.2f}  live {s[0]/s[5]:+.2f}  hold15 {s[1]/max(s[6],1):+.2f}  held {s[2]/s[5]:.1f}  stop {100*s[3]/s[5]:.0f}%  n={s[5]}")
