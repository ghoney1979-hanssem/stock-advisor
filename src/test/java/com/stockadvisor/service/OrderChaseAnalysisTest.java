package com.stockadvisor.service;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 주문 추격(취소→재주문) 비용 환산 — {@link OrderChaseAnalysisService#toChase}.
 *
 * <p>부호 규약이 핵심: <b>음수가 불리</b>다(매도=더 싸게 팔림 / 매수=더 비싸게 삼).
 * 두 side의 추격 방향이 반대라 정규화하지 않으면 같은 축에서 읽을 수 없다.</p>
 */
@DisplayName("주문 추격 비용")
class OrderChaseAnalysisTest {

    private Order order(OrderSide side, OrderStatus status, long requested, Long fill, int minute) {
        Order o = mock(Order.class);
        when(o.getSide()).thenReturn(side);
        when(o.getStatus()).thenReturn(status);
        when(o.getRequestedPrice()).thenReturn(requested);
        when(o.getAvgFillPrice()).thenReturn(fill);
        when(o.getCreatedAt()).thenReturn(Instant.parse("2026-08-27T03:00:00Z").plusSeconds(minute * 60L));
        when(o.getStockCode()).thenReturn("112040");
        when(o.getStrategy()).thenReturn("REVERSAL_L");
        when(o.getOrderDate()).thenReturn("20260827");
        return o;
    }

    @Test
    void 매도_계단식_하향추격은_음수로_집계된다() {
        // 실측 2026-08-27 112040: 15,170 → 15,130 → 15,120 → 15,120 → 15,110 (4회 취소 후 체결)
        List<Order> chain = List.of(
                order(OrderSide.SELL, OrderStatus.CANCELLED, 15170, null, 0),
                order(OrderSide.SELL, OrderStatus.CANCELLED, 15130, null, 4),
                order(OrderSide.SELL, OrderStatus.CANCELLED, 15120, null, 8),
                order(OrderSide.SELL, OrderStatus.CANCELLED, 15120, null, 12),
                order(OrderSide.SELL, OrderStatus.FILLED, 15110, 15110L, 16));

        OrderChaseAnalysisService.Chase c = OrderChaseAnalysisService.toChase(chain);

        assertThat(c).isNotNull();
        assertThat(c.attempts()).isEqualTo(5);
        assertThat(c.firstPrice()).isEqualTo(15170);
        assertThat(c.filledPrice()).isEqualTo(15110);
        assertThat(c.adverseDriftPct()).isEqualTo(-0.40);   // (15110-15170)/15170*100
        assertThat(c.minutesLost()).isEqualTo(16);
    }

    @Test
    void 매수_상향추격도_같은_부호규약을_따른다() {
        // 매수는 더 비싸게 사는 게 불리 → 부호를 뒤집어 음수로 만든다.
        List<Order> chain = List.of(
                order(OrderSide.BUY, OrderStatus.CANCELLED, 10000, null, 0),
                order(OrderSide.BUY, OrderStatus.FILLED, 10100, 10100L, 4));

        OrderChaseAnalysisService.Chase c = OrderChaseAnalysisService.toChase(chain);

        assertThat(c.adverseDriftPct()).isEqualTo(-1.0);
    }

    @Test
    void 추격이_유리하게_끝나면_양수() {
        // 매도인데 더 비싸게 팔린 경우 — 추격이 항상 손해는 아니다.
        List<Order> chain = List.of(
                order(OrderSide.SELL, OrderStatus.CANCELLED, 10000, null, 0),
                order(OrderSide.SELL, OrderStatus.FILLED, 10050, 10050L, 4));

        assertThat(OrderChaseAnalysisService.toChase(chain).adverseDriftPct()).isEqualTo(0.5);
    }

    @Test
    void 한번에_체결되면_추격이_아니다() {
        List<Order> chain = List.of(order(OrderSide.SELL, OrderStatus.FILLED, 10000, 10000L, 0));

        assertThat(OrderChaseAnalysisService.toChase(chain)).isNull();
    }

    @Test
    void 첫행이_곧_체결이면_재주문이_없었던_것() {
        // 같은 키에 행이 둘이어도 첫 행이 체결이면 추격이 아니다(뒤는 중복 거부 등).
        List<Order> chain = List.of(
                order(OrderSide.SELL, OrderStatus.FILLED, 10000, 10000L, 0),
                order(OrderSide.SELL, OrderStatus.REJECTED, 10000, null, 4));

        assertThat(OrderChaseAnalysisService.toChase(chain)).isNull();
    }

    @Test
    void 아직_체결되지_않은_체인은_비용_미확정이라_제외() {
        List<Order> chain = List.of(
                order(OrderSide.SELL, OrderStatus.CANCELLED, 10000, null, 0),
                order(OrderSide.SELL, OrderStatus.SUBMITTED, 9900, null, 4));

        assertThat(OrderChaseAnalysisService.toChase(chain)).isNull();
    }
}
