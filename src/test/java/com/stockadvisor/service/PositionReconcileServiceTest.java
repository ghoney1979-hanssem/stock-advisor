package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PositionReconcileServiceTest {

    private TradingPolicyProperties policy(TradingMode mode) {
        return new TradingPolicyProperties(true, mode, 10.0, 0, 50_000, 10, "15:20", 60, true, List.of(), 3, 5, 0);
    }

    private KisBalanceResponse balanceWith(KisBalanceResponse.Holding... holdings) {
        return new KisBalanceResponse("0", "ok", List.of(holdings), List.of());
    }

    private KisBalanceResponse.Holding holding(String code, String qty) {
        return new KisBalanceResponse.Holding(code, code, qty, null, null, null, null, null);
    }

    private Order pos(String code, long qty) {
        Order o = mock(Order.class);
        when(o.getStockCode()).thenReturn(code);
        when(o.getRequestedQty()).thenReturn(qty);
        when(o.getStrategy()).thenReturn("MEAN_REVERSION_C");
        return o;
    }

    @Test
    void DRY_RUN은_reconcile_생략() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        PositionReconcileService svc = new PositionReconcileService(repo, kis, policy(TradingMode.DRY_RUN));

        var r = svc.reconcile();

        assertThat(r.skipped()).isTrue();
        verify(kis, never()).fetchBalance();
        verify(repo, never()).findOpenBuyPositions();
    }

    @Test
    void LIVE_내부보유인데_실계좌없으면_정리() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order p = pos("005930", 1);
        when(kis.fetchBalance()).thenReturn(balanceWith());          // 실계좌 보유 없음
        when(repo.findOpenBuyPositions()).thenReturn(List.of(p));    // 내부엔 미청산 1건
        PositionReconcileService svc = new PositionReconcileService(repo, kis, policy(TradingMode.LIVE));

        var r = svc.reconcile();

        assertThat(r.skipped()).isFalse();
        assertThat(r.closedStale()).isEqualTo(1);
        verify(p).markReconciledClosed();
        verify(repo).save(p);
    }

    @Test
    void LIVE_추적안되는_실보유는_미추적으로_경고() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchBalance()).thenReturn(balanceWith(holding("000660", "5")));  // 실보유 있음
        when(repo.findOpenBuyPositions()).thenReturn(List.of());                   // 내부 추적 없음
        PositionReconcileService svc = new PositionReconcileService(repo, kis, policy(TradingMode.LIVE));

        var r = svc.reconcile();

        assertThat(r.untracked()).isEqualTo(1);
        assertThat(r.closedStale()).isEqualTo(0);
    }
}
