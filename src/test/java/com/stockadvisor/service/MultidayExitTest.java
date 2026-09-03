package com.stockadvisor.service;

import com.stockadvisor.strategy.MultidayReversionStrategy;
import com.stockadvisor.strategy.StrategyContext;
import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.RecommendationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 P — 눌림목 멀티데이 트레일(2026-09-03, 사용자 지정).
 * 진입은 C와 동일하고, 청산만 "수익 +5% 도달 후 고점 대비 −2%, 미발동이면 15거래일 종가" 다.
 */
class MultidayExitTest {

    private static final double ARM = 5.0, DROP = 2.0;
    private static final int MAX = 15;

    private String exit(long buy, long price, Long peak, int heldDays, boolean sessionEnded) {
        return PositionExitService.multidayExitReason(buy, price, peak, heldDays, sessionEnded, ARM, DROP, MAX);
    }

    @Test
    void 무장전에는_고점되돌림이_와도_보유한다() {
        // +3%까지만 갔다가 −2% 되돌림 — 아직 +5%를 못 찍었으니 arm 안 됨.
        // ⚠️ 이게 스윙 트레일과의 결정적 차이다(스윙은 peak>매수가면 바로 arm돼 초기 눌림을 조기컷한다).
        assertThat(exit(10_000, 10_090, 10_300L, 3, false)).isNull();
    }

    @Test
    void 무장후_고점대비_2퍼센트_되돌리면_청산() {
        // 고점 +6%(10,600) → 현재 10,388 = 고점 대비 −2.0%
        String r = exit(10_000, 10_388, 10_600L, 3, false);
        assertThat(r).isNotNull();
        assertThat(r).contains("멀티데이트레일");
        assertThat(r).contains("무장 +5.0%");
    }

    @Test
    void 무장후_되돌림이_2퍼센트_미만이면_보유() {
        // 고점 10,600 → 현재 10,450 = −1.42%
        assertThat(exit(10_000, 10_450, 10_600L, 3, false)).isNull();
    }

    @Test
    void 무장판정은_현재가가_아니라_고점으로_한다() {
        // 장중 +6%를 찍고 +1%로 밀린 상태 — 현재가로 보면 arm이 안 돼 되돌림을 영영 못 잡는다.
        // peak 10,600 기준이면 이미 무장 → 10,100은 고점 대비 −4.7%라 청산.
        assertThat(exit(10_000, 10_100, 10_600L, 2, false)).isNotNull();
    }

    @Test
    void peak가_null이면_현재가를_고점으로_본다() {
        // 첫 점검(추적값 없음) — 현재가가 곧 고점. +6%면 무장되지만 되돌림 0이라 보유.
        assertThat(exit(10_000, 10_600, null, 1, false)).isNull();
    }

    @Test
    void 만기는_그날_장마감에만_발사된다() {
        // ⚠️ 근거 시뮬이 '일봉 종가' 기준이라, 장중에 팔면 검증한 적 없는 청산이 된다.
        assertThat(exit(10_000, 9_800, 10_100L, 15, false)).isNull();          // 15거래일이어도 장중이면 보유
        assertThat(exit(10_000, 9_800, 10_100L, 15, true)).contains("만기청산(D+15");
        assertThat(exit(10_000, 9_800, 10_100L, 14, true)).isNull();           // 14거래일은 아직
    }

    @Test
    void 무장은_됐지만_만기전이면_보유하고_만기엔_청산() {
        // 무장(+6%) 후 되돌림 −1%만 — 트레일 미발동이지만 만기 도달 시 종가 청산.
        assertThat(exit(10_000, 10_500, 10_600L, 15, false)).isNull();
        assertThat(exit(10_000, 10_500, 10_600L, 15, true)).contains("만기청산");
    }

    @Test
    void arm이_0이면_항상_무장이고_maxHold가_0이면_만기없음() {
        assertThat(PositionExitService.multidayExitReason(10_000, 9_700, 10_000L, 1, false, 0, DROP, MAX))
                .contains("멀티데이트레일");
        assertThat(PositionExitService.multidayExitReason(10_000, 9_800, 10_100L, 99, true, ARM, DROP, 0))
                .isNull();
    }

    @Test
    void 매수가_이상이면_판정하지_않는다() {
        assertThat(exit(0, 10_000, 10_000L, 3, true)).isNull();
    }

    // ── 전략 P의 진입 = C와 동일해야 한다 ──────────────────────────────────────────

    private StrategyContext ctx(double changeRate, boolean volumeSpike, boolean rebound, double score) {
        SignalResult sig = new SignalResult(3.0, changeRate, 10_000, 1_000_000,
                volumeSpike, false, rebound, 0, false, false, false,
                3.0, -12.0, 0.0, 0.0, false, 0.0, false, 9_900, "20260903");
        return new StrategyContext("005930", sig, score, RecommendationType.HOLD, null, false, false);
    }

    private MultidayReversionStrategy p(boolean enabled) {
        // C의 prod 기본값과 같은 임계(min-drop 3 / max-drop 12 / rebound on / score 40)
        SignalProperties props = org.mockito.Mockito.mock(SignalProperties.class);
        org.mockito.Mockito.lenient().when(props.meanReversionMinDrop()).thenReturn(3.0);
        org.mockito.Mockito.lenient().when(props.meanReversionMaxDrop()).thenReturn(12.0);
        org.mockito.Mockito.lenient().when(props.meanReversionRequireRebound()).thenReturn(true);
        org.mockito.Mockito.lenient().when(props.meanReversionMinScore()).thenReturn(40.0);
        return new MultidayReversionStrategy(props, enabled);
    }

    @Test
    void 진입조건은_C와_같고_사유문자열도_같다() {
        MultidayReversionStrategy s = p(true);
        assertThat(s.rejectReason(ctx(-5.0, true, true, 50))).isNull();          // 진입
        assertThat(s.rejectReason(ctx(-1.0, true, true, 50))).isEqualTo("DROP_RANGE");   // 낙폭 부족
        assertThat(s.rejectReason(ctx(-15.0, true, true, 50))).isEqualTo("DROP_RANGE");  // 폭락 제외
        assertThat(s.rejectReason(ctx(-5.0, false, true, 50))).isEqualTo("NO_VOLUME");
        assertThat(s.rejectReason(ctx(-5.0, true, false, 50))).isEqualTo("NO_REBOUND");
        assertThat(s.rejectReason(ctx(-5.0, true, true, 30))).isEqualTo("SCORE");
    }

    @Test
    void 비활성이면_DISABLED() {
        assertThat(p(false).rejectReason(ctx(-5.0, true, true, 50))).isEqualTo("DISABLED");
    }

    // ── 게이트 채점 horizon(2026-09-03) ────────────────────────────────────────────
    // ⚠️ 분 단위 마크로 채점하면 게이트가 '한 번도 검증한 적 없는 청산'으로 실주문을 열어준다
    //    (2026-08-18 비-TIME horizon 버그와 같은 유형). 일봉 경로에 라이브와 같은 판정 함수를 적용한다.

    private java.util.List<Long> path(long... closes) {
        java.util.List<Long> l = new java.util.ArrayList<>();
        for (long c : closes) l.add(c);
        return l;
    }

    @Test
    void 시뮬은_라이브와_같은_규칙으로_청산가를_돌려준다() {
        // D+1 10,300(무장 전) · D+2 10,600(+6% 무장) · D+3 10,380(고점 대비 −2.08% → 발동)
        assertThat(PositionExitService.simulateMultidayExitPrice(
                10_000, path(10_300, 10_600, 10_380, 11_000), ARM, DROP, MAX)).isEqualTo(10_380L);
    }

    @Test
    void 무장하지_못하면_만기_종가로_청산된다() {
        // 한 번도 +5%를 못 찍음 → 트레일 미발동 → 마지막 관측일 종가
        assertThat(PositionExitService.simulateMultidayExitPrice(
                10_000, path(10_200, 9_900, 10_100), ARM, DROP, MAX)).isEqualTo(10_100L);
    }

    @Test
    void maxHold를_넘는_경로는_잘라서_본다() {
        // maxHold=2면 D+3의 급등은 안 본다(만기 D+2 종가)
        assertThat(PositionExitService.simulateMultidayExitPrice(
                10_000, path(10_100, 10_200, 20_000), ARM, DROP, 2)).isEqualTo(10_200L);
    }

    @Test
    void 경로가_없으면_null이라_채점에서_제외된다() {
        assertThat(PositionExitService.simulateMultidayExitPrice(10_000, null, ARM, DROP, MAX)).isNull();
        assertThat(PositionExitService.simulateMultidayExitPrice(10_000, path(), ARM, DROP, MAX)).isNull();
        assertThat(PositionExitService.simulateMultidayExitPrice(0, path(10_500), ARM, DROP, MAX)).isNull();
    }

    @Test
    void 게이트가_멀티데이_전략은_일봉경로로_채점한다() {
        // 진입 6건(3거래일) — 전부 D+1 10,600(+6% 무장) → D+2 10,380(−2.08% 발동)이면 청산가 10,380 = gross +3.8%.
        // 분 단위 마크는 일부러 넣지 않는다 — 종전 exit horizon이면 표본 0으로 차단됐을 상황이다.
        java.util.List<com.stockadvisor.domain.TradeOutcome> rows = new java.util.ArrayList<>();
        java.util.List<com.stockadvisor.domain.OutcomeDailyMark> marks = new java.util.ArrayList<>();
        String[] dates = {"20260901", "20260902", "20260903"};
        for (int i = 0; i < 6; i++) {
            var o = new com.stockadvisor.domain.TradeOutcome("MULTIDAY_REVERSION_P", null,
                    String.format("%06d", i), dates[i % 3], 10_000L);
            org.springframework.test.util.ReflectionTestUtils.setField(o, "id", (long) i);
            rows.add(o);
            marks.add(new com.stockadvisor.domain.OutcomeDailyMark((long) i, "MULTIDAY_REVERSION_P", 10_000L, 1, dates[i % 3], 10_600L));
            marks.add(new com.stockadvisor.domain.OutcomeDailyMark((long) i, "MULTIDAY_REVERSION_P", 10_000L, 2, dates[i % 3], 10_380L));
        }
        var repo = org.mockito.Mockito.mock(com.stockadvisor.repository.TradeOutcomeRepository.class);
        org.mockito.Mockito.when(repo.findByStrategyAndAlertDateGreaterThanEqual(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(rows);
        var markRepo = org.mockito.Mockito.mock(com.stockadvisor.repository.OutcomeDailyMarkRepository.class);
        org.mockito.Mockito.when(markRepo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc("MULTIDAY_REVERSION_P"))
                .thenReturn(marks);
        var regime = org.mockito.Mockito.mock(MarketRegimeService.class);
        var cost = org.mockito.Mockito.mock(ExecutionCostModel.class);
        org.mockito.Mockito.when(cost.estimateRoundTripSlippagePct(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0.0);
        var props = new com.stockadvisor.config.properties.StrategyPerformanceProperties(
                true, 20, 5, 0.3, "exit", false, false, false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
        var gate = new StrategyPerformanceGate(repo, props, regime, cost,
                org.mockito.Mockito.mock(StrategyHoldTimeProvider.class),
                org.mockito.Mockito.mock(com.stockadvisor.repository.OutcomeSampleRepository.class),
                java.util.List.of(), 0.22, "", "nextClose", 0.0, false, false, "", "", "", "");
        gate.configureMultidayScoring("MULTIDAY_REVERSION_P", ARM, DROP, MAX, markRepo);

        var d = gate.evaluate("MULTIDAY_REVERSION_P");
        assertThat(d.samples()).isEqualTo(6);                       // 분 마크가 없어도 채점된다
        assertThat(d.netAvgReturnPct()).isEqualTo(3.58);            // (10,380−10,000)/10,000 −0.22 = +3.58%
        assertThat(d.reason()).contains("멀티데이트레일");            // 어느 horizon으로 쟀는지 드러난다
        assertThat(d.allowed()).isTrue();
    }
}
