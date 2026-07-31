package com.stockadvisor.service;

import com.stockadvisor.domain.ExitMethodType;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지수흐름 반전(FLOW_REVERSAL) 청산 — 시뮬(순수함수)과 라이브 판정.
 */
class FlowReversalExitTest {

    // ── 시뮬(ExitStrategyService 정적 함수) ──

    private TradeOutcome outcome(Instant alertTime) {
        TradeOutcome o = new TradeOutcome("SQUEEZE_BREAKOUT_H", null, "005930", "20260715", 10_000);
        o.setEntryMarketTrend("NEUTRAL");
        o.recordEntryFeatures(1.0, 2.0, 50, 10, 1.0, "KOSPI", 0, null, 1.0);   // entryMarket/entryMarketChange 세팅
        // alertTime 은 생성 시각 — 테스트는 앵커를 생성 시각 기준으로 구성
        return o;
    }

    @Test
    void 지수흐름_음전하는_첫_마크에서_청산() {
        TradeOutcome o = outcome(Instant.now());
        Instant t0 = o.getAlertTime();
        // 지수경로: 진입 −40분 +0.0% → 진입시 +1.0%(상승 중) → +40분 +0.2%(꺾임: mom30 음전)
        TreeMap<Instant, Double> series = new TreeMap<>(Map.of(
                t0.minusSeconds(2400), 0.0, t0, 1.0, t0.plusSeconds(2400), 0.2));
        Map<String, TreeMap<Instant, Double>> anchors = Map.of("KOSPI|" + o.getAlertDate(), series);
        // 가격경로: 5분 10,100 / 35분 10,050 / 60분 9,800
        var path = List.of(new ExitStrategyService.Point(5, 10_100L, null),
                new ExitStrategyService.Point(35, 10_050L, null),
                new ExitStrategyService.Point(60, 9_800L, null));

        Double r = ExitStrategyService.simulateFlowReversal(path, 10_000, o, anchors, 0.0);

        // 35분 시점: mom30 = interp(t35) − interp(t5) < 0 → 35분 마크(10,050, +0.5%)에서 청산 — 60분 하락 회피
        assertThat(r).isNotNull();
        assertThat(r).isGreaterThan(0.0).isLessThan(1.05);
    }

    @Test
    void 앵커_부족이면_표본제외_null() {
        TradeOutcome o = outcome(Instant.now());
        Map<String, TreeMap<Instant, Double>> anchors = Map.of();   // 앵커 없음
        var path = List.of(new ExitStrategyService.Point(5, 10_100L, null));

        assertThat(ExitStrategyService.simulateFlowReversal(path, 10_000, o, anchors, 0.0)).isNull();
    }

    // ── 라이브(PositionExitService) ──

    private ExitMethodProvider flowMethod(double th) {
        ExitMethodProvider p = mock(ExitMethodProvider.class);
        when(p.methodFor(any())).thenReturn(
                new ExitStrategyService.BestExit("X", ExitMethodType.FLOW_REVERSAL, th, 0, 999));
        return p;
    }

    private Order pos() {
        Order pos = mock(Order.class);
        when(pos.getCreatedAt()).thenReturn(Instant.now().minusSeconds(600));
        when(pos.getStrategy()).thenReturn("SQUEEZE_BREAKOUT_H");
        when(pos.getStockCode()).thenReturn("005930");
        when(pos.getMarket()).thenReturn("KOSPI");
        when(pos.getRequestedQty()).thenReturn(1L);
        when(pos.getRequestedPrice()).thenReturn(70_000L);
        when(pos.getId()).thenReturn(1L);
        return pos;
    }

    private MarketRiskGuard guardOff() {
        MarketRiskGuard g = mock(MarketRiskGuard.class);
        MarketRiskGuard.RiskOff ro = new MarketRiskGuard.RiskOff(false, null);
        when(g.isRiskOff()).thenReturn(ro);
        when(g.isRiskOff(any())).thenReturn(ro);
        return g;
    }

    private StrategyStopProvider stop7() {
        StrategyStopProvider p = mock(StrategyStopProvider.class);
        org.mockito.Mockito.lenient().when(p.stopPct(any())).thenReturn(7.0);
        return p;
    }

    @Test
    void 지수흐름_음전이면_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = pos();
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_500L);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.intradayFlow("KOSPI")).thenReturn(new MarketRegimeService.IntradayFlow(null, -0.6, null, true));
        PositionExitService svc = new PositionExitService(repo, orderService, kis,
                new com.stockadvisor.config.properties.TradingPolicyProperties(true, com.stockadvisor.domain.TradingMode.DRY_RUN,
                        10.0, 0, 50_000, 10, "23:59", 60, true, List.of(), 3, 5, 0),
                hold, guardOff(), flowMethod(0.0), "", stop7());
        svc.setMarketRegimeService(regime);

        assertThat(svc.closeDuePositions()).isEqualTo(1);
    }

    @Test
    void 지수흐름_순풍이면_보유() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = pos();
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_500L);
        StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.intradayFlow("KOSPI")).thenReturn(new MarketRegimeService.IntradayFlow(null, 0.8, null, true));
        PositionExitService svc = new PositionExitService(repo, orderService, kis,
                new com.stockadvisor.config.properties.TradingPolicyProperties(true, com.stockadvisor.domain.TradingMode.DRY_RUN,
                        10.0, 0, 50_000, 10, "23:59", 60, true, List.of(), 3, 5, 0),
                hold, guardOff(), flowMethod(0.0), "", stop7());
        svc.setMarketRegimeService(regime);

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }
}
