package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisOrderResponse;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCancelServiceTest {

    // 미체결 타임아웃 3분
    private final TradingPolicyProperties policy =
            new TradingPolicyProperties(true, TradingMode.LIVE, 10.0, 0, 50_000, 10, "15:20", 60, true, List.of(), 3, 5, 0);

    private Order pending(long minutesAgo, String odno) {
        Order o = mock(Order.class);
        when(o.getCreatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        when(o.getBrokerOrderNo()).thenReturn(odno);
        when(o.getBrokerOrgNo()).thenReturn("91252");
        when(o.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(o.getStockCode()).thenReturn("005930");
        return o;
    }

    @Test
    void 타임아웃_경과_미체결은_취소() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pending(10, "O1");   // 10분 경과 > 3분
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        when(kis.cancelOrder("91252", "O1")).thenReturn(new KisOrderResponse("0", "c", "취소완료", null));
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(1);
        verify(o).markCancelled();
        verify(repo).save(o);
    }

    @Test
    void 타임아웃_이내면_유지() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pending(1, "O2");    // 1분 < 3분
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(0);
        verify(kis, never()).cancelOrder(any(), any());
        verify(o, never()).markCancelled();
    }

    @Test
    void 전일_만료_주문은_KIS호출없이_로컬종결() {
        // 지정가는 당일만 유효 — 전일 SUBMITTED는 브로커 측 이미 만료. 취소 API는 "원주문 없음" 거부라
        // 로컬 CANCELLED 처리로 멱등키를 풀어 재매도를 허용해야 한다(2026-07-10 삼천리 교착 재발 방지).
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order o = pending(3 * 24 * 60, "O3");   // 3일 전 접수
        when(o.getOrderDate()).thenReturn("20260710");   // 전일(주말 전 금요일)
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(o));
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(1);
        verify(o).markCancelled();
        verify(repo).save(o);
        verify(kis, never()).cancelOrder(any(), any());   // 만료분은 KIS 취소 호출 안 함
    }

    @Test
    void 부분체결_매도_취소시_부모포지션에_수량차감과_손익누적() {
        // 2026-07-15 모베이스전자: 239주 매도 중 66주만 체결된 채 취소 → 정산 없이는 재매도가 239주로 나가 거부 반복.
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order sell = pending(10, "O9");
        when(sell.getSide()).thenReturn(com.stockadvisor.domain.OrderSide.SELL);
        when(sell.getFilledQty()).thenReturn(66L);
        when(sell.getAvgFillPrice()).thenReturn(2_285L);
        when(sell.getIdempotencyKey()).thenReturn("SELL:210");
        when(sell.getOrderDate()).thenReturn(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        Order buy = mock(Order.class);
        when(buy.isClosed()).thenReturn(false);
        when(buy.getAvgFillPrice()).thenReturn(2_290L);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(sell));
        when(repo.findById(210L)).thenReturn(java.util.Optional.of(buy));
        when(kis.cancelOrder("91252", "O9")).thenReturn(new KisOrderResponse("0", "c", "취소완료", null));
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(1);
        // pnl = (2285−2290)×66 − round(2290×66×0.22%) = −330 − 333 = −663
        verify(buy).applyPartialSell(66L, -663L);
        verify(repo).save(buy);
    }

    @Test
    void 부분체결_매수_취소시_체결분은_FILLED_포지션으로_전환() {
        // 2026-07-16 안트로젠: 29주 매수 중 28주 체결된 채 잔여 취소 → CANCELLED로 두면 체결 28주가
        // 포지션 추적에서 사라져 청산이 영영 안 도는 고아 포지션(reconcile 경고만). 체결수량 FILLED 전환 검증.
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order buy = pending(10, "O11");
        when(buy.getSide()).thenReturn(com.stockadvisor.domain.OrderSide.BUY);
        when(buy.getFilledQty()).thenReturn(28L);
        when(buy.getAvgFillPrice()).thenReturn(18_790L);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of(buy));
        when(kis.cancelOrder("91252", "O11")).thenReturn(new KisOrderResponse("0", "c", "취소완료", null));
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(1);
        verify(buy).markFilled(28L, 18_790L);   // 체결분 보유 유지 → 청산 스케줄이 정상 매도
        verify(buy, never()).markCancelled();
        verify(repo).save(buy);
    }

    @Test
    void LIVE_미체결주문_없으면_no_op() {
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(repo.findByModeAndStatusIn(any(), any())).thenReturn(List.of());
        OrderCancelService svc = new OrderCancelService(repo, kis, policy);

        assertThat(svc.cancelStaleOrders()).isEqualTo(0);
        verify(kis, never()).cancelOrder(any(), any());
    }
}
