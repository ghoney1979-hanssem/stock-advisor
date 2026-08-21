package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 J(저평가 반등): RSI 과매도 상향돌파(촉매) + 업종 대비 저평가일 때만 진입. 볼륨 무관, 대조군 미추적, 인버스 제외.
 */
class ValueReversalStrategyTest {

    private final ValueReversalStrategy strategy = new ValueReversalStrategy(true, false);

    private SignalResult signal(boolean rsiCrossUp) {
        return new SignalResult(1.0, 0.0, 10_000, 1_000_000, false, false, false, 0, false, rsiCrossUp, false);
    }

    private StrategyContext ctx(boolean rsiCrossUp, boolean undervalued, boolean inverse) {
        return new StrategyContext("005930", signal(rsiCrossUp), 50, RecommendationType.HOLD, null, inverse, undervalued);
    }

    @Test
    void 저평가_RSI반등이면_진입() {
        assertThat(strategy.rejectReason(ctx(true, true, false))).isNull();
    }

    @Test
    void RSI반등_없으면_NO_RSI_CROSS() {
        assertThat(strategy.rejectReason(ctx(false, true, false))).isEqualTo("NO_RSI_CROSS");
    }

    @Test
    void 저평가_아니면_NOT_UNDERVALUED() {
        assertThat(strategy.rejectReason(ctx(true, false, false))).isEqualTo("NOT_UNDERVALUED");
    }

    @Test
    void 인버스는_제외() {
        assertThat(strategy.rejectReason(ctx(true, true, true))).isEqualTo("INVERSE");
    }

    @Test
    void 볼륨_미요구_대조군_미추적_preScreen일치() {
        assertThat(strategy.requiresVolumeSpike()).isFalse();
        assertThat(strategy.tracksControl()).isFalse();
        assertThat(strategy.preScreen("005930", signal(true))).isTrue();
        assertThat(strategy.preScreen("005930", signal(false))).isFalse();
    }

    @Test
    void 흐름필터_켜면_흐름하락에서_FLOW_DOWN_이고_미산출은_통과() {
        ValueReversalStrategy flowOn = new ValueReversalStrategy(true, true);
        // 흐름↓(mom30 < 0) → 보류
        assertThat(flowOn.rejectReason(flowCtx(-0.5))).isEqualTo("FLOW_DOWN");
        // 흐름↑(mom30 ≥ 0) → 진입
        assertThat(flowOn.rejectReason(flowCtx(0.0))).isNull();
        // 흐름 미산출(개장 ~30분·조회실패) → degrade open(필터 미적용)
        assertThat(flowOn.rejectReason(flowCtx(null))).isNull();
        // 기본(off)이면 흐름↓여도 종전대로 진입
        assertThat(strategy.rejectReason(flowCtx(-0.5))).isNull();
    }

    /** 다른 조건은 모두 통과시키고 흐름만 바꾼 ctx(흐름이 마지막 게이트임을 확인). */
    private StrategyContext flowCtx(Double indexMom30) {
        return new StrategyContext("005930", signal(true), 50, RecommendationType.HOLD,
                null, false, true, null, indexMom30);
    }
}
