package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략별 대형주 상한 판정 — {@link StrategyEvaluator#largeCapReject}.
 *
 * <p>사전등록 "L 시총 (10/01)"이 표본 밖에서 PASS해 L만 시총 ≥1.5조 진입을 보류한다.</p>
 */
@DisplayName("전략별 대형주 상한 판정")
class LargeCapRejectTest {

    private final Map<String, Long> caps = OrderService.parsePositionCaps("REVERSAL_L:15000");

    @Test
    void 지정_전략은_상한_이상이면_보류() {
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 15000, caps)).isTrue();
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 52000, caps)).isTrue();
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 14999, caps)).isFalse();
    }

    @Test
    void 미지정_전략은_영향_없음() {
        assertThat(StrategyEvaluator.largeCapReject("RSI_REVERSAL_G", 52000, caps)).isFalse();
    }

    @Test
    void 시총_미상이나_빈설정이면_통과() {
        // 데이터 결손으로 매매를 막지 않는다(degrade open)
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 0, caps)).isFalse();
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 52000, OrderService.parsePositionCaps(""))).isFalse();
        assertThat(StrategyEvaluator.largeCapReject("REVERSAL_L", 52000, OrderService.parsePositionCaps("REVERSAL_L:abc"))).isFalse();
    }
}
