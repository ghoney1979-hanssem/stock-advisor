package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.KisApiClient.FillInfo;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 체결 동기화 — LIVE 주문의 접수(SUBMITTED) 상태를 KIS 실제 체결현황과 맞춘다.
 *
 * <p>접수 성공(rt_cd=0)은 "전송됨"일 뿐 체결이 아니다. 이 서비스가 일별주문체결조회로
 * 주문번호별 체결수량/평균체결가를 받아 전량체결→FILLED, 부분→PARTIALLY_FILLED 로 갱신한다.
 * 미체결(체결수량 0)은 그대로 둔다(정정/취소는 별도).</p>
 *
 * <p>DRY_RUN 주문은 KIS로 나간 적이 없어 대상이 아니므로 LIVE 주문이 없으면 KIS 호출도 하지 않는다.</p>
 */
@Service
public class FillSyncService {

    private static final Logger log = LoggerFactory.getLogger(FillSyncService.class);

    private final OrderRepository orderRepository;
    private final KisApiClient kisApiClient;
    private final TradingPolicyProperties policy;
    private final OrderService orderService;   // 운영 이벤트 알림(notifyEvent) 재사용

    // 왕복 매매비용(수수료+거래세, 매수금액 기준 %) — 실현손익을 net으로 기록(실계좌와 정합, 일일손실한도의 실질화).
    // 필드주입(생성자 무churn) — 테스트(생성자 생성)는 초기값 사용.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.cost.round-trip-pct:0.22}")
    private double roundTripCostPct = 0.22;

    public FillSyncService(OrderRepository orderRepository, KisApiClient kisApiClient,
                           TradingPolicyProperties policy, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.kisApiClient = kisApiClient;
        this.policy = policy;
        this.orderService = orderService;
    }

    /** @return 상태가 갱신된 주문 수 */
    public int syncFills() {
        List<Order> pending = orderRepository.findByModeAndStatusIn(
                TradingMode.LIVE, List.of(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED));
        if (pending.isEmpty()) {
            return 0;   // LIVE 미체결 주문 없음 → KIS 호출 생략(DRY_RUN 포함)
        }

        Map<String, FillInfo> byOrderNo = new HashMap<>();
        for (FillInfo f : kisApiClient.fetchTodayFills()) {
            byOrderNo.put(f.orderNo(), f);
        }

        int updated = 0;
        for (Order o : pending) {
            if (o.getBrokerOrderNo() == null) {
                continue;
            }
            FillInfo f = byOrderNo.get(o.getBrokerOrderNo());
            if (f == null || f.filledQty() <= 0) {
                continue;   // 아직 미체결
            }
            boolean fullyFilled = f.filledQty() >= o.getRequestedQty();
            if (fullyFilled) {
                o.markFilled(f.filledQty(), f.avgFillPrice());
                log.info("[체결] 전량 [{}] {} {}주 @ {}원 ODNO={}",
                        o.getStrategy(), o.getStockCode(), f.filledQty(), f.avgFillPrice(), o.getBrokerOrderNo());
            } else {
                o.markPartiallyFilled(f.filledQty(), f.avgFillPrice());
                log.info("[체결] 부분 [{}] {} {}/{}주 @ {}원 ODNO={}",
                        o.getStrategy(), o.getStockCode(), f.filledQty(), o.getRequestedQty(),
                        f.avgFillPrice(), o.getBrokerOrderNo());
            }
            orderRepository.save(o);
            updated++;

            // 매도 전량체결 → 원 매수 포지션 청산 확정(실체결가 기준 손익)
            if (fullyFilled && o.getSide() == OrderSide.SELL) {
                closeParentPosition(o, f.avgFillPrice());
            }
        }
        if (updated > 0) {
            log.info("체결 동기화 {}건 갱신", updated);
        }
        return updated;
    }

    /** 매도 체결 시 원 매수 포지션을 실체결가 기준으로 청산 확정. idem 키 "SELL:{buyId}" 에서 매수 id 파싱. */
    private void closeParentPosition(Order sell, long sellAvgPrice) {
        Long buyId = parseBuyId(sell.getIdempotencyKey());
        if (buyId == null) return;
        orderRepository.findById(buyId).ifPresent(buy -> {
            if (buy.isClosed()) return;
            long buyPrice = (buy.getAvgFillPrice() != null && buy.getAvgFillPrice() > 0)
                    ? buy.getAvgFillPrice() : buy.getRequestedPrice();
            // 실현손익 = 체결가 차익 − 왕복 매매비용(수수료+거래세, 매수금액 기준) → 실계좌 실현손익과 정합(net).
            long cost = Math.round(buyPrice * sell.getFilledQty() * roundTripCostPct / 100.0);
            long pnl = (sellAvgPrice - buyPrice) * sell.getFilledQty() - cost;
            buy.closePosition(pnl);
            orderRepository.save(buy);
            log.info("[청산확정] [{}] {} 매수 {}원 → 매도 {}원 ×{}주 손익 {}원",
                    buy.getStrategy(), buy.getStockCode(), buyPrice, sellAvgPrice, sell.getFilledQty(), pnl);
            double pct = buyPrice > 0 ? (sellAvgPrice - buyPrice) * 100.0 / buyPrice : 0;
            orderService.notifyEvent(String.format("💰 **청산 확정 · %s** %s\n• 전략: %s · 매수 %,d → 매도 %,d ×%,d주\n• 손익 **%+,d원 (%+.2f%%)**",
                    pnl >= 0 ? "✅ 익절" : "🛑 손절", orderService.stockDisplay(buy.getStockCode()),
                    orderService.label(buy.getStrategy()), buyPrice, sellAvgPrice, sell.getFilledQty(), pnl, pct));
        });
    }

    private Long parseBuyId(String idem) {
        if (idem == null || !idem.startsWith("SELL:")) return null;
        try {
            return Long.parseLong(idem.substring("SELL:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
