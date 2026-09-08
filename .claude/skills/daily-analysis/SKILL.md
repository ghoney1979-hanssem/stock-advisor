---
name: daily-analysis
description: 주식 자동매매 시스템의 일별 성과·종목선정력 분석. LIVE 실거래 손익, 멀티데이 전략의 유니버스 대비 초과수익, 종목선정 축(Sleeve 포워드 테스트 등)을 점검하고 조치를 제안한다. 인트라데이 청산타이밍·당일 net 튜닝은 다루지 않는다(2026-09-08부로 전 LIVE 전략이 멀티데이/스윙/인버스 전용 청산으로 전환돼 실효가 없다고 판단됨). 수급·체결강도·추세 같은 feature는 단일축 필터로 따로 보지 않고 복합 점수로 묶어 판정한다(2026-09-08 사용자 결정). 최근 배포된 새 엔드포인트가 있으면 자동 반영.
user-invocable: true
---

# daily-analysis

장 마감 후(또는 요청 시점) LIVE 실거래 성과와 **멀티데이 전략·종목선정력**을 점검해 조치를 제안한다.

⚠️ **2026-09-08 범위 변경(사용자 결정)**: 2026-09-07부터 전 LIVE 전략이 멀티데이(15거래일 트레일)·스윙(D+1)·
인버스 전용 청산으로 전환되면서, 그때까지 정교하게 튜닝해오던 "적응형 청산타이밍"(전략별 권장 보유시간
자동산출)이 실제 청산 어디에도 안 쓰이는 죽은 레버였음이 드러나 코드까지 삭제됐다(`StrategyHoldTimeProvider`
단순화, `ExitTimingService` 삭제, `GET /admin/exit-timing` 폐지). 같은 이유로 **이 스킬도 인트라데이 청산
메커니즘(보유시간·청산방식·손절선 시뮬·집행품질 gap·장중흐름 필터)과 당일종가 기준 net 튜닝을 정규 분석에서
뺀다** — LIVE 전략의 실제 손익은 이제 최대 15거래일에 걸쳐 나므로, 그 경제성과 무관한 당일 수치를 근거로
파라미터를 튜닝하는 게 의미가 없어졌다. **무게중심은 ① LIVE 실거래 손익 추적 ② 멀티데이 전략 성과(유니버스
대비 초과수익) ③ 종목선정력(어떤 축이 실제로 승자를 고르는가)으로 옮긴다.**

## 환경 (고정 사실 — 재탐색 불필요)

- 배포: GCP VM `stock-advisor`(zone `asia-northeast3-a`). 접근: `gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command='...'`
- 컨테이너명: 앱 `sa-app`, DB `sa-postgres`, Redis `sa-redis`. VM에서는 `sudo docker` 필요.
- 앱 API는 VM 내부 `localhost:8080`만 바인딩 → SSH command 안에서 curl.
- ⚠️ `POST /api/v1/admin/daily-report`는 **호출 금지**(Discord 중복 발송됨). 분석은 GET 엔드포인트 + DB 직접 조회로만.
- VM 소스 경로는 `/home/user/stock-advisor`(소유자 `user`, SSH 로그인 계정과 다름 — 조회·수정은 `sudo` 필수).

## Phase 1. 데이터 수집

SSH 한 번에 여러 curl을 묶어 호출(왕복 절감). 수집 대상 GET 엔드포인트(`/api/v1/admin/` prefix):

| 축 | 엔드포인트 |
|---|---|
| 게이트/국면/리스크 | `strategy-gate`, `market-regime`, `risk-status` |
| 시장 폭 | `market-breadth` |
| **멀티데이 성과(핵심)** | `multiday-exit-comparison?fullPathsOnly=true`(완주 코호트 필수 — 아래 함정 참조), `multiday-marks`(전략별 수집현황) |
| **종목선정 포워드(핵심)** | `sleeve-report`(가볍다 — DB 조회 1회, 매 실행 확인) |
| 진입필터 품질(선정 보조) | `control-analysis?horizon=close`(전략별 "진입 vs 미진입" — 그 전략의 자체 선정이 자기가 거른 것보다 나은가) |

⚠️ **더 이상 routine으로 수집하지 않는다** — 인트라데이 청산 인프라(`exit-stop`·`exit-method`·`exit-comparison`·
`heat-analysis`·`flow-analysis`·`execution-quality`·`swing-trail-analysis`·`swing-exit`·`outcome-analysis`)는
전부 "당일 인트라데이 청산 시점" 또는 그 시점까지의 net을 재는 도구인데, 지금 LIVE 화이트리스트 8개 전략은
전부 멀티데이/스윙/인버스로 청산돼 그 시점에 팔지 않는다(C조차 스윙 대상이지만 멀티데이가 우선한다). 이
엔드포인트들은 여전히 호출 가능하지만 **LIVE 손익과 연결되지 않는 진단이라 정규 출력에서 뺀다** — 특정 버그를
의심할 때만(예: "이 전략이 실제로 며칠 만에 청산됐나" 같은 구체 질문이 있을 때) ad hoc으로 쓸 것.

⚠️ **무거운 종목선정 백테스트 엔드포인트는 routine에서 제외한다** — `selection-sweep`·`value-sweep`·
`multiday-backtest`·`financial-spread`는 10년치 `daily_price`(298만 행)/`financial_fact`를 전수 스캔하는
연구용 엔드포인트다. 2026-08-28~09-07 사이 이미 충분히 탐색됐다 — 가격·거래량 8축(RET_1M/3M/6M·거래대금·
52주고가거리·거래량추세·변동성 등) + 재무 퀄리티(F-Score) + 가치 2축(PBR·이익수익률) + 골든크로스 2축 +
수급 2축, **holdout을 3~6회씩 소진**해가며 검증한 끝에 **`HIGH_52W_HIGH`(52주 최고가 근접 매수) 하나만
살아남았다**(자세한 경과는 `CLAUDE.md`의 "종목 선정력 측정"·"멀티데이 백테스트"·"종목 선정 축 탐색"·"가치
축 백테스트" 절 참조). **holdout을 더 쓰는 것 자체가 검증을 오염시키므로, 새 가설(축)이 없는 한 재실행하지
말 것** — 매일 다시 돌려도 결론이 안 바뀌고 계산 비용(수 분~10분대)만 든다. 유일한 생존 축은 `sleeve-report`가
실시간으로 포워드 검증 중이니 **이것만 매회 확인**한다.

## Phase 2. 데이터 품질 검증 (분석 전 필수 — 수치를 액면대로 읽지 말 것)

1. **단일일 클러스터 검증**: 게이트/멀티데이 비교의 net이 이상하게 좋은 전략은 진입일 분포를 확인할 것 —
   `distinctDays`/`maxDaySharePct`/`topDay`/`netExTopDayPct`가 각 응답에 이미 실려 있다. 최대기여일 하나를
   빼고 부호가 뒤집히면(`clustered=true`) 그 수치는 채택 근거로 쓰지 않는다(이 시스템에서 반복 재발한 함정 —
   C의 6/26 하루 의존, L의 8/25 하루 의존 등).
2. **태깅 커버리지 편차**: `control-analysis`의 `edgeVsEnteredPct`가 null이면 대조군 정렬 실패(기간 안 겹침)라
   비교 불가 — null을 0이나 음수로 취급하지 말 것.
3. ⚠️ **horizon 개념이 2026-09-08 이후 단순해졌다**: 과거엔 게이트=`exit`(전략별 자동산출 권장 청산마크) vs
   `outcome-analysis`/`control-analysis`=`close` 기본값이 서로 달라 직접 비교가 금지됐었다. **지금은 `exit`
   horizon이 전 전략 고정 60분으로 통일**돼 있어(적응형 자동산출 폐기, `StrategyHoldTimeProvider` 단순화)
   더 이상 "게이트가 검증한 청산시점"이라는 의미가 없다 — **routine 분석에선 `horizon=exit`을 쓰지 말고
   `close`/`nextClose`를 쓸 것**. 게이트 자체의 실제 채점 horizon은 지금 대부분 `multiday`(9/7 전환된 8개
   전략) 또는 INVERSE 실현손익 버킷이므로, 게이트 판정 근거를 보려면 `strategy-gate` 응답의 사유 문자열을
   직접 읽거나 `multiday-exit-comparison`을 볼 것 — `outcome-analysis`/`control-analysis`로는 게이트와
   정렬되지 않는다(이건 결함이 아니라 애초에 다른 질문을 재는 도구이기 때문).
4. `market-breadth`가 빈 배열이면 재시작 직후(인메모리 소실) 확인 — Phase 2.5에서 함께 다룬다.

## Phase 2.5. 상태(국면·흐름·시장폭) 파악 정확도 점검

게이트는 (시장 × 국면 × 장중흐름 × 시장폭) 버킷으로 "지금 상태에서 양수였던 전략만" 열고, 약세장 라벨이면
엄격 버킷 통과분 외 전 전략(멀티데이 포함)을 차단한다(bear-block). 상태 라벨이 틀리면 표본 풀 자체가
바뀌어 잘못된 전략이 열리거나 옳은 전략이 닫힌다. 매 실행마다 점검하고, 오독 흔적이 있으면 Phase 5-A(시스템
보완)에 상태 판정 수정안으로 올린다.

```sql
-- ① 라벨-현실 불일치일: 그날 시장별 최빈 라벨 vs 실제(그날 진입 태그의 시장폭 평균·지수 등락 평균).
--    BEAR 라벨인데 시장폭≥55 또는 지수≥+0.5  /  BULL 라벨인데 시장폭<40 또는 지수≤-1.0  → 불일치 후보
select alert_date, entry_market, mode() within group (order by entry_market_trend) label,
  round(avg(entry_market_breadth_pct)::numeric,1) breadth, round(avg(entry_market_change)::numeric,2) idx_chg, count(*) n
from trade_outcome where alert_date>='YYYYMMDD' and entry_market in ('KOSPI','KOSDAQ') and entry_market_trend is not null
group by 1,2 order by 1,2;

-- ② 장중 라벨 플립: (날짜,시장)에 라벨이 2개 이상 → 그날 게이트 표본 풀이 바뀐 것.
-- (쉘 따옴표 중첩 문제를 피하려면 SQL을 파일로 저장해 scp 후 psql -f로 실행할 것)
select alert_date, entry_market, count(distinct entry_market_trend) labels, string_agg(distinct entry_market_trend, ',') which
from trade_outcome where alert_date>='YYYYMMDD' and entry_market in ('KOSPI','KOSDAQ') and entry_market_trend is not null
group by 1,2 having count(distinct entry_market_trend)>1 order by 1;
```

- **③ 시장폭 스냅샷 건강**: `market-breadth` 응답이 장중 빈 배열이면 재시작(인메모리 소실) 확인. 신선도 40분
  만료 구간(첫 스캔 publish 전 ~09:12)엔 폭 레이어가 생략되는 게 정상.
- **④ 게이트가 실제로 어느 층에서 판정했나**: `strategy-gate` 사유 문자열에서 `폭버킷`/`흐름버킷`/
  `국면표본부족`/`fallback`/`부트스트랩`/`net추세` 건수를 센다. `net추세` 레이어가 판정의 다수를 차지하면
  — 이 레이어는 표본이 쌓인 버킷에서만 방향(상승/하락 곡선)으로 여닫으므로 — "이 전략이 왜 닫혔나"를 물을 때
  그 사유 문자열의 `LOO`/`마지막일 net` 태그를 먼저 볼 것.
- **⑤ bear-block 발동**: 라벨 불일치가 있던 날의 bear-block 차단은 오독에 의한 기회 손실로 분류. 인버스는
  차단 대상이 아니어야 한다(있으면 버그).
- **⑥ 판정 소스 정합**: `market-regime`의 `asOf`가 당일인지, K는 `priorDayTrendOf`(전일 확정) 라벨로
  태깅됐는지(`entry_market_trend`가 장중 라벨과 다를 수 있음 — 정상).

**수정 판단 기준(제안 문턱)**: 불일치일이 최근 10거래일 중 ≥2일 → `MARKET_REGIME_INTRADAY_DEMOTE/PROMOTE_PCT`
재검 / 플립이 하루 ≥2회 반복 → `UPGRADE_MIN_HOLD_MINUTES` 상향 검토 / ⑥ 결손은 즉시 버그로 취급.

## Phase 3. 멀티데이 전략 성과 (핵심)

LIVE 화이트리스트(9/7 이후 8개 전략: A·B·C·D·G·J·L·P — B는 9/8 화이트리스트 제외됨, 이 목록은 `strategy-gate`
응답으로 매번 재확인할 것)의 **실제 경제성**을 판정하는 1차 도구. 인트라데이 net이 아니라 여기 수치가 "이
전략이 돈을 버는가"의 답이다.

```
multiday-exit-comparison?fullPathsOnly=true      # ⚠️ 필수 파라미터 — 없으면 horizon마다 표본이 달라 코호트 교체 산물이 섞인다
multiday-marks                                   # 수집 현황(전략별 outcomes/fullPaths/maxMarkDays) — 완주 코호트가 0이면 그 전략은 판정 불가(도입 최근)
```

**읽는 법**:
- 응답의 `recommended`가 방식명(예: `보유 D+1`)이면 그 방식이 **완주 코호트 안에서 단순보유(유니버스 동일가중)를
  이기는 유일한 조합**이라는 뜻. `유니버스 미달`이면 어떤 청산 방식으로도 유니버스를 못 이겼다는 뜻 — 그
  전략의 선정 자체가 약하거나 표본이 아직 얇다.
- `recommendedExcessPct`가 주지표다. **절대 net(`avgNetPct`)이 아니라 초과수익으로 판정할 것** — 절대 net은
  시장 드리프트를 함께 재므로, 강세장엔 다 좋아 보이고 약세장엔 다 나빠 보인다(2026-09-07 유니버스 반사실
  도입 계기: D+15 권장이 8개 전략에서 튀어나왔는데 전부 그 구간 시장이 올랐던 것이었다).
- `netExTopDayPct`/`excessExTopDayPct`로 클러스터 여부를 다시 확인 — Phase 2-1과 같은 원칙.
- `fullPaths=0`인 전략(도입한 지 15거래일이 안 지남)은 판정 보류, `multiday-marks`의 `maxMarkDays`로 확인.

**산출 형식**: **[전략] 완주n·거래일 · 권장방식·net·초과수익(최대기여일 제외 시 값도) → 판정(생존/보류/기각) →
근거**. 표로 정리해 한눈에 비교.

⚠️ **인버스(I)는 이 비교 대상이 아니다** — 당일청산 전용, 다일 감쇠라 멀티데이 경로가 의미 없다(코드도 이렇게
설계돼 있다).

## Phase 4. 종목선정 분석

**핵심 질문 하나**: "지금 이 시스템이 사는 종목들이, 안 샀으면 얻었을 것(유니버스)보다 나은가?" 이 질문에
답하는 유일하게 오염되지 않은 소스가 `sleeve-report`다.

1. **Sleeve 포워드 테스트 상태(매회 확인, 가볍다)**: `GET /admin/sleeve-report` — `HIGH_52W_HIGH`(52주 최고가
   근접 30종목·3개월 보유·동일가중)를 실시간 기록 중인 섀도우 슬리브. **실주문 없음.** 응답의 `cycles`·
   `avgExcessPct`·`cyclesPositive`·`results[].excessPct`를 확인. ⚠️ **사이클이 8~10개(2~3년) 쌓이기 전에는
   채택/기각 판정을 하지 말 것**(3개월 보유라 표본이 느리게 쌓인다 — `notes` 필드에 이 경고가 항상 실려 온다).
   지금은 관찰 단계이므로 리포트에는 "N사이클째, 현재 초과수익 X%p, 판정 보류" 정도만 담고 성급한 결론을
   내지 않는다.
2. **무거운 스윕 재실행 금지**(Phase 1 경고 반복) — `selection-sweep`/`value-sweep`/`multiday-backtest`/
   `financial-spread`는 사용자가 명시적으로 새 축(가설)을 제시했을 때만, 그리고 반드시 `since`/`until`로
   탐색·holdout 구간을 나눠 돌릴 것. 이미 죽은 축(RET계열·거래대금·거래량추세·저PBR·F-Score·골든크로스·
   수급 등)을 별다른 새 근거 없이 재검하지 말 것 — CLAUDE.md에 전부 기각 사유가 기록돼 있다.

### 4-A. 복합 지표 판정 (단일축 필터는 지양 — 2026-09-08 사용자 결정)

⚠️ **원칙 변경**: 수급·체결강도·추세·뉴스·호가불균형·ATR 같은 개별 feature를 **하나씩 따로 켜고 끄는
단순 필터로 다루지 않는다.** 이 시스템은 지금까지 이런 축들을 하나씩 독립적으로 검정해왔고, CLAUDE.md에
기록된 실측만 봐도 급증 lift·거래량배수 단조성·`WEAK_VOLUME` 대조군·전역 필터 3종·뉴스 반증·수급 반증까지
**최소 7개의 독립된 단일축 연구가 전부 같은 결론**("이미 관심을 받은 종목을 사면 진다")에 도달했다. 이건
"단일축이 전부 무신호"라는 뜻이 아니라 — **서로 다른 방식으로 같은 현상(쏠림/과열)을 재는 상관된 지표들을
하나씩 따로 봐서 신호 대 잡음비가 낮았다는 뜻**일 가능성이 높다. → 앞으로는 **관련 feature를 묶어 하나의
복합 점수로 만들고, 그 복합 점수 단위로 성과를 본다.**

**방법(SQL 템플릿 — 아직 전용 엔드포인트가 없다, 필요성이 확인되면 Phase 5-D로 코드화 제안)**:

```sql
-- "관심집중도" 복합점수 — 개별로 이미 방향이 확인된 지표들을 +1/0으로 합산(0~5).
-- entered/control 양쪽에 다 태깅되므로 대조군까지 포함해 컷별 성과를 본다(universe-analysis와 같은 사상 —
-- 반사실은 lift가 아니라 대조군으로 잰다). 임계는 예시 — CLAUDE.md 각 축의 기존 근거값을 따르되
-- 데이터가 쌓이면 재조정.
with scored as (
  select id, control_sample, strategy, alert_date,
    (case when entry_volume_ratio >= 8 then 1 else 0 end)
  + (case when entry_change_rate >= 5 then 1 else 0 end)
  + (case when entry_news_cnt_1h >= 3 then 1 else 0 end)
  + (case when (coalesce(entry_frgn_ntby_ratio,0)+coalesce(entry_orgn_ntby_ratio,0)) >= 2 then 1 else 0 end)
  + (case when entry_exec_strength >= 150 then 1 else 0 end)
    as crowd_score,
    (price_close - buy_price)::numeric / buy_price * 100 as close_gross,
    (price_next_close - buy_price)::numeric / buy_price * 100 as nextclose_gross
  from trade_outcome
  where alert_date >= 'YYYYMMDD' and price_close is not null
)
select crowd_score, control_sample, count(*) n,
  round(avg(close_gross)::numeric,2) avg_close, round(avg(nextclose_gross)::numeric,2) avg_nextclose
from scored group by 1,2 order by 1,2;
```

**읽는 법**: 복합점수가 올라갈수록(더 많은 "관심 신호"가 동시에 켜질수록) `avg_close`/`avg_nextclose`가
**단조 하락**하는지, 그리고 그 하락이 `control_sample=true`(미진입 포함 전체)에서도 같은 방향인지 본다.
단조성이 개별 단일축보다 뚜렷하면 복합 점수가 실제로 잡음을 줄인 것 — 이 경우 반대 극단(복합점수 0, "조용한"
후보)을 종목선정 후보로 검토할 가치가 있다(L/N 전략의 "관심 못 받은 채 조용히 있는 종목" 가설과 같은 방향).

⚠️ **주의사항(단일축보다 더 엄격히 적용할 것)**:
- **변수가 늘수록 과적합 위험도 늘어난다** — 임계값을 5개 조합하면 우연히 잘 맞는 조합을 찾을 확률이
  단일축보다 훨씬 높다. 반드시 `since`/`until`로 탐색·holdout을 나누고, holdout에서도 방향이 유지돼야
  채택 후보로 본다(이 시스템이 이미 여러 번 겪은 실패 패턴 — `TURNOVER LOW`·`GC_RECENCY HIGH`·
  `FRGN_CHG_3M HIGH` 전부 탐색 구간에서 깨끗했다가 holdout에서 부호가 뒤집혔다).
- **단일일 클러스터 가드는 그대로 적용**(Phase 2-1) — 복합점수 버킷별로도 `distinctDays`/최대기여일 제외
  net을 반드시 확인.
- **왜도 큰 feature를 이진화할 때 임계 선택 자체가 숨은 자유도**다 — 예시 임계(거래량배수≥8, 등락률≥5% 등)는
  CLAUDE.md에 이미 근거가 있는 값을 재사용했지만, 여러 임계를 시험해보고 제일 잘 맞는 걸 고르면 그것도
  과적합이다. 임계는 고정하고 결과만 보고할 것.
- 결과가 나오면 **"복합점수 N에서 net이 M%였다"는 한 줄 보고가 아니라, 단조성·holdout·클러스터 셋 다 통과한
  뒤에만** Phase 5-B/C 제안으로 올릴 것 — 통과 못 하면 "복합 지표도 무신호"라는 결론 자체가 유효한 산출물이다.

**적용 범위 둘**:
- **전략 진입-레벨 복합**(위 템플릿, `TradeOutcome.entry_*` 컬럼): 수급·체결강도·뉴스·호가불균형·거래량배수·
  등락률처럼 진입 순간에 찍히는 지표들의 조합. Phase 5-C 축③의 기본 접근으로 승격.
- **종목선정-레벨 복합**(`selection-sweep`/`value-sweep`이 쓰는 `daily_price` 기반 `SelectionAxis`,
  월별 스냅샷): RET_1M/3M/6M·52주고가거리·거래량추세·거래대금·PBR/이익수익률처럼 월 단위로 갱신되는
  지표들의 조합 — 예: `HIGH_52W_HIGH`(유일 생존 단일축) + 완만한 RET_3M + 적정 거래대금을 함께 요구하면
  더 나아지는지. 단 이쪽은 **holdout을 이미 3~6회 소진**했으므로(Phase 1 경고) 사용자가 새 조합을 명시적으로
  요청했을 때만, 그리고 반드시 코드에 정식 축으로 추가해 `since`/`until`로 검증할 것 — SQL로 대충 훑어보고
  판단하기엔 이미 표본이 오염 위험군이다.

3. **진입필터가 선정력을 더하는가**(`control-analysis?horizon=close`, 보조): 전략별 "진입 vs 미진입(reject
   사유별)" 비교는 "이 전략의 자체 필터가 같은 후보군에서 더 나은 종목을 골랐는가"를 재는 도구다 — 넓게 보면
   이것도 종목선정 품질 진단이다. `hint`에 "미진입이 더 나음(필터 완화 검토)"가 뜨면 그 필터가 오히려
   선정력을 깎고 있다는 뜻. `edgeVsEnteredPct`(기간 정렬된 값)만 볼 것 — 정렬 안 된 원값은 도입 시점 편향으로
   부풀려질 수 있다.
4. **feature-mining은 horizon을 바꿔 쓸 것**: `?horizon=exit`은 이제 의미가 없다(Phase 2-3 참조) — `close`/
   `nextClose`/`d2`/`d3` 중 멀티데이 스케일에 가장 가까운 것을 쓴다(정확한 15거래일 반사실은 아직 이 엔드포인트가
   지원 안 함 — Phase 5-D 참조). ⚠️ 2026-08-21 발굴 세션 결론을 잊지 말 것: **"유효한 축은 이미 게이트가 쓰는
   전략×시장×국면뿐이고, 미탐색 pocket은 없었다."** 단, feature-mining은 **여전히 단일축**이다 — 새 pocket이
   나오면 위 4-A 방식으로 다른 지표와 묶어 복합점수로도 재검할 것. 통과해도 즉시 채택하지 말고 섀도우로 먼저
   검증할 것.

## Phase 5. 조치 제안

**A. 시스템 보완 조치사항** — 로그 에러, 오버나잇 잔류 포지션, 상태 판정 오독(Phase 2.5), 멀티데이 마크
수집 결손(`multiday-marks`의 특정 전략 `outcomes` 대비 `rows`가 비정상적으로 적은 경우) 등. 버그성은 근거와
함께 수정안 제시.

**B. 종목선정 개선 제안** — 데이터 근거와 함께:
- `control-analysis` hint 기반 필터 완화/강화(해당 reject 사유·표본수·edge 명기) — "그 필터를 풀면/조이면
  이 전략이 더 나은 종목을 고를 것이다"라는 형태로 서술할 것(단순 net 개선이 아니라 선정력 개선으로).
- 멀티데이 초과수익이 뚜렷이 음수인 전략의 화이트리스트 제외 제안(Phase 3 결과 기반).
- 임계치는 env 이름으로 구체 제안(`SIGNAL_*`/`TRADING_*`). ⚠️ prod 반영은 `docker-compose.prod.yml`의
  `environment:`에 해당 키 패스스루가 있어야 함 — 없으면 추가 필요하다고 명시.
- 제안 vs 즉시 적용 구분: 화이트리스트 변경 등 실거래에 영향 주는 조치는 **제안만** 하고, 사용자 확인 전엔
  적용하지 않는다.

**C. 전략별 보정방안(종목선정 관련 축만)** — 표본이 충분한 전략마다 아래 축을 순회해 구체안을 낸다. **인트라데이
청산·보유시간·손절선·집행품질 축은 이 스킬에서 다루지 않는다**(위 범위 변경 참조 — 필요하면 사용자가 별도
요청).

| 축 | 데이터 소스 | 진단 신호 | 보정 레버 |
|---|---|---|---|
| ① 진입 필터 강도 | `control-analysis`(close, aligned) | reject분이 ENTERED보다 나쁨→필터 유효 / 좋음→과도 | 해당 전략 `SIGNAL_*` 임계 |
| ② 국면 조건부 | `strategy-gate` 사유·`multiday-exit-comparison` | 특정 국면에서만 약함 | 그 국면 진입 하드컷(`entryTrend`) 또는 게이트 `*_ALLOWED_REGIMES` |
| ③ 승패 feature(복합) | `feature-mining` 단일축 + **Phase 4-A 복합점수**(우선) | 단일 feature가 아니라 관련 feature 묶음의 복합점수가 단조성을 보이는가 | 개별 filter가 아니라 복합점수 상/하한. 왜도 큰 feature는 bin별 net으로(평균 비교 금지 — 뉴스경과분에서 반대 결론 낸 전례) |

출력 형식: **[전략] 진단(근거 n·edge/excess) → 보정안(env 이름/코드 위치) → 기대효과 → 리스크·표본충분성**.
전략마다 가장 임팩트 큰 1~2개만 제시.

**D. 데이터 공백 & 추가 지표 제안** — "측정 먼저" 원칙. 현재 이 시스템에서 두드러진 공백:
- **멀티데이 반사실(대조군) 부재** — `control-analysis`/`feature-mining`의 대조군은 일봉 마크를 수집하지
  않아 15거래일 스케일 반사실이 없다(CLAUDE.md: "대조군은 일봉 마크를 수집하지 않아 multiday horizon에선
  버킷이 공집합"). `multiday-exit-comparison`의 유니버스 반사실이 그 공백을 부분적으로 메우지만, 이건
  "전략이 고른 것 vs 유니버스"이지 "전략이 고른 것 vs 그 전략이 거른 것"이 아니다. 후자를 재려면 대조군에도
  일봉 마크 수집을 붙여야 하는데 아직 없다 — 붙일지는 비용 대비 판단 필요(제안만, 미적용).
- **feature-mining의 진짜 멀티데이 horizon 부재** — d2/d3까지만 있고 d5/d10/d15가 없어 15거래일 스케일
  feature 탐색이 근사치로만 가능하다.
- 신규 태깅은 forward-only(소급 불가)인 것과 소급 가능한 것을 구분해서 제안할 것 — 소급 가능하면 "먼저
  재고 아니면 버린다"(수급 태깅 사례), forward-only면 "일단 붙이고 판정은 나중에"가 맞다.
- **복합 점수 전용 엔드포인트 부재** — 4-A는 지금 ad hoc SQL로만 가능하다. 특정 복합 조합이 홀드아웃·클러스터
  가드를 반복해서 통과하면(우연이 아니라고 볼 근거가 쌓이면), `FeatureMiningService`에 다축 조합 필터를
  추가하거나 별도 `CompositeScoreService`를 신설해 정식 코드로 승격하는 걸 제안할 것 — SQL 스니펫을 매번
  손으로 복사해 돌리는 건 재현성이 떨어지고 실수가 잦다.

## 인버스 분리 표기

B·E 등 롱 전략이 인버스 ETF(114800/251340)도 매매하므로, 전략별 집계에서 인버스 행을 분리해 표기한다
(`stock_code in ('114800','251340')` 기준). 게이트는 entry_market 버킷으로 이미 분리돼 있어 실주문 판정엔
오염 없음 — 이 규칙은 보고서 표기 전용.

## 스타일

- 수치는 net(왕복비용·슬리피지 차감) 여부, **그리고 절대치인지 유니버스 대비 초과수익인지**를 항상 명시.
  멀티데이 구간에서는 절대 net만 보고 판단하지 말 것(Phase 3 참조).
- 표본수(n) 없는 수익률 인용 금지. 단일일 클러스터 의심 시 `netExTopDayPct` 함께 제시.
- 결론 먼저, 근거 다음. 사용자는 전략 튜닝 판단권자 — 데이터로 옵션을 제시하되 판단을 대신하지 않는다.
- 화이트리스트/env 변경 같은 실거래 영향 조치는 **제안**과 **실행**을 분명히 구분해서 서술할 것.
