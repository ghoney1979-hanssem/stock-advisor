package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 H(변동성 수축 돌파): NR7 수축 돌파(이벤트)일 때만 진입. 볼륨 무관, 대조군 미추적, 인버스 제외.
 * 보완 필터: 돌파 확인장치(requireConfirm) — 기본 off, 켜면 분봉 신선도(squeezeBreakoutFresh) 미충족 시 NOT_CONFIRMED.
 */
class VolatilitySqueezeStrategyTest {

    private final VolatilitySqueezeStrategy strategy = new VolatilitySqueezeStrategy(true, false);
    private final VolatilitySqueezeStrategy confirm = new VolatilitySqueezeStrategy(true, true);

    private SignalResult signal(boolean squeeze) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, false, false, squeeze);
    }

    /** 18-인자 canonical — squeezeBreakoutFresh 지정용. */
    private SignalResult signalFull(boolean squeeze, boolean squeezeFresh) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, false, false, squeeze,
                0, 0, 0, 0, false, 0, squeezeFresh);
    }

    private StrategyContext ctx(boolean squeeze, boolean inverse) {
        return new StrategyContext("005930", signal(squeeze), 50, RecommendationType.HOLD, null, inverse, false);
    }

    private StrategyContext ctxFull(SignalResult sig) {
        return new StrategyContext("005930", sig, 50, RecommendationType.HOLD, null, false, false);
    }

    @Test
    void 수축돌파면_진입() {
        assertThat(strategy.rejectReason(ctx(true, false))).isNull();
    }

    @Test
    void 돌파_아니면_NO_SQUEEZE() {
        assertThat(strategy.rejectReason(ctx(false, false))).isEqualTo("NO_SQUEEZE");
    }

    @Test
    void 인버스는_제외() {
        assertThat(strategy.rejectReason(ctx(true, true))).isEqualTo("INVERSE");
    }

    @Test
    void 볼륨_미요구_대조군_미추적_preScreen일치() {
        assertThat(strategy.requiresVolumeSpike()).isFalse();
        assertThat(strategy.tracksControl()).isFalse();
        assertThat(strategy.preScreen("005930", signal(true))).isTrue();
        assertThat(strategy.preScreen("005930", signal(false))).isFalse();
    }

    // ── 보완 필터: 돌파 확인장치(requireConfirm) ──
    @Test
    void 확인필터_off면_신선도_무관_진입() {
        assertThat(strategy.rejectReason(ctxFull(signalFull(true, false)))).isNull();   // off → 페이드여도 진입(현행)
    }

    @Test
    void 확인필터_on_신선도_미충족이면_NOT_CONFIRMED() {
        assertThat(confirm.rejectReason(ctxFull(signalFull(true, false)))).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void 확인필터_on_신선도_충족이면_진입() {
        assertThat(confirm.rejectReason(ctxFull(signalFull(true, true)))).isNull();
    }
}
