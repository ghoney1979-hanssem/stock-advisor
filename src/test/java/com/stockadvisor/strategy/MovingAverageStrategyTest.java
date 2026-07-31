package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 F(이동평균 추세추종): MA20 상향 돌파(이벤트)일 때만 진입. 볼륨 무관, 대조군 미추적, 인버스 제외.
 */
class MovingAverageStrategyTest {

    private final MovingAverageStrategy strategy = new MovingAverageStrategy(true);

    private SignalResult signal(boolean maCrossUp) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, maCrossUp, false, false);
    }

    private StrategyContext ctx(boolean maCrossUp, boolean inverse) {
        return new StrategyContext("005930", signal(maCrossUp), 50, RecommendationType.HOLD, null, inverse, false);
    }

    @Test
    void MA상향돌파면_진입() {
        assertThat(strategy.rejectReason(ctx(true, false))).isNull();
    }

    @Test
    void 돌파_아니면_NO_CROSS() {
        assertThat(strategy.rejectReason(ctx(false, false))).isEqualTo("NO_CROSS");
    }

    @Test
    void 인버스는_INVERSE로_제외() {
        assertThat(strategy.rejectReason(ctx(true, true))).isEqualTo("INVERSE");
    }

    @Test
    void 볼륨_미요구_대조군_미추적() {
        assertThat(strategy.requiresVolumeSpike()).isFalse();
        assertThat(strategy.tracksControl()).isFalse();
    }

    @Test
    void preScreen은_maCrossUp일때만_true() {
        assertThat(strategy.preScreen("005930", signal(true))).isTrue();
        assertThat(strategy.preScreen("005930", signal(false))).isFalse();
    }

    @Test
    void 비활성이면_DISABLED() {
        assertThat(new MovingAverageStrategy(false).rejectReason(ctx(true, false))).isEqualTo("DISABLED");
    }
}
