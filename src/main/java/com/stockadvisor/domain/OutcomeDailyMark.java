package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 멀티데이(2-3주) 보유 전략의 <b>일봉 종가 경로</b> 마크. (2026-08-07, Phase 1 — 측정 전용)
 *
 * <p>인트라데이 마크({@link OutcomeSample})와 달리 <b>거래일(D+N) 종가</b>를 한 행씩 적재한다.
 * D+15까지 컬럼을 늘리는 대신 별도 테이블에 행으로 쌓아, 기존 인트라데이 분석
 * (exit-comparison/exit-timing)을 전혀 건드리지 않고 멀티데이 청산 시뮬(Phase 2)의
 * 입력을 forward 수집한다. 백테스트 불가(KIS 일봉 ~30거래일·과거 분봉 없음)라 forward-only.</p>
 *
 * <p>{@code strategy}/{@code buyPrice} 는 시뮬 편의를 위한 비정규화. 대조군은 수집 대상이 아니다.</p>
 */
@Entity
@Table(name = "outcome_daily_mark", indexes = {
        @Index(name = "idx_daily_mark_outcome_day", columnList = "outcome_id, mark_days", unique = true),
        @Index(name = "idx_daily_mark_strategy_day", columnList = "strategy, mark_days")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutcomeDailyMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outcome_id", nullable = false)
    private Long outcomeId;

    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    @Column(name = "buy_price", nullable = false)
    private long buyPrice;

    /** 진입일 이후 거래일 수. 0=진입일(D0) 종가, N=D+N 거래일 종가(휴장 제외 카운트). */
    @Column(name = "mark_days", nullable = false)
    private int markDays;

    /** 해당 마크의 실제 거래일(YYYYMMDD) — 하루 1회 수집 게이트 + 시뮬 정렬용. */
    @Column(name = "business_date", length = 8, nullable = false)
    private String businessDate;

    /** 해당 거래일 종가. */
    @Column(name = "close_price", nullable = false)
    private long closePrice;

    public OutcomeDailyMark(Long outcomeId, String strategy, long buyPrice,
                            int markDays, String businessDate, long closePrice) {
        this.outcomeId = outcomeId;
        this.strategy = strategy;
        this.buyPrice = buyPrice;
        this.markDays = markDays;
        this.businessDate = businessDate;
        this.closePrice = closePrice;
    }

    /** 매수가 대비 수익률(%) */
    public double returnPct() {
        return buyPrice > 0 ? (double) (closePrice - buyPrice) / buyPrice * 100 : 0;
    }
}
