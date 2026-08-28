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

import java.time.Instant;

/**
 * 멀티데이 슬리브의 <b>리밸런싱 시점 선정 기록</b> — 섀도우 포워드 검증용.
 *
 * <p><b>왜 선정만 저장하고 성과는 저장하지 않나</b>: 성과는 {@code daily_price}에서 언제든 재계산되지만,
 * <b>"그 시점에 무엇을 골랐는가"는 사후 재구성이 불가능</b>하다 — 유니버스가 계속 변하고(신규 상장·폐지),
 * 일봉 적재 범위도 달라지기 때문이다. 백테스트에서 반복해서 죽은 것이 바로 이 "사후에 다시 그린" 결과였다.</p>
 *
 * <p>⚠️ 이 기록은 <b>실주문과 무관</b>하다(섀도우). 96조합 백테스트에서 유일하게 살아남은 축이지만
 * holdout을 3회 소진해 더는 백테스트로 검증할 수 없어, 남은 검증 수단이 실시간 포워드뿐이다.</p>
 */
@Entity
@Table(name = "sleeve_selection", indexes = {
        @Index(name = "idx_sleeve_sel_key", columnList = "rebalance_date, strategy, stock_code", unique = true),
        @Index(name = "idx_sleeve_sel_strategy", columnList = "strategy, rebalance_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SleeveSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 리밸런싱 기준 거래일(YYYYMMDD) — 이 날 종가로 산 것으로 본다. */
    @Column(name = "rebalance_date", length = 8, nullable = false)
    private String rebalanceDate;

    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    /** 선정 축 값 기준 순위(1이 최상위). 상위 편중 여부 진단용. */
    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "entry_price", nullable = false)
    private long entryPrice;

    /** 선정 축 값(52주 고가 대비 비율 등) — 사후에 "얼마나 근접한 것을 샀나"를 볼 수 있게. */
    @Column(name = "axis_value")
    private Double axisValue;

    /** 계획 보유 개월(백테스트에서 채택한 3개월). */
    @Column(name = "hold_months", nullable = false)
    private int holdMonths;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SleeveSelection(String rebalanceDate, String strategy, String stockCode,
                           int rankNo, long entryPrice, Double axisValue, int holdMonths) {
        this.rebalanceDate = rebalanceDate;
        this.strategy = strategy;
        this.stockCode = stockCode;
        this.rankNo = rankNo;
        this.entryPrice = entryPrice;
        this.axisValue = axisValue;
        this.holdMonths = holdMonths;
        this.createdAt = Instant.now();
    }
}
