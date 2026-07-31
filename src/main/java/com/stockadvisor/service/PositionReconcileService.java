package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 잔고 reconcile — 내부 포지션 상태와 KIS 실계좌 보유를 대조한다(재시작 복구·드리프트 감지).
 *
 * <p>기동 시 1회 + 수동 트리거. 불일치 처리:
 * ① 내부는 미청산인데 실계좌 미보유 → 청산 누락/외부 매도로 보고 내부 포지션 정리(closed).
 * ② 실계좌 보유인데 내부 추적 없음 → 수동 확인 필요(자동 매매 안 함, 경고만).</p>
 *
 * <p>⚠️ DRY_RUN 포지션은 가상이라 실잔고와 본질적으로 불일치하므로 reconcile은 LIVE 모드에서만 수행한다.</p>
 */
@Service
public class PositionReconcileService {

    private static final Logger log = LoggerFactory.getLogger(PositionReconcileService.class);

    private final OrderRepository orderRepository;
    private final KisApiClient kisApiClient;
    private final TradingPolicyProperties policy;

    public PositionReconcileService(OrderRepository orderRepository, KisApiClient kisApiClient,
                                    TradingPolicyProperties policy) {
        this.orderRepository = orderRepository;
        this.kisApiClient = kisApiClient;
        this.policy = policy;
    }

    public record ReconcileResult(boolean skipped, String mode, int internalOpen,
                                  int closedStale, int untracked, List<String> notes) {}

    /** 기동 직후 1회 reconcile (실패해도 기동은 진행). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            ReconcileResult r = reconcile();
            if (!r.skipped()) {
                log.info("기동 reconcile: 내부보유 {} / 정리 {} / 미추적 {}",
                        r.internalOpen(), r.closedStale(), r.untracked());
            }
        } catch (Exception ex) {
            log.warn("기동 reconcile 실패(무시): {}", ex.getMessage());
        }
    }

    /** 내부 미청산 포지션을 실계좌 보유와 대조해 정리한다. */
    public ReconcileResult reconcile() {
        if (policy.mode() != TradingMode.LIVE) {
            log.debug("reconcile 생략 — DRY_RUN(가상 포지션)");
            return new ReconcileResult(true, policy.mode().name(), 0, 0, 0, List.of("DRY_RUN: skip"));
        }

        Map<String, Long> heldQty = new HashMap<>();
        for (KisBalanceResponse.Holding h : safe(kisApiClient.fetchBalance().holdings())) {
            long q = parseLong(h.holdingQty());
            if (q > 0) heldQty.merge(h.stockCode(), q, Long::sum);
        }

        List<Order> internalOpen = orderRepository.findOpenBuyPositions();
        List<String> notes = new ArrayList<>();
        int closedStale = 0;
        for (Order pos : internalOpen) {
            long held = heldQty.getOrDefault(pos.getStockCode(), 0L);
            if (held <= 0) {
                pos.markReconciledClosed();
                orderRepository.save(pos);
                closedStale++;
                String note = "내부 미청산인데 실계좌 미보유 → 정리: " + pos.getStrategy() + " " + pos.getStockCode();
                notes.add(note);
                log.warn("[reconcile] {}", note);
            } else if (held < pos.getRequestedQty()) {
                String note = "수량 불일치(실보유<내부): " + pos.getStockCode()
                        + " 실 " + held + " < 내부 " + pos.getRequestedQty() + " — 수동 확인";
                notes.add(note);
                log.warn("[reconcile] {}", note);
            }
        }

        // 실계좌 보유인데 내부 추적 없는 종목 — 자동 매매 금지, 경고만
        int untracked = 0;
        var trackedStocks = internalOpen.stream().map(Order::getStockCode).toList();
        for (Map.Entry<String, Long> e : heldQty.entrySet()) {
            if (!trackedStocks.contains(e.getKey())) {
                untracked++;
                String note = "추적 안 되는 실보유: " + e.getKey() + " " + e.getValue() + "주 — 수동 확인 필요";
                notes.add(note);
                log.warn("[reconcile] {}", note);
            }
        }

        return new ReconcileResult(false, policy.mode().name(), internalOpen.size(), closedStale, untracked, notes);
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
