package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.KisApiClient.FillInfo;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FillSyncServiceTest {

    private final TradingPolicyProperties policy =
            new TradingPolicyProperties(true, TradingMode.LIVE, 10.0, 0, 50_000, 10, "15:20", 60, true, List.of(), 3, 5, 0);

    private Order pendingOrder(String odno, long reqQty) {
        Order o = mock(Order.class);
        when(o.getBrokerOrderNo()).thenReturn(odno);
        when(o.getRequestedQty()).thenReturn(reqQty);
        when(o.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(o.getStockCode()).thenReturn("005930");
        return o;
    }

    @Test
    void LIVE_미체결주문_없으면_KIS호출_안함() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of());
        FillSyncService svc = new FillSyncService(repo, kis, policy, mock(OrderService.class));

        assertThat(svc.syncFills()).isEqualTo(0);
        verify(kis, never()).fetchTodayFills();
    }

    @Test
    void 전량체결시_FILLED로_갱신() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pendingOrder("O1", 10);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        when(kis.fetchTodayFills()).thenReturn(List.of(new FillInfo("O1", 10, 10, 0, 9_100)));
        FillSyncService svc = new FillSyncService(repo, kis, policy, mock(OrderService.class));

        assertThat(svc.syncFills()).isEqualTo(1);
        verify(o).markFilled(10, 9_100);
        verify(repo).save(o);
    }

    @Test
    void 부분체결시_PARTIALLY_FILLED로_갱신() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pendingOrder("O2", 10);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        when(kis.fetchTodayFills()).thenReturn(List.of(new FillInfo("O2", 10, 4, 6, 9_050)));
        FillSyncService svc = new FillSyncService(repo, kis, policy, mock(OrderService.class));

        assertThat(svc.syncFills()).isEqualTo(1);
        verify(o).markPartiallyFilled(4, 9_050);
    }

    @Test
    void 매도_전량체결시_원매수포지션_청산확정() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        // 매도 주문 (원 매수 id=42)
        Order sell = mock(Order.class);
        when(sell.getBrokerOrderNo()).thenReturn("S1");
        when(sell.getRequestedQty()).thenReturn(10L);
        when(sell.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(sell.getStockCode()).thenReturn("005930");
        when(sell.getSide()).thenReturn(OrderSide.SELL);
        when(sell.getIdempotencyKey()).thenReturn("SELL:42");
        when(sell.getFilledQty()).thenReturn(10L);
        // 원 매수 포지션
        Order buy = mock(Order.class);
        when(buy.isClosed()).thenReturn(false);
        when(buy.getRequestedPrice()).thenReturn(9_000L);
        when(buy.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(buy.getStockCode()).thenReturn("005930");

        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(sell));
        when(kis.fetchTodayFills()).thenReturn(List.of(new FillInfo("S1", 10, 10, 0, 9_500)));
        when(repo.findById(42L)).thenReturn(java.util.Optional.of(buy));
        FillSyncService svc = new FillSyncService(repo, kis, policy, mock(OrderService.class));

        svc.syncFills();

        verify(sell).markFilled(10, 9_500);
        verify(buy).closePosition(4_802L);   // (9500-9000)×10 − 비용 198(90000×0.22%)
    }

    @Test
    void 미체결이면_갱신안함() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pendingOrder("O3", 10);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        when(kis.fetchTodayFills()).thenReturn(List.of(new FillInfo("O3", 10, 0, 10, 0)));
        FillSyncService svc = new FillSyncService(repo, kis, policy, mock(OrderService.class));

        assertThat(svc.syncFills()).isEqualTo(0);
        verify(o, never()).markFilled(anyLong(), anyLong());
        verify(o, never()).markPartiallyFilled(anyLong(), anyLong());
    }
}
