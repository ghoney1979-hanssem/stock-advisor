package com.stockadvisor.service;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.repository.OrderRepository;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 집행품질 분석 — 실주문 실현손익 vs 섀도우 성과(perf-gate와 동일 exit-마크 정렬) 대조, gap(집행 드래그) 정량화.
 */
class ExecutionQualityServiceTest {

    private final OrderRepository orderRepo = mock(OrderRepository.class);
    private final TradeOutcomeRepository outcomeRepo = mock(TradeOutcomeRepository.class);
    private final OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
    private final ExecutionCostModel cost = mock(ExecutionCostModel.class);
    private final StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);

    private ExecutionQualityService svc() {
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);   // 슬리피지 0 → net = raw − 0.18
        lenient().when(hold.holdMinutes(anyString())).thenReturn(45);                    // 인트라데이 권장 청산 45분
        return new ExecutionQualityService(orderRepo, outcomeRepo, sampleRepo, cost, hold,
                0.18, "exit", "nextClose", "MEAN_REVERSION_C");
    }

    private Order closedBuy(String strategy, String code, String date, long buyFill, long qty, long pnl) {
        Order o = mock(Order.class);
        when(o.getStrategy()).thenReturn(strategy);
        lenient().when(o.getStockCode()).thenReturn(code);
        lenient().when(o.getOrderDate()).thenReturn(date);
        when(o.getAvgFillPrice()).thenReturn(buyFill);
        when(o.getFilledQty()).thenReturn(qty);
        lenient().when(o.getRequestedPrice()).thenReturn(buyFill);
        lenient().when(o.getRequestedQty()).thenReturn(qty);
        when(o.getRealizedPnl()).thenReturn(pnl);
        return o;
    }

    private TradeOutcome shadow(String strategy, String code, String date, long shadowBuy) {
        return new TradeOutcome(strategy, null, code, date, shadowBuy);   // id=null (미영속) → exit 마크 outcomeId=null 매칭
    }

    /** exit 마크(mark분, price원) — outcomeId=null(미영속 섀도우와 매칭). */
    private OutcomeSample mark(int min, long price) {
        OutcomeSample s = mock(OutcomeSample.class);
        when(s.getOutcomeId()).thenReturn(null);
        when(s.getMarkMinutes()).thenReturn(min);
        when(s.getPrice()).thenReturn(price);
        return s;
    }

    @Test
    void 실집행_vs_섀도우_gap_exit마크로_대조() {
        // 실: 매수 10,000×10, 손익 +1,000 — realized_pnl은 이미 비용차감(net) 기록이라 재차감 없이 +1.0%
        // 섀도우: 신호가 10,000, exit마크(45분) 10,050 → raw +0.5% → shadowNet 0.32% (비용 0.18 차감, slip 0)
        Order buy = closedBuy("VOLUME_LEADING_B", "005930", "20260709", 10_000, 10, 1_000);
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true)).thenReturn(List.of(buy));
        when(outcomeRepo.findByStrategyAndStockCodeAndAlertDate("VOLUME_LEADING_B", "005930", "20260709"))
                .thenReturn(List.of(shadow("VOLUME_LEADING_B", "005930", "20260709", 10_000)));
        OutcomeSample m = mark(45, 10_050);   // when() 인자 밖에서 먼저 생성(중첩 스터빙 방지)
        when(sampleRepo.findByStrategyAndMarkMinutesBetween(anyString(), anyInt(), anyInt())).thenReturn(List.of(m));

        ExecutionQualityService.StrategyExecQuality q = svc().analyze().get(0);

        assertThat(q.realClosed()).isEqualTo(1);
        assertThat(q.matched()).isEqualTo(1);
        assertThat(q.avgRealNetPct()).isCloseTo(1.00, within(0.01));
        assertThat(q.avgShadowNetPct()).isCloseTo(0.32, within(0.01));
        assertThat(q.avgGapPct()).isCloseTo(0.68, within(0.01));
        assertThat(q.avgEntrySlipPct()).isCloseTo(0.0, within(0.01));
        assertThat(q.trades().get(0).horizon()).isEqualTo("45분");
    }

    @Test
    void 진입_슬리피지_반영() {
        // 실 매수 10,050 (섀도우 신호가 10,000 대비 +0.5% 불리) — 마크 없어도 entrySlip은 계산
        Order buy = closedBuy("VOLUME_LEADING_B", "005930", "20260709", 10_050, 10, 0);
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true)).thenReturn(List.of(buy));
        when(outcomeRepo.findByStrategyAndStockCodeAndAlertDate(any(), any(), any()))
                .thenReturn(List.of(shadow("VOLUME_LEADING_B", "005930", "20260709", 10_000)));
        when(sampleRepo.findByStrategyAndMarkMinutesBetween(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        ExecutionQualityService.StrategyExecQuality q = svc().analyze().get(0);
        assertThat(q.avgEntrySlipPct()).isCloseTo(0.5, within(0.01));
        assertThat(q.matched()).isZero();
    }

    @Test
    void exit마크_없으면_gap_null이고_realNet만() {
        Order buy = closedBuy("VOLUME_LEADING_B", "005930", "20260709", 10_000, 10, 500);
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true)).thenReturn(List.of(buy));
        when(outcomeRepo.findByStrategyAndStockCodeAndAlertDate(any(), any(), any()))
                .thenReturn(List.of(shadow("VOLUME_LEADING_B", "005930", "20260709", 10_000)));
        when(sampleRepo.findByStrategyAndMarkMinutesBetween(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        ExecutionQualityService.StrategyExecQuality q = svc().analyze().get(0);
        assertThat(q.realClosed()).isEqualTo(1);
        assertThat(q.matched()).isZero();
        assertThat(q.avgRealNetPct()).isNotNull();
        assertThat(q.avgShadowNetPct()).isNull();
        assertThat(q.avgGapPct()).isNull();
        assertThat(q.hint()).contains("미수집");
    }

    @Test
    void 스윙전략은_nextClose로_대조_마크불요() {
        // C(스윙): 실 매수 10,000×10 손익 +700 → realNet 0.52%. 섀도우 nextClose 로 대조(exit 마크 안 씀).
        Order buy = closedBuy("MEAN_REVERSION_C", "005930", "20260709", 10_000, 10, 700);
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true)).thenReturn(List.of(buy));
        TradeOutcome s = shadow("MEAN_REVERSION_C", "005930", "20260709", 10_000);
        s.setPriceNextClose(10_700L);   // nextClose +7%
        when(outcomeRepo.findByStrategyAndStockCodeAndAlertDate(any(), any(), any())).thenReturn(List.of(s));

        ExecutionQualityService.StrategyExecQuality q = svc().analyze().get(0);
        assertThat(q.trades().get(0).horizon()).isEqualTo("nextClose");
        assertThat(q.avgShadowNetPct()).isCloseTo(6.82, within(0.01));   // +7% − 0.18
        assertThat(q.avgGapPct()).isLessThan(0);
    }
}
