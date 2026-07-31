package com.stockadvisor.service;

import com.stockadvisor.config.properties.AdaptiveExitProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.TradingMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 적응형 보유시간 선택: 표본 충분한 마크 중 평균 net 수익 최대 마크 채택, 부족하면 고정값 fallback.
 */
class StrategyHoldTimeProviderTest {

    private static final int FIXED_HOLD = 60;

    private TradingPolicyProperties policy() {
        return new TradingPolicyProperties(true, TradingMode.DRY_RUN, 10.0, 0, 50_000, 10,
                "15:20", FIXED_HOLD, true, List.of(), 3, 5, 0);
    }

    private AdaptiveExitProperties props(boolean enabled, int minSamples, int maxHold) {
        return new AdaptiveExitProperties(enabled, minSamples, 30, maxHold);
    }

    private ExitTimingService.MarkStat mark(int minutes, int samples, double avgReturn) {
        return new ExitTimingService.MarkStat(minutes + "분", minutes, samples, avgReturn, 50.0);
    }

    private ExitTimingService.StrategyExitTiming timing(String strategy, ExitTimingService.MarkStat... marks) {
        return new ExitTimingService.StrategyExitTiming(strategy, 0, List.of(marks), null, null, 0.18);
    }

    private StrategyHoldTimeProvider provider(AdaptiveExitProperties props,
                                              List<ExitTimingService.StrategyExitTiming> analysis) {
        ExitTimingService ets = mock(ExitTimingService.class);
        when(ets.analyze()).thenReturn(analysis);
        return new StrategyHoldTimeProvider(ets, policy(), props);
    }

    @Test
    void 표본충분_마크중_평균수익최대를_보유시간으로() {
        // 45분(avg 1.0), 120분(avg 1.5), 240분(avg 5.0 이지만 n=5<20 제외) → 120분 채택
        StrategyHoldTimeProvider p = provider(props(true, 20, 300), List.of(
                timing("MEAN_REVERSION_C",
                        mark(45, 30, 1.0), mark(120, 30, 1.5), mark(240, 5, 5.0))));

        assertThat(p.holdMinutes("MEAN_REVERSION_C")).isEqualTo(120);
    }

    @Test
    void 자격마크_없으면_고정값_fallback() {
        // 모든 마크 표본 < 20 → 채택 없음 → 고정 60분
        StrategyHoldTimeProvider p = provider(props(true, 20, 300), List.of(
                timing("VOLUME_LEADING_B", mark(45, 5, 2.0), mark(60, 10, 3.0))));

        assertThat(p.holdMinutes("VOLUME_LEADING_B")).isEqualTo(FIXED_HOLD);
    }

    @Test
    void 종가권장이면_상한으로_캡() {
        // EOD(markMinutes=-1)가 최대수익 → maxHold 200 으로 캡
        StrategyHoldTimeProvider p = provider(props(true, 20, 200), List.of(
                timing("MOMENTUM_A", mark(45, 30, 1.0),
                        new ExitTimingService.MarkStat("종가(EOD)", -1, 30, 4.0, 60.0))));

        assertThat(p.holdMinutes("MOMENTUM_A")).isEqualTo(200);
    }

    @Test
    void 비활성이면_분석없이_고정값() {
        ExitTimingService ets = mock(ExitTimingService.class);
        StrategyHoldTimeProvider p = new StrategyHoldTimeProvider(ets, policy(), props(false, 20, 300));

        assertThat(p.holdMinutes("MEAN_REVERSION_C")).isEqualTo(FIXED_HOLD);
        verify(ets, never()).analyze();   // 비활성이면 분석 호출 안 함
    }

    @Test
    void describe는_미산출_전략은_고정값으로_채움() {
        // C만 자동 산출, A·B는 fallback
        StrategyHoldTimeProvider p = provider(props(true, 20, 300), List.of(
                timing("MEAN_REVERSION_C", mark(120, 30, 1.5))));

        List<StrategyHoldTimeProvider.HoldInfo> all = p.describe();

        assertThat(all).hasSize(3);
        StrategyHoldTimeProvider.HoldInfo c = all.stream()
                .filter(h -> h.strategy().equals("MEAN_REVERSION_C")).findFirst().orElseThrow();
        assertThat(c.auto()).isTrue();
        assertThat(c.holdMinutes()).isEqualTo(120);
        StrategyHoldTimeProvider.HoldInfo a = all.stream()
                .filter(h -> h.strategy().equals("MOMENTUM_A")).findFirst().orElseThrow();
        assertThat(a.auto()).isFalse();
        assertThat(a.holdMinutes()).isEqualTo(FIXED_HOLD);
    }
}
