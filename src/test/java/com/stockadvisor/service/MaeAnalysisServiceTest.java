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
 * MAE 히트 분석 — 승자/패자 MAE 분포 + 손절 시뮬(스윙=nextClose 경로로 검증).
 */
class MaeAnalysisServiceTest {

    private final TradeOutcomeRepository outcomeRepo = mock(TradeOutcomeRepository.class);
    private final OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
    private final ExecutionCostModel cost = mock(ExecutionCostModel.class);
    private final StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);

    private MaeAnalysisService svc(List<TradeOutcome> rows) {
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        when(outcomeRepo.findAll()).thenReturn(rows);
        return new MaeAnalysisService(outcomeRepo, sampleRepo, cost, hold,
                0.18, "exit", "nextClose", "MEAN_REVERSION_C");
    }

    /** C(스윙): buy 10,000, nextClose·peak·trough 지정. */
    private TradeOutcome c(long nextClose, long peak, long trough) {
        TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "005930", "20260709", 10_000);
        o.setPriceNextClose(nextClose);
        o.updatePeakTrough(trough);   // 저점 먼저
        o.updatePeakTrough(peak);     // 고점 (trough 유지)
        return o;
    }

    @Test
    void 승자패자_MAE분포_손절시뮬() {
        List<TradeOutcome> rows = List.of(
                c(10_500, 10_600, 9_700),   // 승자 net+4.82, MAE −3%
                c(10_300, 10_400, 9_200),   // 승자 net+2.82, MAE −8%(깊음)
                c(9_500, 10_100, 9_000));   // 패자 net−5.18, MAE −10%
        MaeAnalysisService.StrategyHeat h = svc(rows).analyze().get(0);

        assertThat(h.strategy()).isEqualTo("MEAN_REVERSION_C");
        assertThat(h.horizon()).isEqualTo("nextClose");
        assertThat(h.winners().n()).isEqualTo(2);
        assertThat(h.winners().avgMaePct()).isCloseTo(-5.5, within(0.01));   // (−3 + −8)/2
        assertThat(h.losers().n()).isEqualTo(1);
        assertThat(h.losers().avgMaePct()).isCloseTo(-10.0, within(0.01));

        MaeAnalysisService.StopSim s7 = h.stopSim().stream().filter(s -> s.stopPct() == -7).findFirst().orElseThrow();
        assertThat(s7.winnersHit()).isEqualTo(1);      // MAE −8 ≤ −7 → 승자 1명 희생
        assertThat(s7.winnersTotal()).isEqualTo(2);
        assertThat(s7.losersHit()).isEqualTo(1);       // MAE −10 ≤ −7 → 손실 방어
        assertThat(s7.losersTotal()).isEqualTo(1);
    }

    @Test
    void peak_trough_없으면_제외() {
        TradeOutcome noExtreme = new TradeOutcome("MEAN_REVERSION_C", null, "999", "20260709", 10_000);
        noExtreme.setPriceNextClose(11_000L);   // 승자지만 peak/trough 없음 → 제외
        MaeAnalysisService.StrategyHeat h = svc(List.of(c(10_500, 10_600, 9_700), noExtreme)).analyze().get(0);
        assertThat(h.winners().n()).isEqualTo(1);   // peak/trough 있는 1건만
    }
}
