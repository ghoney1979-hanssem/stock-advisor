package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 대조군 분석 — "진입한 종목 vs 진입 안 한 종목(탈락 사유별)"의 net 성과 비교.
 *
 * <p>최종 목표: 알림 안 나간 종목까지 분석해 <b>더 수익 좋은 전략을 발굴</b>. 예컨대 전략 B에서 "DIRECTION_DOWN(하락 컷)"
 * 으로 탈락시킨 종목들의 평균수익이 진입분보다 높다면, 그 필터가 오히려 수익을 깎고 있다는 신호 → 게이트 완화 검토.</p>
 *
 * <p>net = (price-buyPrice)/buyPrice*100 − (왕복비용 + 진입시 슬리피지). 진입분은 control=false, 대조군은 control=true.</p>
 */
@Service
public class ControlAnalysisService {

    private static final int MIN_SAMPLES = 10;   // 진단 신뢰 최소 진입 표본
    private static final int EXIT_MARK_TOLERANCE_MIN = 30;   // perf-gate와 동일 — 권장 청산마크 ±근접 대체

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final com.stockadvisor.repository.OutcomeSampleRepository outcomeSampleRepository;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final double roundTripCostPct;
    private final java.util.Set<String> swingStrategies;   // 스윙 전략은 nextClose(D+1)로 진단(그 외 exit=권장청산마크)

    public ControlAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
                                  ExecutionCostModel executionCostModel,
                                  com.stockadvisor.repository.OutcomeSampleRepository outcomeSampleRepository,
                                  StrategyHoldTimeProvider holdTimeProvider,
                                  @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                                  @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.holdTimeProvider = holdTimeProvider;
        this.roundTripCostPct = roundTripCostPct;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    public record Stat(String group, int samples, Double avgNetReturnPct, Double winRatePct) {}
    public record StrategyControl(String strategy, String horizon, Stat entered, List<Stat> rejectedByReason,
                                  String hint) {}

    /**
     * @param strategy   전략명
     * @param horizon    진단에 쓴 horizon(스윙=nextClose, 그 외 close)
     * @param enteredNet 진입분 net 평균(%)
     * @param verdict    LOSER_MISCALIBRATED / LOSER_REGIME / UNDERSAMPLED / OK
     * @param suggestion 조치 제안(사람이 읽는 진단)
     * @param outperformingRejects 진입분보다 나은 탈락 사유들(필터 재검토 후보)
     */
    public record Diagnosis(String strategy, String horizon, Double enteredNet, int samples,
                            String verdict, String suggestion, List<String> outperformingRejects) {}

    /** @param horizon exit(권장청산마크)/close/nextClose/d2/d3/p10/p30 */
    public List<StrategyControl> analyze(String horizon) {
        boolean exitMode = "exit".equals(horizon);
        Map<String, Map<Long, Long>> exitByStrat = exitMode ? new java.util.HashMap<>() : null;   // 전략별 (outcomeId→권장마크가)
        // strategy -> "ENTERED" 또는 "REJECT:사유" -> [sumNet, wins, count]
        Map<String, Map<String, double[]>> agg = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            Long price = exitMode
                    ? exitByStrat.computeIfAbsent(o.getStrategy(), this::buildExitPrices).get(o.getId())
                    : resultPrice(o, horizon);
            if (price == null || o.getBuyPrice() <= 0) continue;
            double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                    : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
            double net = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - roundTripCostPct - slip;
            String group = o.isControl() ? "REJECT:" + (o.getRejectReason() == null ? "기타" : o.getRejectReason()) : "ENTERED";
            double[] a = agg.computeIfAbsent(o.getStrategy(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(group, k -> new double[3]);
            a[0] += net; if (net > 0) a[1] += 1; a[2] += 1;
        }

        List<StrategyControl> result = new ArrayList<>();
        agg.forEach((strategy, groups) -> {
            Stat entered = toStat("ENTERED", groups.get("ENTERED"));
            List<Stat> rejected = new ArrayList<>();
            groups.forEach((g, a) -> { if (g.startsWith("REJECT:")) rejected.add(toStat(g.substring(7), a)); });
            rejected.sort((x, y) -> Integer.compare(y.samples(), x.samples()));
            result.add(new StrategyControl(strategy, horizon == null ? "close" : horizon, entered, rejected,
                    buildHint(entered, rejected)));
        });
        return result;
    }

    /**
     * 자동 진단 — 각 전략을 <b>perf-gate와 동일한 horizon</b>(스윙=nextClose, 그 외=exit=권장청산마크)으로 판정 + 손실 원인 분류.
     * 손실전략 우선 정렬. (종가로 보면 인트라데이 전략을 오판 — 권장청산에서 나가는데 종가로 채점하던 불일치 해소, perf-gate와 잣대 통일.)
     */
    public List<Diagnosis> diagnose() {
        Map<String, StrategyControl> exit = index(analyze("exit"));       // 인트라데이 = 권장청산마크(perf-gate와 동일)
        Map<String, StrategyControl> next = index(analyze("nextClose"));  // 스윙 = 익일종가
        java.util.Set<String> strategies = new TreeSet<>();
        strategies.addAll(exit.keySet());
        strategies.addAll(next.keySet());

        List<Diagnosis> out = new ArrayList<>();
        for (String s : strategies) {
            boolean swing = swingStrategies.contains(s);
            StrategyControl sc = swing ? next.get(s) : exit.get(s);
            if (sc == null) sc = exit.get(s);
            if (sc == null) sc = next.get(s);
            if (sc != null) out.add(buildDiagnosis(sc));
        }
        // 손실전략 우선: MISCALIBRATED → REGIME → UNDERSAMPLED → OK, 같은 등급은 net 낮은 순
        out.sort((x, y) -> {
            int rx = verdictRank(x.verdict()), ry = verdictRank(y.verdict());
            if (rx != ry) return Integer.compare(rx, ry);
            double nx = x.enteredNet() == null ? 1e9 : x.enteredNet();
            double ny = y.enteredNet() == null ? 1e9 : y.enteredNet();
            return Double.compare(nx, ny);
        });
        return out;
    }

    private int verdictRank(String v) {
        return switch (v) {
            case "LOSER_MISCALIBRATED" -> 0;
            case "LOSER_REGIME" -> 1;
            case "UNDERSAMPLED" -> 2;
            default -> 3;   // OK
        };
    }

    private Diagnosis buildDiagnosis(StrategyControl sc) {
        Stat e = sc.entered();
        if (e == null || e.avgNetReturnPct() == null || e.samples() < MIN_SAMPLES) {
            int n = e == null ? 0 : e.samples();
            return new Diagnosis(sc.strategy(), sc.horizon(), e == null ? null : e.avgNetReturnPct(), n,
                    "UNDERSAMPLED", String.format("표본 부족(%d<%d) — 수집·관측 지속", n, MIN_SAMPLES), List.of());
        }
        double net = e.avgNetReturnPct();
        if (net >= 0) {
            return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(),
                    "OK", String.format("정상(net %+.2f%%, n%d) — 유지", net, e.samples()), List.of());
        }
        // 손실 — 진입분보다 나은 탈락사유(필터 재검토 후보) 수집
        List<String> better = new ArrayList<>();
        for (Stat r : sc.rejectedByReason()) {
            if (r.samples() >= MIN_SAMPLES && r.avgNetReturnPct() != null && r.avgNetReturnPct() > net) {
                better.add(String.format("%s(%+.2f%%, n%d)", r.group(), r.avgNetReturnPct(), r.samples()));
            }
        }
        if (!better.isEmpty()) {
            return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(), "LOSER_MISCALIBRATED",
                    String.format("🔴 손실(net %+.2f%%). 거른 게 진입보다 나음 → 진입조건/해당 필터 재검토", net), better);
        }
        return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(), "LOSER_REGIME",
                String.format("🔴 손실(net %+.2f%%). 필터는 유효(거른 게 더 나쁨) → 국면 부적합(국면게이팅) 또는 진입 품질바 상향", net),
                List.of());
    }

    private Map<String, StrategyControl> index(List<StrategyControl> list) {
        Map<String, StrategyControl> m = new LinkedHashMap<>();
        for (StrategyControl c : list) m.put(c.strategy(), c);
        return m;
    }

    private Stat toStat(String group, double[] a) {
        if (a == null || a[2] == 0) return new Stat(group, 0, null, null);
        int n = (int) a[2];
        return new Stat(group, n, round2(a[0] / n), round2(100.0 * a[1] / n));
    }

    /** 진입분보다 평균수익이 높은 탈락 사유가 있으면 "완화 검토" 힌트. */
    private String buildHint(Stat entered, List<Stat> rejected) {
        if (entered == null || entered.avgNetReturnPct() == null || entered.samples() == 0) {
            return "진입 표본 부족 — 비교 보류";
        }
        List<String> better = new ArrayList<>();
        for (Stat r : rejected) {
            if (r.samples() >= 10 && r.avgNetReturnPct() != null && r.avgNetReturnPct() > entered.avgNetReturnPct()) {
                better.add(String.format("%s(%.2f%%>%.2f%%, n=%d)", r.group(), r.avgNetReturnPct(), entered.avgNetReturnPct(), r.samples()));
            }
        }
        return better.isEmpty() ? "진입분이 우위 — 현 필터 유지"
                : "⚠️ 미진입이 더 나음(필터 완화 검토): " + String.join(", ", better);
    }

    private Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            case "p10" -> o.getPrice10min();
            case "p30" -> o.getPrice30min();
            default -> o.getPriceClose();
        };
    }

    /** perf-gate와 동일 — 전략 권장 청산마크(holdMinutes) ±허용범위 내 outcome별 근접 마크가. */
    private Map<Long, Long> buildExitPrices(String strategy) {
        int exitMark = holdTimeProvider.holdMinutes(strategy);
        Map<Long, Long> price = new java.util.HashMap<>();
        Map<Long, Integer> bestDist = new java.util.HashMap<>();
        for (com.stockadvisor.domain.OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                strategy, exitMark - EXIT_MARK_TOLERANCE_MIN, exitMark + EXIT_MARK_TOLERANCE_MIN)) {
            int d = Math.abs(s.getMarkMinutes() - exitMark);
            Integer cur = bestDist.get(s.getOutcomeId());
            if (cur == null || d < cur) { bestDist.put(s.getOutcomeId(), d); price.put(s.getOutcomeId(), s.getPrice()); }
        }
        return price;
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
