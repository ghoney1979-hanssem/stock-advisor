package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisOrderResponse;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 미체결 지정가 주문 취소. LIVE 접수/부분체결 주문이 {@code unfilledTimeoutMinutes} 넘게 안 붙으면 취소한다.
 *
 * <p>취소되면 멱등성 키가 풀려(취소분은 중복판정 제외) 다음 평가/청산 틱에서 현재가로 재주문된다 = 가격 추격.
 * DRY_RUN 주문은 KIS로 나간 적이 없어 대상 아님(LIVE 주문 없으면 no-op).</p>
 *
 * <p><b>전일 접수분은 KIS 호출 없이 로컬 종결</b> — 지정가는 당일만 유효해 브로커 측에서 장 마감과 함께 만료되는데,
 * 취소 API는 만료 주문을 "원주문 없음"으로 거부하고 FillSync는 당일 체결만 조회해 SUBMITTED가 영구 고착됨.
 * 그 상태로는 멱등키(SELL:{buyId})가 점유돼 재매도가 차단 → 포지션 영구 미청산 교착.
 * (실측 2026-07-10 삼천리 004690: 마감 직전 추격 매도 미체결 → 주말 오버나잇 고착 → 수동 해제 후 이 로직 추가.)</p>
 */
@Service
public class OrderCancelService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderRepository orderRepository;
    private final KisApiClient kisApiClient;
    private final TradingPolicyProperties policy;

    // 부분매도 정산용 왕복비용(매수금액 기준 %) — FillSync/PositionExit 와 동일 knob.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.cost.round-trip-pct:0.22}")
    private double roundTripCostPct = 0.22;

    public OrderCancelService(OrderRepository orderRepository, KisApiClient kisApiClient,
                              TradingPolicyProperties policy) {
        this.orderRepository = orderRepository;
        this.kisApiClient = kisApiClient;
        this.policy = policy;
    }

    /** @return 취소된 주문 수 */
    public int cancelStaleOrders() {
        List<Order> pending = orderRepository.findByModeAndStatusIn(
                TradingMode.LIVE, List.of(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED));
        if (pending.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        String today = ZonedDateTime.now(SEOUL).format(YYYYMMDD);
        long timeoutMin = policy.unfilledTimeoutMinutes();
        int cancelled = 0;
        for (Order o : pending) {
            try {
                // 전일 이전 접수분 — 브로커 측 이미 만료(당일 유효). 취소 API는 거부하므로 로컬 종결만.
                if (o.getOrderDate() != null && o.getOrderDate().compareTo(today) < 0) {
                    settleCancelled(o);
                    cancelled++;
                    log.info("[취소] 전일({}) 만료 주문 로컬 종결 [{}] {} ODNO={} — 멱등키 해제(재주문 허용)",
                            o.getOrderDate(), o.getStrategy(), o.getStockCode(), o.getBrokerOrderNo());
                    continue;
                }
                long ageMin = Duration.between(o.getCreatedAt(), now).toMinutes();
                if (ageMin < timeoutMin || o.getBrokerOrderNo() == null) {
                    continue;
                }
                KisOrderResponse resp = kisApiClient.cancelOrder(o.getBrokerOrgNo(), o.getBrokerOrderNo());
                if (resp.isSuccess()) {
                    syncFillAtCancel(o);
                    settleCancelled(o);
                    cancelled++;
                    log.info("[취소] 미체결 {}분 경과 [{}] {} ODNO={}",
                            ageMin, o.getStrategy(), o.getStockCode(), o.getBrokerOrderNo());
                } else {
                    log.debug("[취소] 실패(이미 체결/취소?) ODNO={}: {}", o.getBrokerOrderNo(), resp.message());
                }
            } catch (Exception ex) {
                log.warn("[취소] 오류 ODNO={}: {}", o.getBrokerOrderNo(), ex.getMessage());
            }
        }
        if (cancelled > 0) {
            log.info("미체결 주문 취소 {}건", cancelled);
        }
        return cancelled;
    }

    /**
     * 취소 직전 체결 경합 보정 — 취소 성공 직후 그 주문의 체결수량을 KIS에서 다시 읽어 반영한다.
     * <b>2026-10-21 IPARK현대산업개발 294870 실측</b>: 매수 8주 중 1주가 직전 체결동기화(1분 주기) 뒤·취소 직전에
     * 체결됐는데, DB 체결수량(0)만 믿고 CANCELLED로 닫아 실계좌 1주가 청산 추적 밖 고아가 됐다
     * (FillSync는 SUBMITTED/PARTIALLY_FILLED만 보므로 이후 영영 반영 안 됨 — 7/16 안트로젠 수정이 못 잡는 경합 변형).
     * 취소가 성공했으면 잔량은 죽었으므로 이 시점 체결수량이 최종값이다. 매도도 같은 경합이면 판 수량이 정산에서
     * 빠져 재매도가 "주문 가능 수량 초과"로 고착되므로 양방향 적용.
     * 조회 실패 시엔 종전대로 DB 값으로 정산한다(취소 자체를 되돌릴 수 없으므로) — 경고 로그로 reconcile 확인 유도.
     */
    private void syncFillAtCancel(Order o) {
        try {
            for (KisApiClient.FillInfo f : kisApiClient.fetchTodayFills()) {
                if (!f.orderNo().equals(o.getBrokerOrderNo())) continue;
                Long filled = o.getFilledQty();
                long known = filled == null ? 0 : filled;
                if (f.filledQty() > known) {
                    o.markPartiallyFilled(f.filledQty(), f.avgFillPrice());
                    log.warn("[취소] 취소 직전 체결 반영 [{}] {} {} {}→{}주 @ {}원 ODNO={}",
                            o.getStrategy(), o.getSide(), o.getStockCode(), known, f.filledQty(),
                            f.avgFillPrice(), o.getBrokerOrderNo());
                }
                return;
            }
        } catch (Exception ex) {
            log.warn("[취소] 체결 재조회 실패 ODNO={} — DB 체결수량 기준 정산(고아 가능, reconcile 확인): {}",
                    o.getBrokerOrderNo(), ex.getMessage());
        }
    }

    /**
     * 취소 시점 상태 정산 — 매수/매도 공통 진입점.
     * <b>부분체결 매수 취소(2026-07-16 안트로젠 28/29주 사건)</b>: 체결분은 실계좌 보유인데 CANCELLED로 두면
     * 포지션 추적(findOpenBuyPositions)이 status로 제외해 청산이 영영 안 도는 고아 포지션이 된다(reconcile 경고만 남음).
     * → 매수는 체결수량 기준 FILLED로 전환해 보유·청산 추적을 유지하고, 미체결/매도만 CANCELLED 처리(매도는 부분매도 정산).
     */
    private void settleCancelled(Order o) {
        if (o.getSide() == com.stockadvisor.domain.OrderSide.BUY
                && o.getFilledQty() != null && o.getFilledQty() > 0) {
            long price = (o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0)
                    ? o.getAvgFillPrice() : o.getRequestedPrice();
            o.markFilled(o.getFilledQty(), price);
            orderRepository.save(o);
            log.info("[부분매수 정산] [{}] {} 취소 시점 체결 {}주 — FILLED 포지션 전환(청산 추적 유지)",
                    o.getStrategy(), o.getStockCode(), o.getFilledQty());
            return;
        }
        o.markCancelled();
        orderRepository.save(o);
        settlePartialSell(o);
    }

    /**
     * 부분체결된 매도 취소 정산(2026-07-15 모베이스전자 사건) — 매도가 66/239처럼 부분체결된 채 취소되면,
     * 원 매수 포지션에 <b>판 수량 차감 + 부분 실현손익(net) 누적</b>을 반영해야 잔여분 재매도 수량이 맞는다.
     * (미정산 시: 재매도가 원수량으로 나가 KIS "주문 가능 수량 초과" 거부 반복 → 포지션 고착.)
     */
    private void settlePartialSell(Order sell) {
        if (sell.getSide() != com.stockadvisor.domain.OrderSide.SELL) return;
        Long soldQty = sell.getFilledQty();
        if (soldQty == null || soldQty <= 0) return;
        String idem = sell.getIdempotencyKey();
        if (idem == null || !idem.startsWith("SELL:")) return;
        long buyId;
        try {
            buyId = Long.parseLong(idem.substring(5));
        } catch (NumberFormatException e) {
            return;
        }
        orderRepository.findById(buyId).ifPresent(buy -> {
            if (buy.isClosed()) return;
            long buyPrice = (buy.getAvgFillPrice() != null && buy.getAvgFillPrice() > 0)
                    ? buy.getAvgFillPrice() : buy.getRequestedPrice();
            long sellPrice = (sell.getAvgFillPrice() != null && sell.getAvgFillPrice() > 0)
                    ? sell.getAvgFillPrice() : sell.getRequestedPrice();
            long cost = Math.round(buyPrice * soldQty * roundTripCostPct / 100.0);
            long pnl = (sellPrice - buyPrice) * soldQty - cost;
            buy.applyPartialSell(soldQty, pnl);
            orderRepository.save(buy);
            log.info("[부분매도 정산] [{}] {} {}주 매도체결분 반영 — 잔여 {}주, 부분손익 {}원(누적 {}원)",
                    buy.getStrategy(), buy.getStockCode(), soldQty, buy.getFilledQty(), pnl, buy.getRealizedPnl());
        });
    }
}
