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
 * 가상매수 후 특정 보유시간(mark) 시점의 가격 샘플. 보유시간별 수익 곡선 산출용.
 * 분석 편의를 위해 strategy/buyPrice 를 비정규화해 함께 저장한다.
 *
 * @see com.stockadvisor.service.ExitTimingService
 */
@Entity
@Table(name = "outcome_sample", indexes = {
        @Index(name = "idx_sample_outcome_mark", columnList = "outcome_id, mark_minutes", unique = true),
        @Index(name = "idx_sample_strategy_mark", columnList = "strategy, mark_minutes")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutcomeSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outcome_id", nullable = false)
    private Long outcomeId;

    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    @Column(name = "buy_price", nullable = false)
    private long buyPrice;

    /** 보유시간(분). -1 = 당일종가(EOD). */
    @Column(name = "mark_minutes", nullable = false)
    private int markMinutes;

    /** 해당 시점 가격 */
    @Column(name = "price", nullable = false)
    private long price;

    /** 해당 시점 VWAP(가중평균주가) — 신호 청산 판정용 */
    @Column(name = "vwap")
    private Double vwap;

    /** 해당 시점 누적 거래량 */
    @Column(name = "acml_volume")
    private Long acmlVolume;

    /** 마크 시점 지수 mom30(%) — 보유중 관찰/조건부 청산 검증용(2026-07-23, 캐시 재사용 저장). */
    @Column(name = "idx_mom30")
    private Double idxMom30;

    /** 마크 시점 시장폭 상승비율(%) — 신선(40분내)할 때만 저장. */
    @Column(name = "breadth_pct")
    private Double breadthPct;

    public OutcomeSample(Long outcomeId, String strategy, long buyPrice, int markMinutes, long price) {
        this.outcomeId = outcomeId;
        this.strategy = strategy;
        this.buyPrice = buyPrice;
        this.markMinutes = markMinutes;
        this.price = price;
    }

    /** 신호 지표(VWAP·거래량) 함께 기록. */
    public void recordContext(Double idxMom30, Double breadthPct) {
        this.idxMom30 = idxMom30;
        this.breadthPct = breadthPct;
    }

    public void recordSignals(Double vwap, Long acmlVolume) {
        this.vwap = vwap;
        this.acmlVolume = acmlVolume;
    }

    /** 매수가 대비 수익률(%) */
    public double returnPct() {
        return buyPrice > 0 ? (double) (price - buyPrice) / buyPrice * 100 : 0;
    }
}
