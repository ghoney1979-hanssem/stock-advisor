package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** N(깊은 눌림) 대조군 강제 기록 판정 — {@link StrategyEvaluator#deepPullbackControl}. L·M과 같은 구조. */
@DisplayName("N 대조군 강제 기록 판정")
class DeepPullbackControlTest {

    @Test
    void 판별_사유는_대조군으로_기록된다() {
        for (String reason : new String[]{"NOT_WEAK", "CHASING", "TOO_DEEP", "SCORE"}) {
            assertThat(StrategyEvaluator.deepPullbackControl("DEEP_PULLBACK_N", reason))
                    .as(reason).isTrue();
        }
    }

    @Test
    void 정체성_사유는_기록하지_않는다() {
        // VOLUME_UP/TOO_SHALLOW/COLLAPSED는 preScreen 경계 — 후보 풀 자체가 아니라 비교로 무의미하다.
        for (String reason : new String[]{"VOLUME_UP", "TOO_SHALLOW", "COLLAPSED", "INVERSE", "DISABLED"}) {
            assertThat(StrategyEvaluator.deepPullbackControl("DEEP_PULLBACK_N", reason))
                    .as(reason).isFalse();
        }
        assertThat(StrategyEvaluator.deepPullbackControl("DEEP_PULLBACK_N", null)).isFalse();
    }

    @Test
    void 다른_전략의_공용사유는_영향받지_않는다() {
        // NOT_WEAK/CHASING/TOO_DEEP/SCORE는 L도 쓰는 공용 사유 — 전략명을 함께 봐야 정책이 조용히 번지지 않는다.
        assertThat(StrategyEvaluator.deepPullbackControl("REVERSAL_L", "NOT_WEAK")).isFalse();
        assertThat(StrategyEvaluator.deepPullbackControl("MEAN_REVERSION_C", "SCORE")).isFalse();
        // 반대로 L의 판정도 N에 번지지 않는다
        assertThat(StrategyEvaluator.reversalControl("DEEP_PULLBACK_N", "NOT_WEAK")).isFalse();
    }
}
