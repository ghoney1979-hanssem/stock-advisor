package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MAE/MFE(히트) 분석 — "승자들이 이기기 전에 얼마나 물렸나(MAE)"로 <b>catastrophic 손절선을 데이터로 검증</b>.
 *
 * <p>{@code -7% 손절}은 현재 추측값. 매 분 갱신되는 {@code peak_price}(MFE)/{@code trough_price}(MAE)로:
 * <ul>
 *   <li>전략별 <b>승자/패자의 MAE 분포</b>(평균·중앙·최악10%) + 평균 MFE.</li>
 *   <li><b>손절선 시뮬</b>: 후보 손절 S에서 "MAE가 S 이하로 내려간" 승자 수(=손절이 죽였을 승자)와 패자 수(=손절이 방어한 손실).
 *       ⚠️ peak/trough는 순서정보가 없어 근사 — trough≤S면 그 지점서 손절 발동으로 간주(보수적).</li>
 * </ul>
 * 승자/패자 분류는 exit-horizon net(perf-gate와 동일: 인트라데이=권장청산마크, 스윙=nextClose)>0 기준.</p>
 */
@Service
public class MaeAnalysisService {

    private static final int EXIT_MARK_TOLERANCE_MIN = 30;
    private static final double[] STOP_GRID = {-3, -5, -7, -10};

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final OutcomeSampleRepository outcomeSampleRepository;
    private final ExecutionCostModel executionCostModel;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final double roundTripCostPct;
    private final String perfGateHorizon;
    private final String swingHorizon;
    private final Set<String> swingStrategies;

    public MaeAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
                              OutcomeSampleRepository outcomeSampleRepository,
                              ExecutionCostModel executionCostModel,
                              StrategyHoldTimeProvider holdTimeProvider,
                              @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                              @Value("${stockadvisor.trading.perf-gate.horizon:exit}") String perfGateHorizon,
                              @Value("${stockadvisor.trading.swing-horizon:nextClose}") String swingHorizon,
                              @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.executionCostModel = executionCostModel;
        this.holdTimeProvider = holdTimeProvider;
        this.roundTripCostPct = roundTripCostPct;
        this.perfGateHorizon = perfGateHorizon;
        this.swingHorizon = swingHorizon;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    public record HeatGroup(String group, int n, Double avgMaePct, Double medianMaePct,
                            Double worst10MaePct, Double avgMfePct) {}
    public record StopSim(double stopPct, int winnersHit, int winnersTotal, int losersHit, int losersTotal) {}
    public record StrategyHeat(String strategy, String horizon, HeatGroup winners, HeatGroup losers,
                               List<StopSim> stopSim, String hint) {}

    public List<StrategyHeat> analyze() {
        Map<String, List<TradeOutcome>> byStrat = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            if (o.isControl() || o.getBuyPrice() <= 0) continue;
            if (o.getPeakPrice() == null || o.getTroughPrice() == null) continue;
            byStrat.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        }

        List<StrategyHeat> out = new ArrayList<>();
        byStrat.forEach((strategy, rows) -> {
            boolean swing = swingStrategies.contains(strategy);
            String horizon = swing ? swingHorizon : perfGateHorizon;
            boolean exitMode = "exit".equals(horizon);
            int exitMark = exitMode ? holdTimeProvider.holdMinutes(strategy) : -1;
            Map<Long, Long> exitPrice = exitMode ? buildExitPrices(strategy, exitMark) : Map.of();

            List<Double> winMae = new ArrayList<>(), winMfe = new ArrayList<>();
            List<Double> loseMae = new ArrayList<>(), loseMfe = new ArrayList<>();
            for (TradeOutcome o : rows) {
                Long price = exitMode ? exitPrice.get(o.getId()) : resultPrice(o, horizon);
                if (price == null) continue;
                double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                        : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
                double net = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - roundTripCostPct - slip;
                double mae = (double) (o.getTroughPrice() - o.getBuyPrice()) / o.getBuyPrice() * 100;   // ≤0
                double mfe = (double) (o.getPeakPrice() - o.getBuyPrice()) / o.getBuyPrice() * 100;      // ≥0
                if (net > 0) { winMae.add(mae); winMfe.add(mfe); } else { loseMae.add(mae); loseMfe.add(mfe); }
            }

            List<StopSim> sims = new ArrayList<>();
            for (double s : STOP_GRID) {
                sims.add(new StopSim(s,
                        (int) winMae.stream().filter(m -> m <= s).count(), winMae.size(),
                        (int) loseMae.stream().filter(m -> m <= s).count(), loseMae.size()));
            }
            out.add(new StrategyHeat(strategy, exitMode ? exitMark + "분" : horizon,
                    group("승자", winMae, winMfe), group("패자", loseMae, loseMfe), sims,
                    hint(winMae, sims)));
        });
        return out;
    }

    private HeatGroup group(String label, List<Double> mae, List<Double> mfe) {
        if (mae.isEmpty()) return new HeatGroup(label, 0, null, null, null, null);
        List<Double> sorted = new ArrayList<>(mae);
        Collections.sort(sorted);   // 오름차순(가장 깊은 MAE 먼저)
        return new HeatGroup(label, mae.size(), round2(avg(mae)), round2(median(sorted)),
                round2(percentile(sorted, 0.10)), round2(avg(mfe)));   // worst10 = 하위 10% 지점(더 깊음)
    }

    /** 승자 MAE 분포·손절 시뮬로 손절선 힌트. */
    private String hint(List<Double> winMae, List<StopSim> sims) {
        if (winMae.size() < 10) return "승자 표본 부족 — 관측 지속";
        List<Double> sorted = new ArrayList<>(winMae);
        Collections.sort(sorted);
        double p10 = percentile(sorted, 0.10), p50 = median(sorted);
        String s = String.format("승자 MAE 중앙 %.1f%% · 최악10%% %.1f%%.", p50, p10);
        for (StopSim sim : sims) {
            if (sim.stopPct() == -7 && sim.winnersTotal() > 0) {
                double killed = 100.0 * sim.winnersHit() / sim.winnersTotal();
                s += String.format(" 현행 −7%% 손절은 승자 %.0f%% 희생.", killed);
            }
        }
        return s + " (peak/trough 순서 미상 근사 — 표본 충분 후 손절선 조정 판단)";
    }

    private Map<Long, Long> buildExitPrices(String strategy, int exitMark) {
        Map<Long, Long> price = new HashMap<>();
        Map<Long, Integer> bestDist = new HashMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                strategy, exitMark - EXIT_MARK_TOLERANCE_MIN, exitMark + EXIT_MARK_TOLERANCE_MIN)) {
            int d = Math.abs(s.getMarkMinutes() - exitMark);
            Integer cur = bestDist.get(s.getOutcomeId());
            if (cur == null || d < cur) { bestDist.put(s.getOutcomeId(), d); price.put(s.getOutcomeId(), s.getPrice()); }
        }
        return price;
    }

    private Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            default -> o.getPriceClose();
        };
    }

    private static double avg(List<Double> l) { return l.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    private static double median(List<Double> sortedAsc) { return percentile(sortedAsc, 0.50); }
    private static double percentile(List<Double> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) return 0;
        int idx = (int) Math.round(p * (sortedAsc.size() - 1));
        return sortedAsc.get(Math.max(0, Math.min(sortedAsc.size() - 1, idx)));
    }
    private static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
