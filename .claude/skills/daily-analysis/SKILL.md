---
name: daily-analysis
description: 주식 자동매매 시스템의 일별 성과·전략 진단 종합 분석. 모든 집계 데이터(게이트·대조군·흐름·MAE·breadth·집행품질)를 수집해 전략 옥석을 가리고, 시스템 보완 조치와 수익률 개선(전략 수정·임계치 조정)을 제안한다.
user-invocable: true
---

# daily-analysis

장 마감 후(또는 요청 시점) 시스템의 **모든 집계 데이터**를 수집해 종합 분석 리포트를 만든다.
산출물은 ① 일별 성과 요약 ② 전략별 진단(옥석) ③ 시스템 보완 조치사항 ④ 수익률 개선 제안(전략 수정·임계치 조정) 4부.

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
| 스윙 | `swing-exit`, `swing-trail-analysis` |
| 장중흐름 | `flow-analysis?lag=30` **및** `lag=60` (둘 다 — lag별 표본이 다름, 아래 함정 참조) |
| 시장 폭 | `market-breadth` |

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

## Phase 4. 조치 제안 (2종 분리)

**A. 시스템 보완 조치사항** — 로그 에러, 오버나잇 잔류 포지션, 미체결→취소 후 재주문 실패, 스냅샷/태깅 누락, 게이트 구조 약점(예: min-samples를 단일일 클러스터가 채우는 문제 → 다중 거래일 분산 요건) 등. 버그성은 근거(로그/DB)와 함께 수정안 제시.

**B. 수익률 개선 제안** — 반드시 데이터 근거와 함께:
- 대조군 hint 기반 필터 완화/강화(해당 reject 사유·표본수·net 차이 명기)
- 흐름 what-if의 "개선 후보" 전략(양쪽 lag에서 일관 + 표본 충분할 때만)
- 진입 시간대 제한(lag60 편향 활용), 손절선 조정(heat-analysis 승자/패자 MAE 분리), 보유시간(exit-timing 곡선)
- 임계치는 env 이름으로 구체 제안(`SIGNAL_*`/`TRADING_*`). ⚠️ **prod 반영은 `docker-compose.prod.yml`의 `environment:`에 해당 키 패스스루가 있어야 함** — 없으면 추가 필요하다고 명시.
- 제안 vs 즉시 적용 구분: 사용자가 "국면/흐름 데이터 쌓이면 판단" 방침이면 손실전략 중단·게이트 변경은 **제안만** 하고 적용하지 않는다.

## 인버스 분리 표기 (2026-07-30 추가)

B·E 등 롱 전략이 인버스 ETF(114800/251340)도 매매하므로, **전략별 섀도우/LIVE 집계에서 인버스 행을 분리**해 표기한다
(`stock_code in ('114800','251340')` 기준). 합산 수치는 오해를 부른다 — "B 부진"의 원인 분해(일반주 신호 문제 vs 인버스 청산 문제)가 목적.
게이트는 entry_market 버킷으로 이미 분리돼 있어 실주문 판정엔 오염 없음(사용자 확인 완료) — 이 규칙은 보고서 표기 전용.

## 스타일

- 수치는 net(왕복비용 0.18%+슬리피지 차감) 여부를 항상 명시. gross/net 혼용 금지.
- 표본수(n) 없는 수익률 인용 금지. 아웃라이어(±10% 이상 단일 종목)는 이름 짚어서 분리 서술.
- 결론 먼저, 근거 다음. 사용자는 전략 튜닝 판단권자 — 데이터로 옵션을 제시하되 판단을 대신하지 않는다.
