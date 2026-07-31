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

    public record MethodStat(String method, int trailPct, int samples, int triggered, Double avgNetPct, Double winRatePct) {}
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
        }
        return new MethodStat(label, trailPct == null ? 0 : trailPct, n, triggered,
                n == 0 ? null : round2(sum / n), n == 0 ? null : round2(100.0 * wins / n));
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
        if (best.method().equals(hold.method())) return "익일보유가 최선 — 트레일링 이득 없음(현행 유지)";
        return String.format("⚠️ %s 최선(%.2f%% vs 익일보유 %.2f%%) — 트레일 검토(트리거 %d/%d)",
                best.method(), best.avgNetPct(), hold.avgNetPct() == null ? 0 : hold.avgNetPct(),
                best.triggered(), best.samples());
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
