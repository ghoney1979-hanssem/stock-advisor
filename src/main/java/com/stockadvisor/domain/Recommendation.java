package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 시스템이 산출한 투자 의견(추천) 이력.
 * 추천 사유와 점수를 함께 저장해 추후 검증/백테스트에 활용한다.
 */
@Entity
@Table(name = "recommendation", indexes = {
        @Index(name = "idx_recommendation_stock_code", columnList = "stock_code"),
        @Index(name = "idx_recommendation_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation_type", length = 10, nullable = false)
    private RecommendationType recommendationType;

    /** 추천 점수 (0~100). 높을수록 매수 신호가 강함을 의미. */
    @Column(name = "score", nullable = false)
    private double score;

    /** 추천 산출 근거 (예: 저PER + 매출 성장) */
    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    public Recommendation(String stockCode, RecommendationType recommendationType,
                          double score, String reason) {
        this.stockCode = stockCode;
        this.recommendationType = recommendationType;
        this.score = score;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
