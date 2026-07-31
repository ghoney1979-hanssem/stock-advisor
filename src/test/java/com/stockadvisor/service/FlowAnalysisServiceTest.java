package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장중흐름 분석 — 전략별 흐름 부호 버킷 net·승률(스윙=nextClose 경로로 검증) + what-if.
 */
class FlowAnalysisServiceTest {

    private final TradeOutcomeRepository outcomeRepo = mock(TradeOutcomeRepository.class);
    private final OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
    private final ExecutionCostModel cost = mock(ExecutionCostModel.class);
    private final StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);

    private FlowAnalysisService svc(List<TradeOutcome> rows) {
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        when(outcomeRepo.findAll()).thenReturn(rows);
        return new FlowAnalysisService(outcomeRepo, sampleRepo, cost, hold,
                0.18, "exit", "nextClose", "MEAN_REVERSION_C");
    }

    /** C(스윙, nextClose) 진입 — nextClose%, mom60 지정. */
    private TradeOutcome c(String code, long buy, long nextClose, double mom60, String trend) {
        TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, code, "20260709", buy);
        o.setPriceNextClose(nextClose);
        o.setEntryIntradayFlow(null, null, mom60);
        o.setEntryMarketTrend(trend);
        return o;
    }

    @Test
    void 흐름부호별_net_버킷팅() {
        List<TradeOutcome> rows = List.of(
                c("001", 10_000, 10_200, +1.0, "BEAR"),   // 흐름↑ net +1.82
                c("002", 10_000, 9_900, -1.0, "BEAR"),    // 흐름↓ net -1.18
                c("003", 10_000, 10_100, -0.5, "BEAR"));  // 흐름↓ net +0.82
        FlowAnalysisService.StrategyFlowAnalysis a = svc(rows).analyze(60).get(0);

        assertThat(a.strategy()).isEqualTo("MEAN_REVERSION_C");
        assertThat(a.horizon()).isEqualTo("nextClose");   // 스윙
        assertThat(a.lagMin()).isEqualTo(60);

        FlowAnalysisService.FlowStat up = a.byFlow().stream().filter(s -> s.bucket().equals("흐름↑")).findFirst().orElseThrow();
        FlowAnalysisService.FlowStat dn = a.byFlow().stream().filter(s -> s.bucket().equals("흐름↓")).findFirst().orElseThrow();
        assertThat(up.samples()).isEqualTo(1);
        assertThat(up.avgNetPct()).isCloseTo(1.82, within(0.01));
        assertThat(dn.samples()).isEqualTo(2);
        assertThat(dn.avgNetPct()).isCloseTo(-0.18, within(0.01));   // (-1.18 + 0.82)/2
    }

    @Test
    void mom미태깅은_제외() {
        List<TradeOutcome> rows = List.of(
                c("001", 10_000, 10_200, +1.0, "BEAR"),
                new TradeOutcome("MEAN_REVERSION_C", null, "999", "20260709", 10_000));   // mom 없음 → 제외
        rows.get(1).setPriceNextClose(11_000L);   // 수익이지만 mom 없어 분석 제외돼야
        FlowAnalysisService.StrategyFlowAnalysis a = svc(rows).analyze(60).get(0);
        int total = a.byFlow().stream().mapToInt(FlowAnalysisService.FlowStat::samples).sum();
        assertThat(total).isEqualTo(1);   // mom 태깅된 1건만
    }

    @Test
    void 국면_흐름_교차버킷() {
        List<TradeOutcome> rows = List.of(
                c("001", 10_000, 10_200, +1.0, "BEAR"),
                c("002", 10_000, 9_900, -1.0, "BULL"));
        FlowAnalysisService.StrategyFlowAnalysis a = svc(rows).analyze(60).get(0);
        assertThat(a.byRegimeFlow()).extracting(FlowAnalysisService.FlowStat::bucket)
                .contains("BEAR·흐름↑", "BULL·흐름↓");
    }
}
