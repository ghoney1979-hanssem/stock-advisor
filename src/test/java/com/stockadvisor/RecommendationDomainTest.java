package com.stockadvisor;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.controller.dto.RecommendationResponse;
import com.stockadvisor.domain.Recommendation;
import com.stockadvisor.domain.RecommendationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인프라(PostgreSQL/Redis) 없이 검증 가능한 도메인/매핑 단위 테스트.
 * 전체 컨텍스트 로딩 테스트는 DB·Redis 구동 후 별도 통합 테스트로 추가한다.
 */
class RecommendationDomainTest {

    @Test
    void 추천_응답에는_면책조항이_포함된다() {
        Recommendation recommendation = Recommendation.builder()
                .stockCode("005930")
                .recommendationType(RecommendationType.BUY)
                .score(85.0)
                .reason("테스트 사유")
                .build();

        RecommendationResponse response = RecommendationResponse.from(recommendation);

        assertThat(response.opinion()).isEqualTo("매수");
        assertThat(response.disclaimer()).isEqualTo(Disclaimer.INVESTMENT_DISCLAIMER);
        assertThat(response.disclaimer()).contains("투자에 대한 최종 판단과 책임은 투자자 본인");
    }
}
