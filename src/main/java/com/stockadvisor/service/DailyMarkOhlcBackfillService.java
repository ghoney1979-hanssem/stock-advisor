package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멀티데이 일봉 마크의 <b>시·고·저가 소급 백필</b> (2026-09-10).
 *
 * <p><b>왜 필요한가</b>: 게이트 net 채점이 라이브와 같은 청산 규칙(손절·트레일·상한가)을 쓰게 되면서
 * <b>장중 저가</b>가 필요해졌다 — 손절은 종가가 아니라 장중에 발사되기 때문이다. 종전 마크는 종가만 있어
 * 구표본 전체가 "손절 없는 세계"로 채점된다(= 라이브가 실제로 확정한 손실이 net에서 빠진다).</p>
 *
 * <p>소스는 {@code daily_price}(네이버 10년 일봉, 워치리스트 전 종목 OHLC, 평일 16:40 갱신) —
 * KIS 일봉은 ~30거래일 창이라 오래된 마크를 못 덮는다. 마크에 종목코드가 없으므로 {@code trade_outcome}과 조인한다.</p>
 *
 * <p>⚠️ <b>수정주가 가드가 핵심</b>: {@code daily_price}는 수정주가라 액면분할·병합이 있었으면 마크(KIS 원가격)와
 * 스케일이 다르다. 그대로 넣으면 저가가 매수가의 1/50 같은 값이 돼 <b>손절이 전부 발사된 것처럼</b> 보인다.
 * → 같은 날 <b>종가가 일치할 때만</b> 채운다(허용오차 0.5%). 불일치분은 건드리지 않고 남긴다(종가 판정으로 degrade).</p>
 *
 * <p>재실행 안전(이미 채워진 행은 {@code high_price is null} 조건에서 빠진다).</p>
 */
@Service
public class DailyMarkOhlcBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DailyMarkOhlcBackfillService.class);

    /** 수정주가 스케일 불일치 판정 허용오차(종가 대비). */
    private static final double CLOSE_MATCH_TOLERANCE = 0.005;

    private final JdbcTemplate jdbcTemplate;

    public DailyMarkOhlcBackfillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param filled        이번 실행에서 채운 행 수
     * @param remaining     아직 시·고·저가가 없는 행 수(일봉 미보유·수정주가 불일치 — 종가 판정으로 degrade)
     * @param total         전체 마크 행 수
     * @param coveragePct   시·고·저가 보유 비율(%)
     */
    public record Report(int filled, long remaining, long total, double coveragePct) { }

    @Transactional
    public Report backfill() {
        int filled = jdbcTemplate.update("""
                update outcome_daily_mark m
                   set open_price = d.open_price, high_price = d.high_price, low_price = d.low_price
                  from trade_outcome o, daily_price d
                 where o.id = m.outcome_id
                   and d.stock_code = o.stock_code
                   and d.business_date = m.business_date
                   and m.high_price is null
                   and d.high_price > 0 and d.low_price > 0 and d.close_price > 0
                   and abs(d.close_price - m.close_price) <= greatest(1, m.close_price * ?)
                """, CLOSE_MATCH_TOLERANCE);
        Long total = jdbcTemplate.queryForObject("select count(*) from outcome_daily_mark", Long.class);
        Long remaining = jdbcTemplate.queryForObject(
                "select count(*) from outcome_daily_mark where high_price is null", Long.class);
        long t = total == null ? 0 : total;
        long r = remaining == null ? 0 : remaining;
        double coverage = t == 0 ? 0 : Math.round((t - r) * 10000.0 / t) / 100.0;
        log.info("일봉 마크 OHLC 백필 — 채움 {}건, 잔여 {}건/{} (커버리지 {}%)", filled, r, t, coverage);
        return new Report(filled, r, t, coverage);
    }
}
