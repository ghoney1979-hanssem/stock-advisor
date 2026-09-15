package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멀티데이 일봉 마크의 <b>D+30 천장 제거</b> — {@code daily_price}에서 누락 마크를 소급 적재한다 (2026-09-15).
 *
 * <p>🔴 <b>왜 필요한가</b>: {@code TradeFollowUpService.collectDailyMarks}는 경로를 KIS 일봉
 * ({@code inquire-daily-price}, FHKST01010400)에서 읽는데 이 API가 <b>~30거래일</b>만 준다. 루프는
 * {@code n <= multidayMaxHoldDays}(현 60)까지 돌지만 D+31부터는 조회 결과에 그 날이 아예 없어
 * <b>영영 안 채워진다</b> — 실측(2026-09-15) {@code max(mark_days)=30}, 30 초과 <b>0건</b>,
 * 정확히 30에 쌓인 것 204건. 보유 상한을 60거래일로 늘린 뒤로 시스템이 <b>자기 규칙을 측정할 수 없는 상태</b>였다.</p>
 *
 * <p>이게 분석만의 문제가 아니다 — {@code StrategyPerformanceGate}가 같은 마크로 multiday net을 채점하고,
 * {@code PositionExitService.simulateMultidayExitPrice}는 <b>트리거가 안 걸리면 마지막 관측일 종가</b>를
 * 청산가로 돌려준다(그 메서드의 {@code @return} 규약). 즉 마크가 짧으면 게이트는 그 짧은 지점을 만기로 착각하고,
 * 그 net으로 <b>실주문</b>을 여닫는다.</p>
 *
 * <p>소스는 {@code daily_price}(네이버 10년 일봉, 평일 16:40 갱신). 미청산 outcome 종목 커버리지는 실측 100%
 * (665/665). KIS 호출은 <b>0</b>이다.</p>
 *
 * <p>⚠️ <b>수정주가 가드가 이 서비스의 핵심</b>({@link DailyMarkOhlcBackfillService}와 같은 함정):
 * {@code daily_price}는 수정주가라 액면분할·병합이 있었으면 마크(KIS 원가격)와 스케일이 다르다. 그대로 넣으면
 * 종가가 1/50인 마크가 생겨 <b>손절이 전부 발사된 것처럼</b> 채점된다. → 기존 마크와 대조해
 * <b>모순이 없는 outcome만</b> 채운다:
 * <ol>
 *   <li>D0(진입일) 마크의 종가가 같은 날 {@code daily_price} 종가와 일치(허용오차 0.5%) — <b>스케일</b> 검증</li>
 *   <li>기존 마크 <b>전부</b>가 같은 거래일 오프셋에서 같은 거래일·같은 종가 — <b>거래일 정렬</b> 검증
 *       (거래정지로 KIS와 소스의 거래일 집합이 어긋나면 D+N이 밀리는데, 이걸 안 보면 조용히 다른 날을 넣는다)</li>
 * </ol>
 * 하나라도 어긋나면 그 outcome은 <b>통째로 건너뛴다</b>(fail-closed — 틀린 마크를 넣느니 짧은 채로 두는 게 낫다).</p>
 *
 * <p>대상은 <b>이미 마크가 있는 outcome</b>으로 한정한다 — 마크 수집 자체가 멀티데이 전략에서만 돌므로
 * 그 집합이 곧 스코프다(전략 csv를 다시 받아 두 곳이 갈라지는 것을 피한다).</p>
 *
 * <p>재실행 안전({@code not exists}로 누락분만 삽입). 미래 거래일은 소스에 없으므로 저절로 안 들어간다.</p>
 */
@Service
public class DailyMarkHistoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DailyMarkHistoryBackfillService.class);

    /** 수정주가 스케일·정렬 불일치 판정 허용오차(종가 대비). {@link DailyMarkOhlcBackfillService}와 같은 값. */
    private static final double CLOSE_MATCH_TOLERANCE = 0.005;

    private final JdbcTemplate jdbcTemplate;
    private final boolean scheduleEnabled;
    private final int maxHoldDays;

    public DailyMarkHistoryBackfillService(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${stockadvisor.trading.multiday-exit.max-hold-days:15}") int maxHoldDays,
            @org.springframework.beans.factory.annotation.Value(
                    "${stockadvisor.daily-mark-history.schedule-enabled:false}") boolean scheduleEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxHoldDays = maxHoldDays;
        this.scheduleEnabled = scheduleEnabled;
    }

    /**
     * 일봉 증분 갱신(16:40) 직후에 돈다 — 소스가 갱신된 뒤라야 <b>어제·오늘 치</b> 마크가 채워진다.
     *
     * <p>이게 정기로 돌면 KIS 창(~30거래일)에 의존하던 수집이 사실상 대체된다. 라이브 후속추적
     * ({@code TradeFollowUpService})은 분 단위 인트라데이 샘플 때문에 그대로 두되, 멀티데이 경로의
     * <b>깊이</b>는 여기서 보장된다 — 그쪽은 구조적으로 D+30을 넘을 수 없기 때문이다.</p>
     */
    @org.springframework.scheduling.annotation.Scheduled(
            cron = "${stockadvisor.daily-mark-history.cron:0 50 16 * * MON-FRI}")
    public void scheduledBackfill() {
        if (!scheduleEnabled) return;
        try {
            backfill(maxHoldDays, "");
        } catch (Exception e) {
            log.warn("[일봉마크] 히스토리 백필 실패(무시): {}", e.toString());
        }
    }

    /**
     * @param inserted    이번 실행에서 새로 적재한 마크 행 수
     * @param maxMarkDays 백필 후 전체 마크의 최대 D+N (천장이 걷혔는지 확인용 — 종전엔 30에서 멈춰 있었다)
     * @param beyond30    D+30을 넘는 마크 행 수(종전 0)
     * @param skipped     수정주가·거래일 정렬 검증에 걸려 건너뛴 outcome 수
     */
    public record Report(int inserted, int maxMarkDays, long beyond30, long skipped) { }

    /**
     * @param maxHoldDays 채울 상한 거래일(보통 {@code trading.multiday-exit.max-hold-days})
     * @param sinceDate   이 진입일(YYYYMMDD) 이후 outcome만 — 빈 값이면 전체
     */
    @Transactional
    public Report backfill(int maxHoldDays, String sinceDate) {
        int hold = Math.max(1, maxHoldDays);
        String since = (sinceDate == null || sinceDate.isBlank()) ? "00000000" : sinceDate;

        // 검증 통과 outcome만 골라 누락 오프셋을 삽입한다. r은 종목별 거래일 순번 — 마크의 D+N과 같은 의미
        // (둘 다 "진입일 이후 N번째 거래일"이고, 휴장은 행이 없으므로 자동 제외된다).
        int inserted = jdbcTemplate.update("""
                with tgt as (
                  select distinct o.id, o.strategy, o.buy_price, o.stock_code, o.alert_date
                    from trade_outcome o
                   where o.alert_date >= ?
                     and exists (select 1 from outcome_daily_mark m where m.outcome_id = o.id)
                ),
                r as (
                  select d.stock_code, d.business_date, d.open_price, d.high_price, d.low_price,
                         d.close_price,
                         row_number() over (partition by d.stock_code order by d.business_date) rn
                    from daily_price d
                   where d.stock_code in (select stock_code from tgt)
                     and d.business_date >= (select min(alert_date) from tgt)
                ),
                base as (
                  select t.id, t.strategy, t.buy_price, t.stock_code, r.rn as base_rn
                    from tgt t
                    join r on r.stock_code = t.stock_code and r.business_date = t.alert_date
                ),
                -- 기존 마크 전부를 같은 오프셋의 소스 행과 대조. d0_ok=스케일, mismatch=정렬·스케일 모순.
                chk as (
                  select b.id,
                         bool_or(m.mark_days = 0) as has_d0,
                         bool_or(m.mark_days = 0
                                 and abs(r2.close_price - m.close_price)
                                     <= greatest(1, m.close_price * ?)) as d0_ok,
                         bool_or(r2.business_date <> m.business_date
                                 or abs(r2.close_price - m.close_price)
                                    > greatest(1, m.close_price * ?)) as mismatch
                    from base b
                    join outcome_daily_mark m on m.outcome_id = b.id
                    join r r2 on r2.stock_code = b.stock_code and r2.rn = b.base_rn + m.mark_days
                   group by b.id
                )
                insert into outcome_daily_mark
                       (outcome_id, strategy, buy_price, mark_days, business_date, close_price,
                        open_price, high_price, low_price)
                select b.id, b.strategy, b.buy_price, f.rn - b.base_rn, f.business_date, f.close_price,
                       nullif(f.open_price, 0), nullif(f.high_price, 0), nullif(f.low_price, 0)
                  from base b
                  join chk c on c.id = b.id and c.has_d0 and c.d0_ok and not c.mismatch
                  join r f on f.stock_code = b.stock_code
                          and f.rn > b.base_rn and f.rn <= b.base_rn + ?
                 where f.close_price > 0
                   and not exists (select 1 from outcome_daily_mark m2
                                    where m2.outcome_id = b.id and m2.mark_days = f.rn - b.base_rn)
                """, since, CLOSE_MATCH_TOLERANCE, CLOSE_MATCH_TOLERANCE, hold);

        Integer maxMark = jdbcTemplate.queryForObject(
                "select coalesce(max(mark_days), 0) from outcome_daily_mark", Integer.class);
        Long beyond = jdbcTemplate.queryForObject(
                "select count(*) from outcome_daily_mark where mark_days > 30", Long.class);
        Long skipped = jdbcTemplate.queryForObject("""
                select count(*) from (
                  select o.id from trade_outcome o
                    join outcome_daily_mark m on m.outcome_id = o.id
                   where o.alert_date >= ?
                   group by o.id
                ) x
                 where not exists (
                   select 1 from daily_price d
                     join trade_outcome o2 on o2.id = x.id
                    where d.stock_code = o2.stock_code and d.business_date = o2.alert_date)
                """, Long.class, since);

        int mm = maxMark == null ? 0 : maxMark;
        long b30 = beyond == null ? 0 : beyond;
        long sk = skipped == null ? 0 : skipped;
        log.info("일봉 마크 히스토리 백필 — 적재 {}건, 최대 D+{}, D+30 초과 {}행, 소스 미보유 outcome {}건",
                inserted, mm, b30, sk);
        return new Report(inserted, mm, b30, sk);
    }
}
