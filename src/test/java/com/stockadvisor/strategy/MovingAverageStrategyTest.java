package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 F(이동평균 추세추종): MA20 상향 돌파(이벤트)일 때만 진입. 볼륨 무관, 대조군 미추적, 인버스 제외.
 * 보완 필터 ①분봉 신선도(requireFresh) ②돌파 버퍼(breakoutBufferPct)는 기본 off — 켜면 추가 컷.
 */
class MovingAverageStrategyTest {

    private final MovingAverageStrategy strategy = new MovingAverageStrategy(true, false, 0.0);

    private SignalResult signal(boolean maCrossUp) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, maCrossUp, false, false);
    }

    /** maCrossUp + 신선도/이격까지 지정하는 전체 신호(보완 필터 검증용). */
    private SignalResult signalFull(boolean maCrossUp, boolean maBreakoutFresh, double maDistPct) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, maCrossUp, false, false,
                0, 0, 0, 0, maBreakoutFresh, maDistPct);
    }

    private StrategyContext ctx(boolean maCrossUp, boolean inverse) {
        return new StrategyContext("005930", signal(maCrossUp), 50, RecommendationType.HOLD, null, inverse, false);
    }

    private StrategyContext ctxFull(SignalResult sig) {
        return new StrategyContext("005930", sig, 50, RecommendationType.HOLD, null, false, false);
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
        assertThat(new MovingAverageStrategy(false, false, 0.0).rejectReason(ctx(true, false))).isEqualTo("DISABLED");
    }

    // ── 보완 필터 ① 분봉 신선도(requireFresh) ──
    @Test
    void 신선도필터_켜짐_식은돌파면_NOT_FRESH() {
        MovingAverageStrategy fresh = new MovingAverageStrategy(true, true, 0.0);
        assertThat(fresh.rejectReason(ctxFull(signalFull(true, false, 1.0)))).isEqualTo("NOT_FRESH");
    }

    @Test
    void 신선도필터_켜짐_살아있는돌파면_진입() {
        MovingAverageStrategy fresh = new MovingAverageStrategy(true, true, 0.0);
        assertThat(fresh.rejectReason(ctxFull(signalFull(true, true, 1.0)))).isNull();
    }

    @Test
    void 신선도필터_꺼짐이_기본_식어도_진입() {
        assertThat(strategy.rejectReason(ctxFull(signalFull(true, false, 0.0)))).isNull();
    }

    // ── 보완 필터 ② 돌파 강도 버퍼(breakoutBufferPct) ──
    @Test
    void 버퍼필터_켜짐_약한돌파면_WEAK_BREAKOUT() {
        MovingAverageStrategy buf = new MovingAverageStrategy(true, false, 1.0);   // MA20 대비 +1%p 이상 요구
        assertThat(buf.rejectReason(ctxFull(signalFull(true, false, 0.4)))).isEqualTo("WEAK_BREAKOUT");
    }

    @Test
    void 버퍼필터_켜짐_강한돌파면_진입() {
        MovingAverageStrategy buf = new MovingAverageStrategy(true, false, 1.0);
        assertThat(buf.rejectReason(ctxFull(signalFull(true, false, 1.5)))).isNull();
    }

    @Test
    void 버퍼가_신선도보다_먼저_판정된다() {
        // 둘 다 켜짐 + 약한돌파 + 식음 → 버퍼(②)가 먼저라 WEAK_BREAKOUT
        MovingAverageStrategy both = new MovingAverageStrategy(true, true, 1.0);
        assertThat(both.rejectReason(ctxFull(signalFull(true, false, 0.4)))).isEqualTo("WEAK_BREAKOUT");
    }
}
