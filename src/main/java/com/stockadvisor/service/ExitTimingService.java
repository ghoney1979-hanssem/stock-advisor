package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.repository.OutcomeSampleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 당일 데이트레이딩 청산시점 분석.
 * 전략별로 "진입 후 보유시간(마크)마다 평균수익률·승률"을 내고,
 * 평균수익률이 최대인 보유시간(=평균적으로 가장 익절하기 좋은 시점)을 추천한다.
 *
 * <p>수익률·승률은 왕복 매매비용(stockadvisor.cost.round-trip-pct, 기본 0.18%) 차감 순수익 기준.</p>
 */
@Service
public class ExitTimingService {

    private static final int EOD_MARK = -1;

    private final OutcomeSampleRepository outcomeSampleRepository;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;   // 왕복 매매비용(수수료+거래세) — 슬리피지는 ExecutionCostModel 별도 가산

    public ExitTimingService(OutcomeSampleRepository outcomeSampleRepository,
                             ExecutionCostModel executionCostModel,
                             @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
    }

    public record MarkStat(String mark, int markMinutes, int samples, double avgReturnPct, double winRatePct) {}
    public record StrategyExitTiming(String strategy, int totalEntries, List<MarkStat> curve,
                                     String recommendedExit, Double recommendedAvgReturnPct,
                                     double roundTripCostPct) {}

    public List<StrategyExitTiming> analyze() {
        // strategy -> (markMinutes -> [sumReturn, wins, count])
        Map<String, Map<Integer, double[]>> agg = new TreeMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findAll()) {
            Map<Integer, double[]> byMark = agg.computeIfAbsent(s.getStrategy(), k -> new TreeMap<>());
            double[] acc = byMark.computeIfAbsent(s.getMarkMinutes(), k -> new double[3]);
            double ret = s.returnPct() - roundTripCostPct
                    - executionCostModel.estimateRoundTripSlippagePct(s.getBuyPrice());   // 순수익(수수료+슬리피지)
            acc[0] += ret;
            if (ret > 0) acc[1] += 1;
            acc[2] += 1;
        }

        List<StrategyExitTiming> result = new ArrayList<>();
        agg.forEach((strategy, byMark) -> {
            List<MarkStat> curve = new ArrayList<>();
            byMark.forEach((mark, acc) -> {
                int n = (int) acc[2];
                curve.add(new MarkStat(label(mark), mark, n,
                        round2(acc[0] / n), round2(100.0 * acc[1] / n)));
            });
            // 보유시간 오름차순, 종가(EOD)는 맨 뒤
            curve.sort(Comparator.comparingInt(m -> m.markMinutes() == EOD_MARK ? Integer.MAX_VALUE : m.markMinutes()));

            // 평균수익률 최대 마크 = 권장 청산시점
            MarkStat best = curve.stream().max(Comparator.comparingDouble(MarkStat::avgReturnPct)).orElse(null);
            result.add(new StrategyExitTiming(strategy,
                    curve.stream().mapToInt(MarkStat::samples).max().orElse(0),
                    curve,
                    best == null ? null : best.mark(),
                    best == null ? null : best.avgReturnPct(),
                    roundTripCostPct));
        });
        return result;
    }

    private String label(int mark) {
        return mark == EOD_MARK ? "종가(EOD)" : mark + "분";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
