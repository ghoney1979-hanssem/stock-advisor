package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대조군 분석: 진입 vs 미진입 비교 + 자동 진단(exit-horizon 정렬 — perf-gate와 동일 잣대).
 */
class ControlAnalysisServiceTest {

    private ControlAnalysisService svc(List<TradeOutcome> rows) {
        return svc(rows, List.of());
    }

    private ControlAnalysisService svc(List<TradeOutcome> rows, List<OutcomeSample> exitSamples) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findAll()).thenReturn(rows);
        ExecutionCostModel cost = mock(ExecutionCostModel.class);
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);   // 슬리피지 0 → net = gross - 0.18
        OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
        lenient().when(sampleRepo.findByStrategyAndMarkMinutesBetween(anyString(), anyInt(), anyInt())).thenReturn(exitSamples);
        StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
        lenient().when(hold.holdMinutes(anyString())).thenReturn(30);   // 권장 청산마크 30분
        return new ControlAnalysisService(repo, cost, sampleRepo, hold, 0.18, "MEAN_REVERSION_C");
    }

    private static long idSeq = 1;
    private void setId(TradeOutcome o, long id) {
        try { var f = TradeOutcome.class.getDeclaredField("id"); f.setAccessible(true); f.set(o, id); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** i번째 표본의 진입일 — 5거래일에 고르게 분산(클러스터 가드 미발동). */
    private static String day(int i) {
        return "2026062" + (i % 5);
    }

    /** 종가수익 closePct% 표본 n건 (analyze("close") 테스트용). reason=null이면 진입. */
    private List<TradeOutcome> outcomes(String strategy, int n, double closePct, String reason) {
        long buy = 10_000, close = Math.round(buy * (1 + closePct / 100.0));
        List<TradeOutcome> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 진입일을 5거래일에 분산 — 단일일 클러스터 가드에 걸리지 않는 "정상" 표본
            TradeOutcome o = new TradeOutcome(strategy, null, "0059" + i, day(i), buy);
            o.setPriceClose(close);
            if (reason != null) o.markControl(reason);
            list.add(o);
        }
        return list;
    }

    @Test
    void 미진입이_진입보다_나으면_완화검토_hint() {
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(outcomes("VOLUME_LEADING_B", 15, 1.0, null));
        rows.addAll(outcomes("VOLUME_LEADING_B", 12, 2.0, "DIRECTION_DOWN"));
        rows.addAll(outcomes("VOLUME_LEADING_B", 12, 0.1, "SCORE"));
        ControlAnalysisService.StrategyControl c = svc(rows).analyze("close").stream()
                .filter(x -> x.strategy().equals("VOLUME_LEADING_B")).findFirst().orElseThrow();

        assertThat(c.entered().samples()).isEqualTo(15);
        assertThat(c.entered().avgNetReturnPct()).isEqualTo(0.82);
        assertThat(c.hint()).contains("완화 검토").contains("DIRECTION_DOWN");
        assertThat(c.hint()).doesNotContain("SCORE(");
    }

    @Test
    void 진입이_우위면_유지_hint() {
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(outcomes("MEAN_REVERSION_C", 20, 1.5, null));
        rows.addAll(outcomes("MEAN_REVERSION_C", 15, 0.2, "NO_REBOUND"));
        assertThat(svc(rows).analyze("close").get(0).hint()).contains("진입분이 우위");
    }

    @Test
    void diagnose_비스윙_exit기준_손실_거른게더나으면_MISCALIBRATED() {
        // 비스윙(E)은 이제 exit(권장청산마크 30분)로 진단. 진입 exit −2%, 거른 exit +0.5%.
        List<TradeOutcome> rows = new ArrayList<>();
        List<OutcomeSample> samples = new ArrayList<>();
        for (int i = 0; i < 15; i++) {   // 진입(손실)
            TradeOutcome o = new TradeOutcome("BREAKOUT_E", null, "e" + i, day(i), 10_000);
            setId(o, idSeq); samples.add(new OutcomeSample(idSeq, "BREAKOUT_E", 10_000, 30, 9_800)); rows.add(o); idSeq++;
        }
        for (int i = 0; i < 20; i++) {   // 거른 게 더 나음
            TradeOutcome o = new TradeOutcome("BREAKOUT_E", null, "r" + i, day(i), 10_000);
            o.markControl("NOT_BREAKOUT");
            setId(o, idSeq); samples.add(new OutcomeSample(idSeq, "BREAKOUT_E", 10_000, 30, 10_050)); rows.add(o); idSeq++;
        }
        ControlAnalysisService.Diagnosis e = svc(rows, samples).diagnose().stream()
                .filter(x -> x.strategy().equals("BREAKOUT_E")).findFirst().orElseThrow();

        assertThat(e.horizon()).isEqualTo("exit");            // 비스윙 → exit(권장청산마크), close 아님
        assertThat(e.verdict()).isEqualTo("LOSER_MISCALIBRATED");
        assertThat(e.outperformingRejects()).isNotEmpty();
    }

    @Test
    void diagnose_스윙은_nextClose로_판정() {
        // C(스윙): exit 표본 없어도 nextClose +7%로 OK 판정(horizon 혼동 방지)
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "00c" + i, day(i), 10_000);
            o.setPriceClose(9_800);
            o.setPriceNextClose(10_700);
            rows.add(o);
        }
        ControlAnalysisService.Diagnosis c = svc(rows).diagnose().stream()
                .filter(x -> x.strategy().equals("MEAN_REVERSION_C")).findFirst().orElseThrow();

        assertThat(c.horizon()).isEqualTo("nextClose");
        assertThat(c.verdict()).isEqualTo("OK");
    }

    @Test
    void 단일일이_만든_흑자는_OK가_아니라_CLUSTERED로_판정보류된다() {
        // 실사례 재현(2026-08-21): C가 verdict "OK / net +4.71%(n=195)"로 유일한 흑자 전략으로 보고됐으나
        // 195건 중 134건이 2026-06-26 하루였고, 그 하루를 빼면 net이 음수로 뒤집힌다.
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {                 // 하루에 몰린 대박(+8%)
            TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "big" + i, "20260626", 10_000);
            o.setPriceNextClose(10_800);
            rows.add(o);
        }
        for (int i = 0; i < 20; i++) {                 // 나머지 날들은 손실(−2%)
            TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "sm" + i, day(i), 10_000);
            o.setPriceNextClose(9_800);
            rows.add(o);
        }
        ControlAnalysisService.Diagnosis c = svc(rows).diagnose().stream()
                .filter(x -> x.strategy().equals("MEAN_REVERSION_C")).findFirst().orElseThrow();

        assertThat(c.enteredNet()).isPositive();                  // 전체 net은 흑자로 보이지만
        assertThat(c.verdict()).isEqualTo("CLUSTERED");           // 판정은 보류
        assertThat(c.suggestion()).contains("20260626").contains("뒤집힘");
    }

    @Test
    void 여러날에_고르게_분산된_흑자는_OK로_유지된다() {
        // 대조군: 같은 +net이라도 날짜가 분산되면(최대기여일을 빼도 부호 유지) 종전대로 OK.
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "ev" + i, day(i), 10_000);
            o.setPriceNextClose(10_300);
            rows.add(o);
        }
        ControlAnalysisService.Diagnosis c = svc(rows).diagnose().stream()
                .filter(x -> x.strategy().equals("MEAN_REVERSION_C")).findFirst().orElseThrow();

        assertThat(c.verdict()).isEqualTo("OK");
        assertThat(c.enteredNet()).isPositive();
    }
}
