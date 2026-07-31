package com.stockadvisor.service;

import com.stockadvisor.config.properties.AdaptiveStopProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전략별 적응형 손절 — 승자 MAE worst10 채택·클램프·fail-closed(표본부족/비활성→고정) 검증.
 */
class StrategyStopProviderTest {

    private final MaeAnalysisService mae = mock(MaeAnalysisService.class);

    private AdaptiveStopProperties props(boolean enabled) {
        return new AdaptiveStopProperties(enabled, 30, 3.0, 10.0, 30);
    }

    private StrategyStopProvider svc(boolean enabled, double defaultStop, List<MaeAnalysisService.StrategyHeat> heats) {
        lenient().when(mae.analyze()).thenReturn(heats);
        return new StrategyStopProvider(mae, props(enabled), defaultStop);
    }

    /** 승자 worst10=worst10, n=winners 인 heat. */
    private MaeAnalysisService.StrategyHeat heat(String strat, double worst10, int winners) {
        var w = new MaeAnalysisService.HeatGroup("승자", winners, -3.0, -3.0, worst10, 5.0);
        var l = new MaeAnalysisService.HeatGroup("패자", 5, -8.0, -8.0, -9.0, 2.0);
        return new MaeAnalysisService.StrategyHeat(strat, "90분", w, l, List.of(), "");
    }

    @Test
    void 표본충분이면_승자worst10_채택() {
        StrategyStopProvider p = svc(true, 7.0, List.of(heat("VOLUME_LEADING_B", -5.5, 40)));
        assertThat(p.stopPct("VOLUME_LEADING_B")).isCloseTo(5.5, within(0.01));   // 승자 worst10 −5.5 → 5.5% 손절
    }

    @Test
    void 표본부족이면_고정값_faIl_closed() {
        StrategyStopProvider p = svc(true, 7.0, List.of(heat("MOMENTUM_A", -4.0, 10)));   // n10<30
        assertThat(p.stopPct("MOMENTUM_A")).isCloseTo(7.0, within(0.01));   // 채택 안 함 → 고정 −7%
    }

    @Test
    void 클램프_상하한() {
        StrategyStopProvider p = svc(true, 7.0, List.of(
                heat("INDEX_RELATIVE_D", -12.0, 40),   // 과도 깊음 → max 10
                heat("VALUE_REVERSAL_J", -1.0, 40)));   // 과도 얕음 → min 3
        assertThat(p.stopPct("INDEX_RELATIVE_D")).isCloseTo(10.0, within(0.01));
        assertThat(p.stopPct("VALUE_REVERSAL_J")).isCloseTo(3.0, within(0.01));
    }

    @Test
    void 적응형_비활성이면_고정값() {
        StrategyStopProvider p = svc(false, 7.0, List.of(heat("VOLUME_LEADING_B", -5.5, 40)));
        assertThat(p.stopPct("VOLUME_LEADING_B")).isCloseTo(7.0, within(0.01));
    }

    @Test
    void 마스터_비활성이면_0() {
        StrategyStopProvider p = svc(true, 0.0, List.of(heat("VOLUME_LEADING_B", -5.5, 40)));
        assertThat(p.stopPct("VOLUME_LEADING_B")).isEqualTo(0.0);   // catastrophic-stop-pct=0 → 손절 안 함
    }

    @Test
    void 미지_전략은_고정값() {
        StrategyStopProvider p = svc(true, 7.0, List.of(heat("VOLUME_LEADING_B", -5.5, 40)));
        assertThat(p.stopPct("UNKNOWN_X")).isCloseTo(7.0, within(0.01));   // 분석에 없는 전략 → 고정
    }
}
