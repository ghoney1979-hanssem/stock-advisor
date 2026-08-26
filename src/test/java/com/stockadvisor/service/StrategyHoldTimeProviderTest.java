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

    /** 기존 케이스는 평활 없이(smoothWindow=1) 종전 max-pick 동작을 검증한다. */
    private AdaptiveExitProperties props(boolean enabled, int minSamples, int maxHold) {
        return new AdaptiveExitProperties(enabled, minSamples, 30, maxHold, 1);
    }

    /** 비클러스터 마크(거래일 5·점유율 20%·LOO 부호 유지) — 클러스터 가드에 걸리지 않는 정상 마크. */
    private ExitTimingService.MarkStat mark(int minutes, int samples, double avgReturn) {
        return new ExitTimingService.MarkStat(minutes + "분", minutes, samples, avgReturn, 50.0,
                5, 20.0, "20260801", avgReturn, false);
    }

    /** 단일일 클러스터 마크 — 수익은 좋아 보이지만 하루가 만든 허수. */
    private ExitTimingService.MarkStat clusteredMark(int minutes, int samples, double avgReturn) {
        return new ExitTimingService.MarkStat(minutes + "분", minutes, samples, avgReturn, 50.0,
                2, 90.0, "20260825", -avgReturn, true);
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
                        new ExitTimingService.MarkStat("종가(EOD)", -1, 30, 4.0, 60.0,
                                5, 20.0, "20260801", 4.0, false))));

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
    void 평활은_단발_스파이크_대신_이웃까지_좋은_구간을_고른다() {
        // K 실측 재현: 95분만 +0.94이고 이웃(90 −0.44 / 100 −0.56)은 음수 = 표본 노이즈의 상위 극단.
        // 반면 60~70분은 세 마크가 함께 양호 → 평활(창3) 최대는 65분.
        List<ExitTimingService.MarkStat> curve = List.of(
                mark(60, 100, 0.40), mark(65, 100, 0.50), mark(70, 100, 0.55), mark(75, 100, 0.50),
                mark(90, 100, -0.44), mark(95, 100, 0.94), mark(100, 100, -0.56));

        assertThat(StrategyHoldTimeProvider.pickBest(curve, 20, 3).markMinutes()).isEqualTo(70);
        // 평활 없이는 종전대로 단발 스파이크(95분)를 고른다 — 회귀 대조
        assertThat(StrategyHoldTimeProvider.pickBest(curve, 20, 1).markMinutes()).isEqualTo(95);
    }

    @Test
    void 평활해도_표본부족_마크는_곡선에서_제외된다() {
        // 95분(+9.99%)은 표본 미달이라 후보에서도 이웃 계산에서도 빠진다 → 60~75 구간에서만 선택.
        List<ExitTimingService.MarkStat> curve = List.of(
                mark(60, 100, 0.50), mark(65, 100, 0.60), mark(70, 100, 0.55), mark(75, 100, 0.10),
                mark(95, 5, 9.99));

        assertThat(StrategyHoldTimeProvider.pickBest(curve, 20, 3).markMinutes()).isEqualTo(65);
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

    @Test
    void 단일일_클러스터_마크는_권장에서_제외된다() {
        // 300분이 net 2.28로 최고지만 그 수익이 하루가 만든 것(clustered) → 비클러스터 최선인 90분을 고른다.
        // 실측 REVERSAL_L: 5분 −0.35 → 300분 +2.28 로 단조 상승했으나 상승분 전체가 8/25 하루였다.
        List<ExitTimingService.MarkStat> curve = List.of(
                mark(30, 100, 0.12), mark(90, 100, 0.31), mark(180, 100, 0.20),
                clusteredMark(240, 100, 1.45), clusteredMark(300, 100, 2.28));

        assertThat(StrategyHoldTimeProvider.pickBest(curve, 20, 1).markMinutes()).isEqualTo(90);
    }

    @Test
    void 자격마크가_전부_클러스터면_fallback() {
        List<ExitTimingService.MarkStat> curve = List.of(
                clusteredMark(90, 100, 1.0), clusteredMark(300, 100, 2.0));

        assertThat(StrategyHoldTimeProvider.pickBest(curve, 20, 1)).isNull();
    }

    @Test
    void 캡이_걸리면_원시마크와_capped가_노출된다() {
        // 분석은 295분(n=89)을 골랐는데 maxHold 90 → holdMinutes는 90으로 잘리고,
        // samples/avgReturnPct는 원시 마크(295분) 값이다. 종전엔 이 캡 사실이 어디에도 안 보였다.
        StrategyHoldTimeProvider p = provider(props(true, 20, 90), List.of(
                timing("REVERSAL_L", mark(90, 104, 0.31), mark(295, 89, 1.46))));

        StrategyHoldTimeProvider.HoldInfo l = p.describe().stream()
                .filter(h -> h.strategy().equals("REVERSAL_L")).findFirst().orElseThrow();

        assertThat(l.holdMinutes()).isEqualTo(90);
        assertThat(l.rawMarkMinutes()).isEqualTo(295);
        assertThat(l.capped()).isTrue();
        assertThat(l.samples()).isEqualTo(89);
    }

    @Test
    void 캡에_안_걸리면_capped는_false() {
        StrategyHoldTimeProvider p = provider(props(true, 20, 300), List.of(
                timing("REVERSAL_L", mark(90, 104, 0.31))));

        StrategyHoldTimeProvider.HoldInfo l = p.describe().stream()
                .filter(h -> h.strategy().equals("REVERSAL_L")).findFirst().orElseThrow();

        assertThat(l.holdMinutes()).isEqualTo(90);
        assertThat(l.rawMarkMinutes()).isEqualTo(90);
        assertThat(l.capped()).isFalse();
    }
}
