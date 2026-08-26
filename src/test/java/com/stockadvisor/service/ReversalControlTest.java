package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L(눌림 반전) 대조군 강제 기록 판정 — {@link StrategyEvaluator#reversalControl}.
 *
 * <p>L이 LIVE로 편입되면서 물어야 할 질문이 "MA20 아래가 좋은가"(유니버스 lift)에서
 * <b>"L이 고른 MA20 아래가 L이 거른 MA20 아래보다 나은가"</b>로 바뀌었고, 후자는 대조군으로만 잰다.</p>
 */
@DisplayName("L 대조군 강제 기록 판정")
class ReversalControlTest {

    @Test
    void 판별_사유는_대조군으로_기록된다() {
        // L의 후보 풀(눌림+저거래량) 안에서 L이 실제로 선별에 쓴 축 — ENTERED와 직접 비교 가능해야 한다.
        for (String reason : new String[]{"NOT_WEAK", "CHASING", "TOO_DEEP", "BROKEN_TREND", "SCORE"}) {
            assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", reason))
                    .as(reason).isTrue();
        }
    }

    @Test
    void 정체성_사유는_기록하지_않는다() {
        // VOLUME_UP은 "거래량이 급증해서 안 샀다" = L의 후보 풀 자체가 아니다. 급증 종목 전량이 대조군이 되면
        // 하루 ~700행이 느는데 비교로서는 무의미하고, 그 질문은 universe-analysis와 B의 WEAK_VOLUME이 이미 답한다.
        assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", "VOLUME_UP")).isFalse();
        // NOT_PULLBACK은 preScreen 경계라 급증 종목에서만 발생 — 같은 이유로 제외.
        assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", "NOT_PULLBACK")).isFalse();
        assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", "INVERSE")).isFalse();
        assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", "DISABLED")).isFalse();
    }

    @Test
    void 다른_전략의_공용사유는_영향받지_않는다() {
        // SCORE·TOO_DEEP은 여러 전략이 쓰는 공용 사유라, 전략명을 안 보면 전 전략의 대조군 정책이 조용히 바뀐다.
        assertThat(StrategyEvaluator.reversalControl("MEAN_REVERSION_C", "SCORE")).isFalse();
        assertThat(StrategyEvaluator.reversalControl("INDEX_RELATIVE_D", "TOO_DEEP")).isFalse();
        assertThat(StrategyEvaluator.reversalControl("VALUE_REVERSAL_J", "SCORE")).isFalse();
    }

    @Test
    void 진입한_경우는_대조군이_아니다() {
        assertThat(StrategyEvaluator.reversalControl("REVERSAL_L", null)).isFalse();
    }
}
