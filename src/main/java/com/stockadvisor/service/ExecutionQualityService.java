package com.stockadvisor.service;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.repository.OrderRepository;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 집행품질 분석 — <b>실제 LIVE 매매의 실현손익</b>(trade_order)을 <b>같은 신호의 섀도우 성과</b>(trade_outcome)와 대조.
 *
 * <p>배경: 전략 성과 집계(perf-gate·outcome-analysis 등)는 오직 섀도우(trade_outcome)만 읽는다. 실집행 특유의
 * 손실(진입 슬리피지·청산 타이밍·집행 버그)은 섀도우에 안 잡혀 "전략은 +인데 실집행은 −"인 괴리가 생길 수 있다.
 * 이 분석이 그 괴리를 정량화한다.</p>
 *
 * <p>매칭: LIVE 청산완료 매수(closed) ↔ 같은 (전략,종목,진입일) 섀도우 진입분(control 제외).
 * <ul>
 *   <li><b>realNet</b> = 실현손익%/매수체결 − 왕복비용. (실체결가엔 슬리피지가 이미 반영돼 있어 슬리피지는 재차감 안 함.)</li>
 *   <li><b>shadowNet</b> = 섀도우 (마크가−신호가)/신호가 − 왕복비용 − 추정슬리피지.
 *       <b>horizon은 perf-gate와 동일 정렬</b> — 인트라데이=`exit`(전략별 권장 청산마크 ±30분 근접, PositionExitService가 실제 청산하는 그 시점),
 *       스윙=`swingHorizon`(nextClose=D+1). → gap이 청산시점 차이가 아닌 <b>순수 집행품질</b>을 반영.</li>
 *   <li><b>entrySlip</b> = (실매수체결−섀도우신호가)/섀도우신호가 — 순수 진입 슬리피지.</li>
 *   <li><b>gap</b> = realNet − shadowNet. 음수면 실집행이 섀도우(전략 권장청산)보다 부진(집행 드래그).</li>
 * </ul></p>
 *
 * <p>⚠️ DRY_RUN은 실슬리피지가 없어(현재가 가정) 제외 — 순수 LIVE만. exit 마크 미수집 섀도우는 대조 제외(gap=null).</p>
 */
@Service
public class ExecutionQualityService {

    private static final int EXIT_MARK_TOLERANCE_MIN = 30;   // perf-gate와 동일: 권장 마크 ±범위 내 근접 마크 대체(표본 기근 보정)

    private final OrderRepository orderRepository;
    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final OutcomeSampleRepository outcomeSampleRepository;
    private final ExecutionCostModel executionCostModel;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final double roundTripCostPct;
    private final String perfGateHorizon;   // 인트라데이 검증 horizon(기본 exit) — 게이트와 정합
    private final String swingHorizon;       // 스윙 검증 horizon(기본 nextClose)
    private final Set<String> swingStrategies;

    public ExecutionQualityService(OrderRepository orderRepository,
                                   TradeOutcomeRepository tradeOutcomeRepository,
                                   OutcomeSampleRepository outcomeSampleRepository,
                                   ExecutionCostModel executionCostModel,
                                   StrategyHoldTimeProvider holdTimeProvider,
                                   @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                                   @Value("${stockadvisor.trading.perf-gate.horizon:exit}") String perfGateHorizon,
                                   @Value("${stockadvisor.trading.swing-horizon:nextClose}") String swingHorizon,
                                   @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.orderRepository = orderRepository;
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.executionCostModel = executionCostModel;
        this.holdTimeProvider = holdTimeProvider;
        this.roundTripCostPct = roundTripCostPct;
        this.perfGateHorizon = perfGateHorizon;
        this.swingHorizon = swingHorizon;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    /** 실주문 1건 vs 섀도우 대조. shadowNet/gap 은 섀도우 마크가 아직 없으면 null. */
    public record TradeCompare(String strategy, String stockCode, String date,
                               long realBuyPrice, Long shadowBuyPrice, Double entrySlipPct,
                               Double realNetPct, Double shadowNetPct, Double gapPct, String horizon) {}

    /** 전략별 집행품질 요약. */
    public record StrategyExecQuality(String strategy, int realClosed, int matched,
                                      Double avgRealNetPct, Double avgShadowNetPct, Double avgGapPct,
                                      Double avgEntrySlipPct, String hint, List<TradeCompare> trades) {}

    public List<StrategyExecQuality> analyze() {
        List<Order> closed = orderRepository.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true);
        Map<String, List<Order>> ordersByStrat = new TreeMap<>();
        for (Order o : closed) {
            if (o.getRealizedPnl() == null) continue;
            ordersByStrat.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        }

        List<StrategyExecQuality> out = new ArrayList<>();
        ordersByStrat.forEach((strategy, orders) -> {
            boolean swing = swingStrategies.contains(strategy);
            String horizon = swing ? swingHorizon : perfGateHorizon;
            boolean exitMode = "exit".equals(horizon);
            int exitMark = exitMode ? holdTimeProvider.holdMinutes(strategy) : -1;
            Map<Long, Long> exitPriceByOutcome = exitMode ? buildExitPrices(strategy, exitMark) : Map.of();
            String horizonLabel = exitMode ? exitMark + "분" : horizon;

            List<TradeCompare> trades = new ArrayList<>();
            for (Order o : orders) {
                long buyPrice = (o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0)
                        ? o.getAvgFillPrice() : o.getRequestedPrice();
                long qty = (o.getFilledQty() != null && o.getFilledQty() > 0) ? o.getFilledQty() : o.getRequestedQty();
                if (buyPrice <= 0 || qty <= 0) continue;

                // realized_pnl은 2026-07-13부터 왕복비용 차감(net) 기록 — 재차감하면 이중차감이라 그대로 사용.
                // ⚠️ 그 이전 청산분은 gross 기록이라 realNet이 비용만큼(≈0.22%p) 과대 표시(과거분 근사 수용).
                double realNetPct = (double) o.getRealizedPnl() / (buyPrice * qty) * 100;

                Long shadowBuy = null;
                Double entrySlip = null, shadowNet = null, gap = null;
                TradeOutcome shadow = findShadow(strategy, o.getStockCode(), o.getOrderDate());
                if (shadow != null && shadow.getBuyPrice() > 0) {
                    shadowBuy = shadow.getBuyPrice();
                    entrySlip = round2((double) (buyPrice - shadowBuy) / shadowBuy * 100);
                    Long mark = exitMode ? exitPriceByOutcome.get(shadow.getId()) : resultPrice(shadow, horizon);
                    if (mark != null) {
                        double slip = shadow.getEntrySlippagePct() != null ? shadow.getEntrySlippagePct()
                                : executionCostModel.estimateRoundTripSlippagePct(shadowBuy);
                        double sn = (double) (mark - shadowBuy) / shadowBuy * 100 - roundTripCostPct - slip;
                        gap = round2(realNetPct - sn);
                        shadowNet = round2(sn);
                    }
                }
                trades.add(new TradeCompare(strategy, o.getStockCode(), o.getOrderDate(),
                        buyPrice, shadowBuy, entrySlip, round2(realNetPct), shadowNet, gap, horizonLabel));
            }
            out.add(summarize(strategy, trades));
        });
        return out;
    }

    /** perf-gate와 동일 — 권장 청산마크(exitMark) ±허용범위 내 outcome별 가장 가까운 마크 가격. */
    private Map<Long, Long> buildExitPrices(String strategy, int exitMark) {
        Map<Long, Long> price = new HashMap<>();
        Map<Long, Integer> bestDist = new HashMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                strategy, exitMark - EXIT_MARK_TOLERANCE_MIN, exitMark + EXIT_MARK_TOLERANCE_MIN)) {
            int d = Math.abs(s.getMarkMinutes() - exitMark);
            Integer cur = bestDist.get(s.getOutcomeId());
            if (cur == null || d < cur) {
                bestDist.put(s.getOutcomeId(), d);
                price.put(s.getOutcomeId(), s.getPrice());
            }
        }
        return price;
    }

    private StrategyExecQuality summarize(String strategy, List<TradeCompare> trades) {
        double realSum = 0; int realN = 0;
        double shadowSum = 0, gapSum = 0; int matched = 0;
        double slipSum = 0; int slipN = 0;
        for (TradeCompare t : trades) {
            if (t.realNetPct() != null) { realSum += t.realNetPct(); realN++; }
            if (t.entrySlipPct() != null) { slipSum += t.entrySlipPct(); slipN++; }
            if (t.shadowNetPct() != null && t.gapPct() != null) { shadowSum += t.shadowNetPct(); gapSum += t.gapPct(); matched++; }
        }
        Double avgReal = realN == 0 ? null : round2(realSum / realN);
        Double avgShadow = matched == 0 ? null : round2(shadowSum / matched);
        Double avgGap = matched == 0 ? null : round2(gapSum / matched);
        Double avgSlip = slipN == 0 ? null : round2(slipSum / slipN);
        return new StrategyExecQuality(strategy, realN, matched, avgReal, avgShadow, avgGap, avgSlip,
                buildHint(avgReal, avgShadow, avgGap, matched), trades);
    }

    private String buildHint(Double avgReal, Double avgShadow, Double avgGap, int matched) {
        if (matched == 0) return "섀도우 청산마크 미수집 — 대조 보류(exit 마크 수집 후 재확인)";
        if (avgGap == null) return "-";
        if (avgGap < -0.3 && avgShadow != null && avgShadow >= 0 && avgReal != null && avgReal < 0) {
            return String.format("⚠️ 괴리 %+.2f%%p: 섀도우는 +(%.2f%%)인데 실집행 −(%.2f%%) → 집행 드래그 점검(진입슬리피지·청산체결)", avgGap, avgShadow, avgReal);
        }
        if (avgGap < -0.3) return String.format("⚠️ 실집행이 전략 권장청산 대비 %+.2f%%p 부진 → 집행 품질 점검", avgGap);
        if (avgGap > 0.3) return String.format("실집행이 전략 권장청산 대비 %+.2f%%p 양호", avgGap);
        return "실집행 ≈ 전략 권장청산(괴리 미미)";
    }

    /** (전략,종목,진입일) 섀도우 진입분(control 제외) 1건. */
    private TradeOutcome findShadow(String strategy, String stockCode, String orderDate) {
        for (TradeOutcome o : tradeOutcomeRepository.findByStrategyAndStockCodeAndAlertDate(strategy, stockCode, orderDate)) {
            if (!o.isControl()) return o;
        }
        return null;
    }

    private Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            default -> o.getPriceClose();
        };
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
