package com.stockadvisor.controller.dto;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.domain.Recommendation;

import java.time.Instant;

/**
 * 추천 API 응답. 자본시장법 준수를 위해 모든 응답에 면책조항을 포함한다.
 */
public record RecommendationResponse(
        String stockCode,
        String opinion,        // 매수/중립/매도
        double score,
        String reason,
        Instant createdAt,
        String disclaimer
) {

    public static RecommendationResponse from(Recommendation r) {
        return new RecommendationResponse(
                r.getStockCode(),
                r.getRecommendationType().korean(),
                r.getScore(),
                r.getReason(),
                r.getCreatedAt(),
                Disclaimer.INVESTMENT_DISCLAIMER
        );
    }
}
