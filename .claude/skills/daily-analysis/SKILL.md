---
name: daily-analysis
description: 주식 자동매매 시스템의 일별 성과·전략 진단 종합 분석. 모든 집계 데이터(게이트·대조군·흐름·MAE·breadth·집행품질·feature-mining·멀티데이)를 수집해 전략 옥석을 가리고, 시스템 보완 조치 + 전략별 보정방안(수집 데이터 기반 진입/청산/손절/사이징 튜닝) + 데이터 공백을 메울 추가 지표 + 신규 전략 발굴(수익 pocket→가설)까지 제안한다. 최근 배포된 새 엔드포인트가 있으면 자동 반영.
user-invocable: true
---

# daily-analysis

장 마감 후(또는 요청 시점) 시스템의 **모든 집계 데이터**를 수집해 종합 분석 리포트를 만든다.
산출물은 ① 일별 성과 요약 ② 전략별 진단(옥석) ③ 시스템 보완 조치 ④ 전역 수익률 개선 ⑤ **전략별 보정방안(수집 데이터 기반 진입·청산·손절·사이징 튜닝)** ⑥ **데이터 공백 & 추가 지표 제안** ⑦ **신규 전략 발굴(수익 pocket → 가설, Phase 5)** 7부.

⚠️ **매 실행 시작 시**: 최근 배포로 **새 엔드포인트/데이터가 추가됐는지 먼저 점검**(로컬 `git log --oneline -15`로 최근 커밋 확인, 또는 새 `/admin/*` GET 시도)하고, 있으면 Phase 1 수집에 포함해 분석에 활용한다 — 이 시스템은 계속 진화하므로 고정된 엔드포인트 목록에 갇히지 말 것.

## 환경 (고정 사실 — 재탐색 불필요)

- 배포: GCP VM `stock-advisor`(zone `asia-northeast3-a`). 접근: `gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command='...'`
- 컨테이너명: 앱 `sa-app`, DB `sa-postgres`, Redis `sa-redis` (⚠️ `stockadvisor-postgres`는 **로컬** 컨테이너명. VM에서는 `sudo docker` 필요)
- 앱 API는 VM 내부 `localhost:8080`만 바인딩 → SSH command 안에서 curl.
- ⚠️ `POST /api/v1/admin/daily-report`는 **호출 금지**(Discord 중복 발송됨). 분석은 GET 엔드포인트 + DB 직접 조회로만.

## Phase 1. 데이터 수집

SSH 한 번에 여러 curl을 묶어 호출(왕복 절감). 수집 대상 GET 엔드포인트(`/api/v1/admin/` prefix):

| 축 | 엔드포인트 |
|---|---|
| 게이트/국면/리스크 | `strategy-gate`, `market-regime`, `risk-status` |
| 승자/패자·대조군 | `outcome-analysis?horizon=close`, `control-analysis?horizon=close` (스윙 C는 `nextClose`도) |
| 집행·청산 | `execution-quality`, `exit-hold`, `exit-stop`, `exit-method`, `exit-timing`, `exit-comparison` |
| 손절·MAE(전략별 보정용) | `heat-analysis` (승자/패자 MAE 분포·손절선 시뮬 — 전략별 손절 튜닝 근거) |
| 스윙 | `swing-exit`, `swing-trail-analysis` |
| 장중흐름 | `flow-analysis?lag=30` **및** `lag=60` (둘 다 — lag별 표본이 다름, 아래 함정 참조) |
| 시장 폭 | `market-breadth` |
| **전략 발굴(생성적)** | **`feature-mining?horizon=exit&includeControl=true`** (feature 조합별 net·진입-대조군 edge — 신규 전략 pocket 발굴, 아래 Phase 5) |
| **멀티데이(2-3주)** | `multiday-exit-comparison` (C·D·J 일봉경로 보유D+N/트레일/MA/손절 시뮬), `multiday-marks` (수집현황) |

⚠️ **2026-08-12 추가 배포분(반드시 활용)**:
- **horizon 통일**: `outcome-analysis`·`feature-mining`도 이제 **`?horizon=exit`** 지원(게이트·control과 동일 청산시점). **반사실 비교는 exit로 정렬**해 뽑을 것(종가 horizon과 혼용 금지 — 아래 함정3 완화됨).
- **게이트 교차거래일 가드**: `strategy-gate` 사유에 "단일일 클러스터(최대 X% > 80%) — 교차거래일 미충족"이 뜨면, 그 버킷 net은 **하루 이벤트가 부풀린 허수**라 게이트가 차단한 것(정상). 이 사유가 뜬 전략은 "성과 미달"과 구분해 보고.
- **feature-mining 진입-대조군 edge**: 각 pocket에 `netAvgPct`(진입)·`controlNetPct`(미진입)·`edgeVsControlPct`(진입−대조군). **edge<0이면 그 조건에선 진입이 손해**(전략 필터가 오히려 해로움), **edge>0 & 진입net>0이면 유효 pocket**.

DB 직접 조회(`sudo docker exec sa-postgres psql -U stockadvisor -d stockadvisor -c "..."`):

```sql
-- ⚠️ 날짜 컬럼은 전부 varchar(8) 'yyyyMMdd' — current_date 비교 금지, 문자열 리터럴로.
-- ⚠️ 섀도우 성과 컬럼명: price_close/price_next_close/price_d2/price_d3 (close_price 아님), 대조군 플래그는 control_sample.

-- ① 오늘 LIVE 주문 전체(진입·청산·취소 흐름)
select id,stock_code,strategy,side,status,requested_qty,requested_price,avg_fill_price,realized_pnl,closed,mode
from trade_order where order_date='YYYYMMDD' order by id;

-- ② 최근 7일 LIVE 실현손익(죽은 주문 제외)
select order_date, count(*) buys, count(*) filter (where closed) closed, sum(realized_pnl) pnl_krw
from trade_order where mode='LIVE' and side='BUY'
  and status not in ('REJECTED','FAILED','CANCELLED') and order_date>='YYYYMMDD'
group by order_date order by order_date;

-- ③ 미청산 포지션(오버나잇 리스크 점검 — B 등 당일청산 전략이 남아있으면 운영 이슈)
select id,stock_code,strategy,order_date,requested_qty,requested_price,status from trade_order
where side='BUY' and closed=false and status not in ('REJECTED','FAILED','CANCELLED');

-- ④ 오늘 섀도우 진입 전략별(승패 기준은 net>0 ≈ gross>0.18%)
select strategy, count(*) n,
  round(avg((price_close-buy_price)::numeric/buy_price*100),2) close_gross,
  count(*) filter (where (price_close-buy_price)::numeric/buy_price*100>0.18) wins
from trade_outcome where alert_date='YYYYMMDD' and control_sample=false
group by strategy order by n desc;

-- ⑤ 최근 5거래일 일자별 섀도우 추이(시장 순풍/역풍 판별)
select alert_date, count(*) n, round(avg((price_close-buy_price)::numeric/buy_price*100),2) close_gross,
  round(100.0*count(*) filter (where (price_close-buy_price)::numeric/buy_price*100>0.18)/nullif(count(price_close),0),1) win_pct
from trade_outcome where alert_date>='YYYYMMDD' and control_sample=false and price_close is not null
group by alert_date order by alert_date;
```

운영 로그 점검(선택): `sudo docker logs sa-app --since 24h 2>&1 | grep -E "ERROR|시장폭 갱신|서킷"` — 에러·스냅샷 갱신·서킷 전이 확인. 앱 재시작 여부는 `sudo docker inspect sa-app --format "{{.State.StartedAt}}"`.

## Phase 2. 데이터 품질 검증 (분석 전 필수 — 수치를 액면대로 읽지 말 것)

1. **단일일 클러스터 검증**: 게이트/흐름 net이 이상하게 좋은 전략(net>3% 등)은 표본의 진입일 분포를 확인:
   `select alert_date, count(*), round(avg(...),2) from trade_outcome where strategy='X' and control_sample=false group by alert_date;`
   같은 날 진입 N건은 횡단면 상관 표본이라 **독립 표본이 아니라 이벤트 1개**. (실사례: C의 게이트 net +6~7%·승률 96%는 133건 중 131건이 2026-06-26 반등일 하루 — 사실상 이벤트 2개였음.)
2. **태깅 커버리지 편차**: flow/breadth/regime 태그가 있는 부분집합과 없는 부분집합의 성과가 다르면(태깅 유무별 avg 비교) 그 축의 분석은 선택 편향 의심.
3. **horizon 정합**: 게이트=exit horizon(전략별 권장 청산마크, 스윙 C=nextClose), outcome/control 기본=close. **서로 다른 horizon 수치를 직접 비교 금지** — F가 게이트 +0.33(90분)인데 control ENTERED −0.96(close)인 식의 차이는 모순이 아니라 horizon 차이.
4. **flow lag=60의 구조적 편향**: mom60은 장 시작 60분 후부터만 존재 → lag60 표본 = **10시 이후 진입 부분집합**. lag30과 결과가 다르면 "흐름 차이"가 아니라 "진입 시간대 차이"일 수 있음(실사례: B는 lag60 부분집합 전체가 마이너스 = 늦은 진입 자체가 손실).
5. `market-breadth`가 빈 배열이면: 재시작 직후(인메모리 소실)인지 확인. 진입 태깅은 `entry_breadth_pct`로 DB에 남으므로 분석은 DB 태깅값 사용.

## Phase 3. 분석 리포트 작성

**TL;DR 선두** — 오늘 LIVE 손익(실현+미청산 평가), 섀도우 방향, 가장 중요한 발견 1~2개를 첫 문단에.

1. **오늘 LIVE 실거래**: 진입/청산 테이블 + 실현손익. 미청산 포지션은 오버나잇 리스크로 명시.
2. **주간 흐름**: 7일 실현손익 추이 + 큰 등락의 원인 종목(execution-quality의 개별 trade로 확인).
3. **전략별 옥석** (3분류):
   - *검증 우위*: 게이트 통과 + 대조군 "진입분 우위" + 흐름 엣지 정합 → 유지/확대 후보
   - *경고*: 대조군 hint "미진입이 더 나음"(모든 reject 사유가 ENTERED보다 좋으면 가설 자체 의심) → 개선/중단 후보
   - *판정 대기*: 표본 부족(fail-closed 정상 동작) — 국면(entry_market_trend)·흐름 태그가 쌓일 때까지
4. **시장 국면 맥락**: regime(추세·변동성) + breadth(참여 넓이) + 최근 5일 섀도우 추이로 "전략 문제 vs 시장 역풍" 구분. 노출상한(중립 60%×고변동 0.5 등)이 현재 얼마로 계산되는지.
5. **장중흐름 축**: 전략별 흐름↑/↓ net 비교 + what-if. **전략별 방향이 반대**(B=딥바잉 흐름↓, F/G/H=흐름↑)이므로 단일 게이트 제안 금지 — 전략별 조건부만.
6. **집행·청산 인프라**: execution-quality gap(음수=집행 드래그), entrySlip, 적응형 손절/보유시간/청산방식 현황(auto 여부·근거 표본).

## Phase 4. 조치 제안 (4종 분리 — A 시스템 / B 전역 수익률 / C 전략별 보정 / D 데이터 공백·추가 지표)

**A. 시스템 보완 조치사항** — 로그 에러, 오버나잇 잔류 포지션, 미체결→취소 후 재주문 실패, 스냅샷/태깅 누락, 게이트 구조 약점(예: min-samples를 단일일 클러스터가 채우는 문제 → 다중 거래일 분산 요건) 등. 버그성은 근거(로그/DB)와 함께 수정안 제시.

**B. 수익률 개선 제안** — 반드시 데이터 근거와 함께:
- 대조군 hint 기반 필터 완화/강화(해당 reject 사유·표본수·net 차이 명기)
- 흐름 what-if의 "개선 후보" 전략(양쪽 lag에서 일관 + 표본 충분할 때만)
- 진입 시간대 제한(lag60 편향 활용), 손절선 조정(heat-analysis 승자/패자 MAE 분리), 보유시간(exit-timing 곡선)
- 임계치는 env 이름으로 구체 제안(`SIGNAL_*`/`TRADING_*`). ⚠️ **prod 반영은 `docker-compose.prod.yml`의 `environment:`에 해당 키 패스스루가 있어야 함** — 없으면 추가 필요하다고 명시.
- 제안 vs 즉시 적용 구분: 사용자가 "국면/흐름 데이터 쌓이면 판단" 방침이면 손실전략 중단·게이트 변경은 **제안만** 하고 적용하지 않는다.

**C. 전략별 보정방안 (수집 데이터 기반)** — A(시스템)·B(전역)와 별개로, **표본이 충분한**(대략 게이트 min-samples≈30↑) 전략마다 아래 8축을 순회해 **구체적 보정안**을 낸다. 각 축은 "데이터 소스 → 진단 신호 → 보정 레버(env/code)". 전략마다 **가장 임팩트 큰 1~3개만** 제시(전 축 나열 금지). 출력 형식: **[전략] 진단(근거 n·net) → 보정안(env 이름/코드 위치) → 기대효과 → 리스크·표본충분성**.

| 축 | 데이터 소스 | 진단 신호 | 보정 레버 |
|---|---|---|---|
| ① 진입 필터 강도 | `control-analysis` (reject 사유별 net vs ENTERED) | 특정 reject분이 ENTERED보다 **나쁨→필터 유효**(더 조여도 됨) / **좋음→과도**(완화) | 해당 전략 `SIGNAL_*` 임계(min-drop/gap/score/buffer/min-ratio 등) |
| ② 국면 조건부 | `outcome-analysis` byMarketRegime + gate 국면버킷 | 한 국면에서만 음수(예 하락장 −2%) | 그 국면 진입 하드컷(`entryTrend`) — 게이트가 이미 국면조건부지만 후행적이라 전략단 컷이 더 빠름 |
| ③ 흐름 조건부 | `flow-analysis` byFlow/byRegimeFlow (**lag30·60 일관** 필수) | 흐름부호별 net 갈림(양 lag 일관 + 표본충분) | 흐름↑/↓ 스킵 env(예 D `SIGNAL_D_REQUIRE_RISING_FLOW`). ⚠️ **전략별 방향 반대**(B=흐름↓ 우위) — 단일게이트 금지 |
| ④ 승패 feature | `outcome-analysis` numericFeatures(승 avg vs 패 avg diff) | 특정 feature가 승패를 가름(거래량배수·PER·PBR·시총·체결강도·ret5d·ATR·고가거리) | 그 feature 상/하한 필터 신설. **없는 축이면 D(추가지표)로 넘김** |
| ⑤ 손절선 | `heat-analysis` (승자 MAE p10/p30) | 고정/현행 손절이 승자 과다희생 or 방어부족 | `TRADING_ADAPTIVE_STOP`(자동) — 표본·클램프([min,max]) 재확인. peak/trough 순서 근사 주의 |
| ⑥ 보유시간 | `exit-timing` 곡선(평균net 최대 마크) | 권장마크 이동/이중딥(낙관편향) | `TRADING_ADAPTIVE_EXIT`(자동) — 곡선 신뢰성(표본·단일일 클러스터) 확인 후 |
| ⑦ 청산방식 | `exit-comparison` + **라이브 실보유시간 분포**(아래 쿼리) | 신호기반(VWAP/추세전환) 채택인데 **라이브 보유가 수분(과민 조기청산·저가매도)** | `min-hold-minutes`/`vwap-buffer-pct`(방식청산 지연) 또는 TIME 고정. 시뮬(성긴 마크) vs 라이브(1분) 과민 괴리 유의 |
| ⑧ 집행 품질 | `execution-quality` (gap/entrySlip) | gap 음수(집행 드래그)·진입슬립 큼 | 지정가 오프셋·유동성 필터(`min-turnover`)로 저유동 종목 배제 |

⑦ 검증용 **라이브 실보유시간 분포**(게이트 exit-horizon과 실제 청산 괴리 = 조기/지연청산 진단, Phase 1엔 없음):
```sql
select b.strategy, round(extract(epoch from (s.created_at-b.created_at))/60) hold_min, count(*) n
from trade_order b join trade_order s on s.idempotency_key='SELL:'||b.id
where b.mode='LIVE' and b.closed and b.order_date>='YYYYMMDD'
group by b.strategy, hold_min order by b.strategy, hold_min;
```
분포가 게이트 horizon(예 D 55분)보다 **왼쪽(1~수분)에 몰리면 조기청산 저가매도** — ⑦ 보정 대상.

**전략별 성격 힌트**(보정 방향 판단용): A=공시촉매·상승 / B=거래량선행·딥바잉(흐름↓)·강급증에서만 / C=역추세 스윙(D+1)·떨어지는칼날 회피 / **D=지수상대 준헤지·흐름↑ 우위·역추세라 즉시청산(VWAP) 방식과 충돌** / E=신고가돌파 공격적·손실폭 큼 / F·H=추세돌파(확인장치 부재 시 false breakout)·흐름↑ / G·J=반등계열(체결강도·ret5d 과열 가드 대상) / K=개장갭·약세장 취약 / I=인버스 헤지(폭락일 한정, 실현손익 채점). → 보정은 **전략 성격에 맞는 방향만**(예 F/H는 강도확인 추가, D는 청산 지연, 반등계열은 과열 컷).

**D. 데이터 공백 & 추가 지표 제안** — A·B·C 진단 중 **현 수집 데이터로 결론이 안 나는 지점**을 명시하고, 그걸 풀 **신규 지표/태깅/엔드포인트**를 구체 제안한다("측정 먼저" 원칙). 형식: **[막힌 질문] → [필요 지표] → [수집 방법(진입태깅 신규컬럼 / KIS 필드 / 신규 엔드포인트) · 비용]**. 자주 재발하는 공백(해당되면 지목, 없으면 생략):
- **표본이 단일 거래일 클러스터**라 독립성 부족(Phase 2-1) → 게이트/분석에 **교차 거래일 수(distinct alert_date) 요건·컬럼** 추가 제안.
- **승패 feature에 없는 축**(시총구간·업종·변동성구간·**진입 시각대**·호가 스프레드) → 해당 축이 승패를 가르는지 보려면 `TradeOutcome`에 entry feature 신규 태깅 필요(forward-only).
- **청산 what-if가 앵커 보간 근사**(마크 시점 지수흐름·breadth) → `OutcomeSample` 실측 저장으로 대체(일부 `idx_mom30`/`breadth_pct` 이미 있음 — 커버리지 확인).
- **라이브 청산 실측**(체결 시각·실체결가) vs 섀도우 마크 괴리 → 체결 로그 기반 **실보유시간·실슬리피지 분포** 수집(C⑦·집행품질 정밀화).
- **뉴스/체결강도 feature가 측정만 되고** 승격 판단 표본 부족 → 누적 후 재검 시점(표본 목표치)을 명시.
- ⚠️ 신규 태깅은 **forward-only(소급 불가)** → 판단 보류 중이어도 **"측정은 지금 시작"** 제안을 우선(빨리 붙일수록 검증이 빨라짐).

## Phase 5. 신규 전략 발굴 (생성적 — 수익 pocket → 전략 가설, 2026-08-12 추가)

기존 전략 채점(Phase 3·4)과 별개로, **쌓인 데이터에서 아직 전략화 안 된 수익 조건을 발굴**한다. 핵심 소스는 `feature-mining`.
> ⚠️ 이건 "발견"이지 "즉시 채택"이 아니다 — 사후 데이터 마이닝은 다중검정으로 허수를 잘 낳는다. 산출물은 **가설 + 검증 계획**이지 실전 배포 지시가 아니다.

**수집**(exit horizon 필수 — 게이트와 동일 기준):
```
feature-mining?horizon=exit&includeControl=true&minSamples=30&maxDayShare=80        # 전체
feature-mining?horizon=exit&includeControl=true&minSamples=30&regime=BULL           # 국면 세그먼트(교란 통제)
feature-mining?...&regime=NEUTRAL / regime=BEAR                                      # 국면별로 각각
```
국면을 안 나누면 국면 교란으로 섞인다(전 전략이 국면조건부) → **반드시 regime 세그먼트별로도** 뽑는다.

**발굴 필터(허수 배제 — 이걸 다 통과한 pocket만 "후보")**:
1. **비클러스터**(`clustered=false`) — 단일일 이벤트가 만든 net 제외(이미 highlights는 통과분만).
2. **표본 충분 + 교차거래일**(`n≥30`, `distinctDays≥5`) — 며칠에 걸쳐 반복된 조건.
3. **진입 net > 왕복비용**(`netAvgPct` 유의미하게 +).
4. **진입-대조군 edge > 0**(`edgeVsControlPct>0`) — 그 조건에서 **진입이 미진입보다 실제로 나았음**(필터/신호가 가치 추가). edge≤0이면 "그 조건은 좋아 보여도 진입이 손해"라 후보 아님.
5. **국면 일관성** — 한 국면만이 아니라 최소 두 국면에서 +거나, 특정 국면 전용이면 그 국면 표본이 충분.

**해석·산출**:
- **후보 pocket을 "이미 어느 전략의 영역인가"로 분류**: `topStrategy`가 그 pocket을 이미 지배하면 → 그 전략의 **파라미터 튜닝**(Phase 4-C)으로 회수. topStrategy가 잡다하거나 pocket이 여러 전략에 흩어져 있으면 → **신규 전략 가설**(그 조건만 노리는 새 룰).
- **교차 feature 확인**: 단일 feature pocket이 뜨면, 그게 다른 feature(국면·흐름·업종)와 겹치는지 2차 확인(`?regime=`·`?market=` 조합). 진짜 엣지는 보통 2~3 조건 교집합.
- **출력 형식**: **[발굴 pocket] 조건(feature 구간 + 국면/흐름) · 근거(n·distinctDays·진입net·edge) → 이미 전략 영역인가(topStrategy) → 가설(신규 룰 or 기존 튜닝) → 검증 계획(섀도우 변수로 forward, F 신선도필터·H 돌파확인 패턴)**.
- ⚠️ **즉시 실전 금지**: 발굴은 forward 검증(섀도우 + 대조군)으로 넘긴다. "이 조건이 과거에 좋았다"는 **미래를 보장 안 함** — 신규 전략/필터는 `@Component + scope()` 또는 env 기본 off 섀도우 변수로 붙여 측정부터(측정-먼저 원칙).

**avoid 활용(대칭)**: `avoid`(net 하위) + `edgeVsControlPct<0` pocket = **현재 전략이 진입하는데 미진입이 나은 조건** → Phase 4-C 필터 강화/국면 하드컷 후보로 넘긴다.

## 인버스 분리 표기 (2026-07-30 추가)

B·E 등 롱 전략이 인버스 ETF(114800/251340)도 매매하므로, **전략별 섀도우/LIVE 집계에서 인버스 행을 분리**해 표기한다
(`stock_code in ('114800','251340')` 기준). 합산 수치는 오해를 부른다 — "B 부진"의 원인 분해(일반주 신호 문제 vs 인버스 청산 문제)가 목적.
게이트는 entry_market 버킷으로 이미 분리돼 있어 실주문 판정엔 오염 없음(사용자 확인 완료) — 이 규칙은 보고서 표기 전용.

## 스타일

- 수치는 net(왕복비용 0.18%+슬리피지 차감) 여부를 항상 명시. gross/net 혼용 금지.
- 표본수(n) 없는 수익률 인용 금지. 아웃라이어(±10% 이상 단일 종목)는 이름 짚어서 분리 서술.
- 결론 먼저, 근거 다음. 사용자는 전략 튜닝 판단권자 — 데이터로 옵션을 제시하되 판단을 대신하지 않는다.
