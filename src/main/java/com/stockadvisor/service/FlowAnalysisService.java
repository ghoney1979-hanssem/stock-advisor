package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 장중흐름 분석 — 전략별로 <b>진입 시점 지수 장중흐름(entry_index_mom30/60)</b>이 성과에 어떻게 영향을 주는지.
 *
 * <p>모든 전략 공통 지표: 각 전략이 "지수가 오르는 중/빠지는 중"에 진입했을 때 net·승률이 어떻게 갈리는지 본다
 * (B는 딥바잉이라 흐름↓에 강할 수 있고, E는 추세추종이라 흐름↑에 강할 수 있음 — 전략마다 반대일 수 있어 전 전략 비교).</p>
 *
 * <ul>
 *   <li><b>horizon = perf-gate·집행품질과 동일</b>: 인트라데이=exit(권장 청산마크 ±30분 근접), 스윙=nextClose.
 *       → close 아님(전략이 실제 청산하는 시점 기준).</li>
 *   <li><b>국면 분리</b>(entry_market_trend): 약세장 표본이 많아 "흐름↓=수익"이 국면 교란인지 가려내기 위함.</li>
 *   <li><b>what-if</b>: "흐름↓ 진입을 스킵하면 net이 오르나?" 반사실 비교(순진한 흐름게이트 위험 점검).</li>
 * </ul>
 *
 * <p>lag 파라미터로 30/60분 흐름 선택. mom 미태깅 표본은 제외. net = (청산가−매수가)/매수가 − 왕복비용 − 슬리피지.</p>
 */
@Service
public class FlowAnalysisService {

    private static final int EXIT_MARK_TOLERANCE_MIN = 30;

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final OutcomeSampleRepository outcomeSampleRepository;
    private final ExecutionCostModel executionCostModel;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final double roundTripCostPct;
    private final String perfGateHorizon;
    private final String swingHorizon;
    private final Set<String> swingStrategies;

    public FlowAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
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

    public record FlowStat(String bucket, int samples, Double avgNetPct, Double winRatePct) {}
    public record StrategyFlowAnalysis(String strategy, String horizon, int lagMin,
                                       List<FlowStat> byFlow, List<FlowStat> byRegimeFlow, String whatIf) {}

    /** @param lagMin 흐름 lag(30 또는 60). */
    public List<StrategyFlowAnalysis> analyze(int lagMin) {
        // 전략별 진입분(대조군 제외, mom 태깅된 것만) 수집
        Map<String, List<TradeOutcome>> byStrat = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            if (o.isControl() || o.getBuyPrice() <= 0) continue;
            if (flowValue(o, lagMin) == null) continue;
            byStrat.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        }

        List<StrategyFlowAnalysis> out = new ArrayList<>();
        byStrat.forEach((strategy, rows) -> {
            boolean swing = swingStrategies.contains(strategy);
            String horizon = swing ? swingHorizon : perfGateHorizon;
            boolean exitMode = "exit".equals(horizon);
            int exitMark = exitMode ? holdTimeProvider.holdMinutes(strategy) : -1;
            Map<Long, Long> exitPrice = exitMode ? buildExitPrices(strategy, exitMark) : Map.of();
            String horizonLabel = exitMode ? exitMark + "분" : horizon;

            // 버킷 누산: label -> [sumNet, wins, count]
            Map<String, double[]> flow = new LinkedHashMap<>();
            Map<String, double[]> regimeFlow = new TreeMap<>();
            double allSum = 0, upSum = 0, dnSum = 0; int allN = 0, upN = 0, dnN = 0;
            for (TradeOutcome o : rows) {
                Long price = exitMode ? exitPrice.get(o.getId()) : resultPrice(o, horizon);
                if (price == null) continue;   // 청산마크 미수집 → 제외
                double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                        : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
                double net = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - roundTripCostPct - slip;
                boolean up = flowValue(o, lagMin) >= 0;
                String fLabel = up ? "흐름↑" : "흐름↓";
                acc(flow, fLabel, net);
                String regime = o.getEntryMarketTrend() == null ? "국면미상" : o.getEntryMarketTrend();
                acc(regimeFlow, regime + "·" + fLabel, net);
                allSum += net; allN++;
                if (up) { upSum += net; upN++; } else { dnSum += net; dnN++; }
            }
            out.add(new StrategyFlowAnalysis(strategy, horizonLabel, lagMin,
                    toStats(flow), toStats(regimeFlow),
                    whatIf(allSum, allN, upSum, upN, dnSum, dnN)));
        });
        return out;
    }

    /** "흐름↓ 스킵(흐름↑만)" vs "흐름↑ 스킵(흐름↓만)" vs 전체 net 비교 hint. */
    private String whatIf(double allSum, int allN, double upSum, int upN, double dnSum, int dnN) {
        if (allN == 0) return "표본 없음";
        double all = allSum / allN;
        String s = String.format("전체 net %+.2f%%(n%d)", all, allN);
        if (upN > 0) s += String.format(" · 흐름↑만 %+.2f%%(n%d)", upSum / upN, upN);
        if (dnN > 0) s += String.format(" · 흐름↓만 %+.2f%%(n%d)", dnSum / dnN, dnN);
        // 어느 한쪽 스킵이 전체보다 유의하게 나으면 힌트
        Double best = null; String bestSeg = null;
        if (upN >= 10 && upSum / upN > all + 0.2) { best = upSum / upN; bestSeg = "흐름↓ 스킵(흐름↑만 진입)"; }
        if (dnN >= 10 && dnSum / dnN > all + 0.2 && (best == null || dnSum / dnN > best)) { best = dnSum / dnN; bestSeg = "흐름↑ 스킵(흐름↓만 진입)"; }
        s += bestSeg == null ? " → 흐름 게이트 이득 없음(현행 유지)" : String.format(" → ⚠️ %s 시 %+.2f%%로 개선 후보", bestSeg, best);
        return s;
    }

    /** 진입시 지수 흐름값(lag별). */
    private Double flowValue(TradeOutcome o, int lagMin) {
        return lagMin == 30 ? o.getEntryIndexMom30() : o.getEntryIndexMom60();
    }

    /** perf-gate와 동일 — 권장 청산마크 ±허용범위 내 outcome별 근접 마크 가격. */
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

    private void acc(Map<String, double[]> m, String key, double net) {
        double[] a = m.computeIfAbsent(key, k -> new double[3]);
        a[0] += net; if (net > 0) a[1] += 1; a[2] += 1;
    }

    private List<FlowStat> toStats(Map<String, double[]> m) {
        List<FlowStat> out = new ArrayList<>();
        m.forEach((k, a) -> {
            int n = (int) a[2];
            out.add(new FlowStat(k, n, round2(a[0] / n), round2(100.0 * a[1] / n)));
        });
        return out;
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
