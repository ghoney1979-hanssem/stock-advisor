package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** M(조용한 추세지속) 대조군 강제 기록 판정 — {@link StrategyEvaluator#quietContinuationControl}. L과 같은 구조. */
@DisplayName("M 대조군 강제 기록 판정")
class QuietContinuationControlTest {

    @Test
    void 판별_사유는_대조군으로_기록된다() {
        for (String reason : new String[]{"WEAK_5D", "RAN_5D", "DOWN_DAY", "CHASING", "SCORE"}) {
            assertThat(StrategyEvaluator.quietContinuationControl("QUIET_CONTINUATION_M", reason))
                    .as(reason).isTrue();
        }
    }

    @Test
    void 정체성_사유는_기록하지_않는다() {
        // VOLUME_UP/BELOW_MA/EXTENDED는 preScreen 경계 — 급증 종목 전량이 대조군이 되면 부담만 늘고 비교로는 무의미.
        for (String reason : new String[]{"VOLUME_UP", "BELOW_MA", "EXTENDED", "INVERSE", "DISABLED"}) {
            assertThat(StrategyEvaluator.quietContinuationControl("QUIET_CONTINUATION_M", reason))
                    .as(reason).isFalse();
        }
        assertThat(StrategyEvaluator.quietContinuationControl("QUIET_CONTINUATION_M", null)).isFalse();
    }

    @Test
    void 다른_전략의_공용사유는_영향받지_않는다() {
        assertThat(StrategyEvaluator.quietContinuationControl("REVERSAL_L", "CHASING")).isFalse();
        assertThat(StrategyEvaluator.quietContinuationControl("MEAN_REVERSION_C", "SCORE")).isFalse();
    }
}
