package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 E(신고가 돌파) 진입 판정 검증. lookback=20, minScore=40, buffer는 케이스별.
 */
class BreakoutStrategyTest {

    private static SignalProperties props(double buffer) {
        return new SignalProperties(
                20, 2.0, 1.5, 40.0, Duration.ofHours(1),
                5, 0.3, 1.5,
                0.0, 1.0, 8.0,             // volumeLeading: min=0, max=1, inverse-max=8
                3.0, 12.0, 40.0, true,     // meanReversion
                2.0, 12.0, 40.0, true,     // indexRelative
                20, 40.0, buffer,          // breakout: lookback=20, minScore=40, buffer
                1000, "09:00", "15:20", true);
    }

    private static SignalResult signal(long price, long priorHigh, boolean spike) {
        return new SignalResult(3.0, 0.0, price, 1_000_000, spike, false, false, priorHigh, false, false, false);
    }

    private static StrategyContext ctx(long price, long priorHigh, boolean spike, double score) {
        return new StrategyContext("005930", signal(price, priorHigh, spike), score, RecommendationType.HOLD, null, false, false);
    }

    @Test
    void 신고가_돌파_거래량_점수_충족이면_진입() {
        BreakoutStrategy s = new BreakoutStrategy(props(0.0));
        assertThat(s.rejectReason(ctx(11_000, 10_000, true, 50))).isNull();
        assertThat(s.shouldEnter(ctx(11_000, 10_000, true, 50))).isTrue();
    }

    @Test
    void 미돌파면_NOT_BREAKOUT() {
        BreakoutStrategy s = new BreakoutStrategy(props(0.0));
        assertThat(s.rejectReason(ctx(9_900, 10_000, true, 50))).isEqualTo("NOT_BREAKOUT");
    }

    @Test
    void 직전최고가_없으면_NO_HIGH() {
        BreakoutStrategy s = new BreakoutStrategy(props(0.0));
        assertThat(s.rejectReason(ctx(11_000, 0, true, 50))).isEqualTo("NO_HIGH");
    }

    @Test
    void 거래량_미급증이면_NO_VOLUME() {
        BreakoutStrategy s = new BreakoutStrategy(props(0.0));
        assertThat(s.rejectReason(ctx(11_000, 10_000, false, 50))).isEqualTo("NO_VOLUME");
    }

    @Test
    void 점수미달이면_SCORE() {
        BreakoutStrategy s = new BreakoutStrategy(props(0.0));
        assertThat(s.rejectReason(ctx(11_000, 10_000, true, 30))).isEqualTo("SCORE");
    }

    @Test
    void 버퍼_적용시_여유_미달이면_NOT_BREAKOUT() {
        BreakoutStrategy s = new BreakoutStrategy(props(1.0));   // 직전최고가 ×1.01 이상 요구
        assertThat(s.rejectReason(ctx(10_050, 10_000, true, 50))).isEqualTo("NOT_BREAKOUT"); // +0.5% < +1%
        assertThat(s.rejectReason(ctx(10_150, 10_000, true, 50))).isNull();                  // +1.5% ≥ +1%
    }
}
