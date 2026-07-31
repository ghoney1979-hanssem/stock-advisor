package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
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
 * 스윙 트레일링 검증 — 익일보유 vs 트레일 3/5/7% net 비교.
 */
class SwingTrailAnalysisServiceTest {

    private final TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
    private final ExecutionCostModel cost = mock(ExecutionCostModel.class);

    private SwingTrailAnalysisService svc(List<TradeOutcome> rows) {
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        when(repo.findAll()).thenReturn(rows);
        return new SwingTrailAnalysisService(repo, cost, 0.18, "MEAN_REVERSION_C");
    }

    @Test
    void 익일보유_vs_트레일_비교() {
        // C: 매수 10,000, 고점 10,800, 트레일3% 도달가 10,400, 익일종가 10,600
        TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "005930", "20260709", 10_000);
        o.updatePeakTrough(10_800);          // peak>매수 → arm
        o.updateSwingTrail(10_400);          // 10400 ≤ 10800×0.97(10476) → trail3=10400 (5/7%는 미도달)
        o.setPriceNextClose(10_600L);
        SwingTrailAnalysisService.StrategySwingTrail a = svc(List.of(o)).analyze().get(0);

        var hold = a.methods().stream().filter(m -> m.method().startsWith("익일보유")).findFirst().orElseThrow();
        var t3 = a.methods().stream().filter(m -> m.method().equals("트레일 3%")).findFirst().orElseThrow();
        var t5 = a.methods().stream().filter(m -> m.method().equals("트레일 5%")).findFirst().orElseThrow();

        assertThat(hold.avgNetPct()).isCloseTo(5.82, within(0.01));   // (10600-10000)/100 − 0.18
        assertThat(t3.avgNetPct()).isCloseTo(3.82, within(0.01));     // 트레일 10400
        assertThat(t3.triggered()).isEqualTo(1);
        assertThat(t5.avgNetPct()).isCloseTo(5.82, within(0.01));     // 5% 미도달 → 익일종가
        assertThat(t5.triggered()).isEqualTo(0);
        assertThat(a.hint()).contains("익일보유가 최선");   // 이 표본은 보유가 나음
    }
}
