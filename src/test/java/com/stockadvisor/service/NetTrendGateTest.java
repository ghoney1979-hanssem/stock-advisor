package com.stockadvisor.service;

import com.stockadvisor.config.properties.StrategyPerformanceProperties;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 게이트 net 추세 조건부(2026-09-02) — 절대 net의 <b>수준</b>이 아니라 <b>방향</b>으로 여닫는다.
 *
 * <p>총 net이 음수여도 상승곡선이면 열고, 양수여도 하락곡선이면 닫는다. 평탄하면 종전 절대 net 판정.
 * 열기에만 전수 LOO(어느 하루를 빼도 기울기 양수)를 요구하는 <b>비대칭</b>이 설계의 핵심이다.</p>
 */
class NetTrendGateTest {

    private static final double COST = 0.18;

    /** 국면 무관·fallback off — 순수 net 판정 경로(레이어2 이전)로 태워 추세 로직만 본다. */
    private StrategyPerformanceProperties props(int minSamples, double minNetAvg) {
        return new StrategyPerformanceProperties(true, 20, minSamples, minNetAvg, "close", false, false,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
    }

    /** 거래일별 gross 수익률(%) 시계열 → 하루 perDay건씩의 가상매수 표본. */
    private List<TradeOutcome> daily(String strategy, double[] grossByDay, int perDay) {
        List<TradeOutcome> list = new ArrayList<>();
        long buy = 10_000;
        for (int d = 0; d < grossByDay.length; d++) {
            String date = String.format("202608%02d", d + 10);
            for (int i = 0; i < perDay; i++) {
                TradeOutcome o = new TradeOutcome(strategy, null, String.format("%06d", d * 100 + i), date, buy);
                o.setPriceClose(Math.round(buy * (1 + grossByDay[d] / 100.0)));
                list.add(o);
            }
        }
        return list;
    }

    private StrategyPerformanceGate gate(StrategyPerformanceProperties p, List<TradeOutcome> rows,
                                         boolean trendOn) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(null);
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        StrategyPerformanceGate g = new StrategyPerformanceGate(repo, p, regimeSvc, costModel,
                mock(StrategyHoldTimeProvider.class), mock(OutcomeSampleRepository.class), List.of(),
                COST, "", "nextClose", 0.0, false, false, "", "", "", "");
        g.configureNetTrend(trendOn, 5, 0.05, 0.05, 0.0);
        return g;
    }

    /** {날짜 → {건수, net합}} — netTrend 순수함수 직접 검증용. */
    private Map<String, double[]> days(double... dailyAvgNet) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < dailyAvgNet.length; i++) {
            m.put(String.format("202608%02d", i + 10), new double[]{1, dailyAvgNet[i]});
        }
        return m;
    }

    // ── 게이트 통합 ────────────────────────────────────────────────────────────────

    @Test
    void 총net이_음수여도_상승곡선이고_마지막날이_흑자면_연다() {
        // 일별 gross −1.5 → +0.5 (기울기 +0.5%p/일). 평균 gross −0.5 → net −0.68 < 기준 0.3 → 수준 판정은 차단.
        // 마지막 판정근거일 net = 0.5 − 0.18 = +0.32 (흑자) → 상승 요건 충족.
        var rows = daily("REVERSAL_L", new double[]{-1.5, -1.0, -0.5, 0.0, 0.5}, 6);

        var blocked = gate(props(30, 0.3), rows, false).evaluate("REVERSAL_L");
        assertThat(blocked.allowed()).isFalse();   // 종전 동작(수준 판정)

        var d = gate(props(30, 0.3), rows, true).evaluate("REVERSAL_L");
        assertThat(d.allowed()).isTrue();
        assertThat(d.netAvgReturnPct()).isNegative();          // net은 여전히 음수인데
        assertThat(d.reason()).contains("net 상승추세 통과");   // 방향이 열었다
        assertThat(d.reason()).contains("net추세 +0.500%p/일(5거래일");
    }

    @Test
    void 총net이_양수여도_하락곡선이면_닫는다() {
        // 일별 gross +2.0 → 0.0 (기울기 −0.5%p/일). 평균 gross +1.0 → net +0.82 ≥ 기준 0.3 → 수준 판정은 통과.
        var rows = daily("REVERSAL_L", new double[]{2.0, 1.5, 1.0, 0.5, 0.0}, 6);

        assertThat(gate(props(30, 0.3), rows, false).evaluate("REVERSAL_L").allowed()).isTrue();

        var d = gate(props(30, 0.3), rows, true).evaluate("REVERSAL_L");
        assertThat(d.allowed()).isFalse();
        assertThat(d.netAvgReturnPct()).isPositive();
        assertThat(d.reason()).contains("net 하락추세 차단");
        // ⚠️ 8/26에 고친 자기모순 문장("성과 미달(net ≥ 기준)")이 재발하지 않아야 한다.
        assertThat(d.reason()).doesNotContain("성과 미달");
    }

    @Test
    void 평탄하면_종전_절대net_판정으로_돌아간다() {
        var flat = daily("REVERSAL_L", new double[]{1.0, 1.0, 1.0, 1.0, 1.0}, 6);   // net +0.82 ≥ 0.3
        assertThat(gate(props(30, 0.3), flat, true).evaluate("REVERSAL_L").allowed()).isTrue();

        var flatLoss = daily("REVERSAL_L", new double[]{-1.0, -1.0, -1.0, -1.0, -1.0}, 6);   // net −1.18 < 0.3
        var d = gate(props(30, 0.3), flatLoss, true).evaluate("REVERSAL_L");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("성과 미달");   // 방향이 없으면 수준이 판정자
    }

    /**
     * 기울기만으로는 <b>"덜 지는 중"과 "이기는 중"이 구분되지 않는다</b> — −2.5%에서 −0.5%로 개선되는 전략도
     * 상승곡선이지만 마지막 날까지 여전히 적자다. 그런 회복은 열지 않는다.
     */
    @Test
    void 상승곡선이어도_마지막_판정근거일이_적자면_열지_않는다() {
        // 일별 gross −2.5 → −0.5 (기울기 +0.5%p/일, 전수 LOO도 양수). 마지막 net = −0.5 − 0.18 = −0.68 (적자).
        var rows = daily("REVERSAL_L", new double[]{-2.5, -2.0, -1.5, -1.0, -0.5}, 6);
        var d = gate(props(30, 0.3), rows, true).evaluate("REVERSAL_L");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).doesNotContain("net 상승추세 통과");
        assertThat(d.reason()).contains("마지막");        // 왜 안 열렸는지 태그로 드러난다
        assertThat(d.reason()).contains("평탄");          // 상승으로 인정 안 됨
    }

    @Test
    void 하루가_만든_반등으로는_열리지_않는다() {
        // 0,0,0,0,+5 — 기울기는 양수지만 마지막 하루를 빼면 0이 된다(전수 LOO 미충족) → 상승으로 인정 안 함.
        var rows = daily("REVERSAL_L", new double[]{0.0, 0.0, 0.0, 0.0, 5.0}, 6);
        var d = gate(props(30, 2.0), rows, true).evaluate("REVERSAL_L");
        assertThat(d.allowed()).isFalse();          // net(+0.82) < 기준 2.0 이고 상승 판정도 못 받는다
        assertThat(d.reason()).doesNotContain("net 상승추세 통과");
    }

    @Test
    void 거래일이_부족하면_추세판정을_생략하고_종전대로() {
        var rows = daily("REVERSAL_L", new double[]{-2.0, -1.0, 0.0, 1.0}, 8);   // 4거래일 < minDays 5
        var d = gate(props(30, 0.3), rows, true).evaluate("REVERSAL_L");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).doesNotContain("net추세");
    }

    // ── 순수함수 ──────────────────────────────────────────────────────────────────
    // ⚠️ 아래는 기울기·LOO 로직만 격리해 보려고 마지막날 요건을 −99로 사실상 끈다(위 게이트 통합에서 별도 검증).

    @Test
    void 표본_수로_가중한다_1건짜리_날이_기울기를_끌고가지_못한다() {
        // 앞 4일은 20건씩 0%, 마지막 하루만 1건 +10%.
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) m.put(String.format("202608%02d", i + 10), new double[]{20, 0});
        m.put("20260814", new double[]{1, 10});

        var tr = StrategyPerformanceGate.netTrend(m, 5, 0.05, 0.05, -99.0);
        assertThat(tr).isNotNull();
        assertThat(tr.slope()).isPositive();
        assertThat(tr.rising()).isFalse();   // 그 하루를 빼면 기울기 0 → 전수 LOO 미충족
    }

    @Test
    void LOO에서_가운데_하루를_빼도_시간축이_압축되지_않는다() {
        // y = x (기울기 1). 어느 하루를 빼도 기울기는 1이어야 한다 — 남은 날의 x를 다시 매기면 1.4가 나온다.
        var tr = StrategyPerformanceGate.netTrend(days(0, 1, 2, 3, 4), 5, 0.05, 0.05, -99.0);
        assertThat(tr).isNotNull();
        assertThat(tr.slope()).isEqualTo(1.0, within(1e-9));
        assertThat(tr.looMin()).isEqualTo(1.0, within(1e-9));
        assertThat(tr.looMax()).isEqualTo(1.0, within(1e-9));
        assertThat(tr.rising()).isTrue();
    }

    @Test
    void 데드밴드_안이면_상승도_하락도_아니다() {
        // 기울기 +0.01%p/일 < 문턱 0.05
        var tr = StrategyPerformanceGate.netTrend(days(0, 0.01, 0.02, 0.03, 0.04), 5, 0.05, 0.05, -99.0);
        assertThat(tr).isNotNull();
        assertThat(tr.rising()).isFalse();
        assertThat(tr.falling()).isFalse();
        assertThat(tr.tag()).contains("평탄");
    }

    @Test
    void 하락은_LOO없이_기울기만으로_즉시_판정된다() {
        // 0,0,0,0,−5 — 마지막 하루가 만든 하락이지만 닫는 방향엔 LOO를 요구하지 않는다(리스크 축소는 빠르게).
        var tr = StrategyPerformanceGate.netTrend(days(0, 0, 0, 0, -5), 5, 0.05, 0.05, -99.0);
        assertThat(tr).isNotNull();
        assertThat(tr.falling()).isTrue();
        assertThat(tr.looMax()).isEqualTo(0.0, within(1e-9));   // 그 하루를 빼면 평탄인데도 닫는다
    }

    @Test
    void decide는_추세가_없으면_종전_판정을_그대로_돌려준다() {
        assertThat(StrategyPerformanceGate.decide(true, null)).isTrue();
        assertThat(StrategyPerformanceGate.decide(false, null)).isFalse();
    }
}
