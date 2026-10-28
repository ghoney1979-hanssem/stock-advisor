package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략별 저PBR 하한 판정 — {@link StrategyEvaluator#lowPbrReject}.
 *
 * <p>사전등록 "J PBR (10/01)"이 표본 밖에서 PASS해 J만 PBR ≤0.64 진입을 보류한다.</p>
 */
@DisplayName("전략별 저PBR 하한 판정")
class LowPbrRejectTest {

    private final Map<String, Double> floors = StrategyEvaluator.parseDoubleCaps("VALUE_REVERSAL_J:0.64");

    @Test
    void 지정_전략은_하한_이하면_보류() {
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.64, floors)).isTrue();
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.31, floors)).isTrue();
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.65, floors)).isFalse();
    }

    @Test
    void 미지정_전략은_영향_없음() {
        assertThat(StrategyEvaluator.lowPbrReject("REVERSAL_L", 0.3, floors)).isFalse();
    }

    @Test
    void PBR_미상이나_빈설정_오타면_통과() {
        // 데이터 결손·설정 오타로 매매를 막지 않는다(degrade open)
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0, floors)).isFalse();
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.3, StrategyEvaluator.parseDoubleCaps(""))).isFalse();
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.3, StrategyEvaluator.parseDoubleCaps("VALUE_REVERSAL_J:abc"))).isFalse();
        assertThat(StrategyEvaluator.lowPbrReject("VALUE_REVERSAL_J", 0.3, StrategyEvaluator.parseDoubleCaps("VALUE_REVERSAL_J:-1"))).isFalse();
    }
}
