package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 스윙 트레일링 검증 — 스윙 전략(C)에서 <b>익일종가 단순보유 vs 트레일링(3/5/7% 되돌림)</b>의 net 비교.
 *
 * <p>트레일링 청산가 = {@code trailN_price}(보유 중 고점&gt;매수 대비 N% 되돌림 첫 도달가, `TradeFollowUpService`가 매분 수집);
 * 미도달이면 익일종가(`priceNextClose`)로 청산 간주. → "트레일링이 익일보유보다 나은가 + 몇 %가 최적"을 검증.</p>
 *
 * <p>⚠️ 트레일 도달가는 당일+익일 매분 추적으로 수집되므로 <b>수집 이후 진입분부터</b> 유효(과거분은 trailN=null → 익일종가로만 비교).</p>
 */
@Service
public class SwingTrailAnalysisService {

    private static final int[] TRAIL_PCTS = {3, 5, 7};

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;
    private final Set<String> swingStrategies;

    public SwingTrailAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
                                     ExecutionCostModel executionCostModel,
                                     @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                                     @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    /**
     * @param distinctDays   표본이 걸친 서로 다른 진입일 수
     * @param topDay         net 합 기여가 가장 큰 거래일
     * @param netExTopDayPct 그 하루를 뺀 나머지 net 평균(%) — 단일일 의존도 진단
     */
    public record MethodStat(String method, int trailPct, int samples, int triggered, Double avgNetPct, Double winRatePct,
                             int distinctDays, Double maxDaySharePct, String topDay, Double netExTopDayPct) {

        /** 6-인자 호환 — 날짜 분포 미상(클러스터 판정 불가). 기존 호출·테스트 무변경 유지용. */
        public MethodStat(String method, int trailPct, int samples, int triggered, Double avgNetPct, Double winRatePct) {
            this(method, trailPct, samples, triggered, avgNetPct, winRatePct, 0, null, null, null);
        }

        /** 최대기여일을 빼면 net 부호가 뒤집히는가 — true면 그 우위는 하루 이벤트 위에 서 있다. */
        public boolean clustered() {
            return avgNetPct != null && netExTopDayPct != null
                    && Math.signum(avgNetPct) != Math.signum(netExTopDayPct);
        }
    }
    public record StrategySwingTrail(String strategy, List<MethodStat> methods, String hint) {}

    public List<StrategySwingTrail> analyze() {
        Map<String, List<TradeOutcome>> byStrat = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            if (o.isControl() || o.getBuyPrice() <= 0 || o.getPriceNextClose() == null) continue;
            if (!swingStrategies.contains(o.getStrategy())) continue;
            byStrat.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        }

        List<StrategySwingTrail> out = new ArrayList<>();
        byStrat.forEach((strategy, rows) -> {
            List<MethodStat> methods = new ArrayList<>();
            methods.add(stat("익일보유(hold)", rows, null));      // 기준선: 항상 nextClose
            for (int p : TRAIL_PCTS) methods.add(stat("트레일 " + p + "%", rows, p));
            out.add(new StrategySwingTrail(strategy, methods, hint(methods)));
        });
        return out;
    }

    /** @param trailPct null이면 익일종가 보유, 아니면 그 트레일가(미도달 시 익일종가). */
    private MethodStat stat(String label, List<TradeOutcome> rows, Integer trailPct) {
        double sum = 0; int n = 0, wins = 0, triggered = 0;
        Map<String, int[]> cntByDay = new TreeMap<>();
        Map<String, double[]> sumByDay = new TreeMap<>();
        for (TradeOutcome o : rows) {
            Long exit = o.getPriceNextClose();
            if (trailPct != null) {
                Long t = trailPrice(o, trailPct);
                if (t != null) { exit = t; triggered++; }
            }
            double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                    : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
            double net = (double) (exit - o.getBuyPrice()) / o.getBuyPrice() * 100 - roundTripCostPct - slip;
            sum += net; n++; if (net > 0) wins++;
            String d = o.getAlertDate();
            if (d != null) {
                cntByDay.computeIfAbsent(d, k -> new int[1])[0]++;
                sumByDay.computeIfAbsent(d, k -> new double[1])[0] += net;
            }
        }
        if (n == 0) return new MethodStat(label, trailPct == null ? 0 : trailPct, 0, 0, null, null, 0, null, null, null);

        // 단일일 의존도(2026-08-21) — C의 익일보유 net +4.71%(n=195)가 06-26 하루 134건에서 나온 값이었다.
        // 수치는 그대로 두되 "최대기여일 제외 net"을 함께 실어, 트레일 채택 판단이 하루 이벤트에 끌려가지 않게 한다.
        String topDay = sumByDay.entrySet().stream()
                .max(java.util.Comparator.comparingDouble(e -> Math.abs(e.getValue()[0])))
                .map(Map.Entry::getKey).orElse(null);
        Double netExTop = null;
        if (topDay != null && cntByDay.size() > 1) {
            int restN = n - cntByDay.get(topDay)[0];
            if (restN > 0) netExTop = round2((sum - sumByDay.get(topDay)[0]) / restN);
        }
        double share = 100.0 * cntByDay.values().stream().mapToInt(c -> c[0]).max().orElse(0) / n;
        return new MethodStat(label, trailPct == null ? 0 : trailPct, n, triggered,
                round2(sum / n), round2(100.0 * wins / n),
                cntByDay.size(), round2(share), topDay, netExTop);
    }

    private Long trailPrice(TradeOutcome o, int pct) {
        return switch (pct) {
            case 3 -> o.getTrail3Price();
            case 5 -> o.getTrail5Price();
            default -> o.getTrail7Price();
        };
    }

    private String hint(List<MethodStat> methods) {
        MethodStat best = null;
        for (MethodStat m : methods) {
            if (m.avgNetPct() == null) continue;
            if (best == null || m.avgNetPct() > best.avgNetPct()) best = m;
        }
        if (best == null) return "표본 없음";
        MethodStat hold = methods.get(0);
        // 최대기여일을 빼면 결론이 뒤집히는지 먼저 본다 — 뒤집히면 비교 자체가 하루 이벤트 위에 서 있다.
        if (best.netExTopDayPct() != null && best.avgNetPct() != null
                && Math.signum(best.avgNetPct()) != Math.signum(best.netExTopDayPct())) {
            return String.format("⚠️ 단일일 의존 — %s net %+.2f%%(n%d, 거래일 %d)가 최대기여일(%s) 제외 시 %+.2f%%로 뒤집힘. 판정 보류",
                    best.method(), best.avgNetPct(), best.samples(), best.distinctDays(),
                    best.topDay(), best.netExTopDayPct());
        }
        if (best.method().equals(hold.method())) return "익일보유가 최선 — 트레일링 이득 없음(현행 유지)";
        return String.format("⚠️ %s 최선(%.2f%% vs 익일보유 %.2f%%) — 트레일 검토(트리거 %d/%d, 거래일 %d, 최대기여일 제외 net %s)",
                best.method(), best.avgNetPct(), hold.avgNetPct() == null ? 0 : hold.avgNetPct(),
                best.triggered(), best.samples(), best.distinctDays(),
                best.netExTopDayPct() == null ? "판정불가" : String.format("%+.2f%%", best.netExTopDayPct()));
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private Double round2(Double v) {
        return v == null ? null : round2((double) v);
    }
}
