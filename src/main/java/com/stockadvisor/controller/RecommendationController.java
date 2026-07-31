package com.stockadvisor.controller;

import com.stockadvisor.controller.dto.RecommendationResponse;
import com.stockadvisor.domain.Recommendation;
import com.stockadvisor.service.RecommendationService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 투자 의견(추천) 조회 API.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
@Validated
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * 종목코드에 대한 최신 투자 의견을 산출/반환한다.
     *
     * @param stockCode KRX 종목코드 6자리 (예: 005930)
     */
    @GetMapping("/{stockCode}")
    public RecommendationResponse getRecommendation(
            @PathVariable
            @Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자여야 합니다.")
            String stockCode) {

        Recommendation recommendation = recommendationService.recommend(stockCode);
        return RecommendationResponse.from(recommendation);
    }
}
