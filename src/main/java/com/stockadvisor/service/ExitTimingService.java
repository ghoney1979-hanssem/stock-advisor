package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.repository.OutcomeSampleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final double MAX_DAY_SHARE_PCT = 80.0;   // ControlAnalysisService/MultidayExitAnalysisService와 동일 규칙
    private static final int MIN_DISTINCT_DAYS = 3;

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

    /**
     * @param distinctDays   이 마크 표본이 걸친 서로 다른 <b>진입일</b> 수(0=진입일 미상 → 판정 생략)
     * @param maxDaySharePct 단일 진입일이 차지하는 최대 건수 비중(%)
     * @param topDay         net 합 기여 절대값이 가장 큰 진입일(yyyyMMdd)
     * @param netExTopDayPct 그 하루를 뺀 나머지 net 평균(%) — 남는 표본이 없으면 null
     * @param clustered      건수 편중 <b>또는</b> LOO 부호 반전 → 이 마크 수치는 하루가 만든 허수
     */
    public record MarkStat(String mark, int markMinutes, int samples, double avgReturnPct, double winRatePct,
                           int distinctDays, Double maxDaySharePct, String topDay,
                           Double netExTopDayPct, boolean clustered) {}
    public record StrategyExitTiming(String strategy, int totalEntries, List<MarkStat> curve,
                                     String recommendedExit, Double recommendedAvgReturnPct,
                                     double roundTripCostPct) {}

    public List<StrategyExitTiming> analyze() {
        // 진입일 맵(outcomeId -> alertDate) — 마크별 단일일 클러스터 판정용. 조인 1회, outcome당 1행.
        Map<Long, String> entryDates = new HashMap<>();
        for (Object[] row : outcomeSampleRepository.findEntryDates()) {
            if (row.length >= 2 && row[0] != null && row[1] != null) {
                entryDates.put(((Number) row[0]).longValue(), (String) row[1]);
            }
        }
        // strategy -> (markMinutes -> [sumReturn, wins, count])
        Map<String, Map<Integer, double[]>> agg = new TreeMap<>();
        // strategy -> (markMinutes -> (진입일 -> [count, sumReturn])) — LOO/점유율 판정용
        Map<String, Map<Integer, Map<String, double[]>>> byDay = new TreeMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findAll()) {
            Map<Integer, double[]> byMark = agg.computeIfAbsent(s.getStrategy(), k -> new TreeMap<>());
            double[] acc = byMark.computeIfAbsent(s.getMarkMinutes(), k -> new double[3]);
            double ret = s.returnPct() - roundTripCostPct
                    - executionCostModel.estimateRoundTripSlippagePct(s.getBuyPrice());   // 순수익(수수료+슬리피지)
            acc[0] += ret;
            if (ret > 0) acc[1] += 1;
            acc[2] += 1;
            // 진입일 미상 표본은 일자 집계에서만 빠진다(net 평균엔 그대로 포함) — 진단 부재는 distinctDays=0으로 드러난다.
            String day = entryDates.get(s.getOutcomeId());
            if (day != null) {
                double[] d = byDay.computeIfAbsent(s.getStrategy(), k -> new TreeMap<>())
                        .computeIfAbsent(s.getMarkMinutes(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(day, k -> new double[2]);
                d[0] += 1;
                d[1] += ret;
            }
        }

        List<StrategyExitTiming> result = new ArrayList<>();
        agg.forEach((strategy, byMark) -> {
            List<MarkStat> curve = new ArrayList<>();
            Map<Integer, Map<String, double[]>> dayOfStrategy = byDay.getOrDefault(strategy, Map.of());
            byMark.forEach((mark, acc) -> {
                int n = (int) acc[2];
                curve.add(cluster(label(mark), mark, n, round2(acc[0] / n), round2(100.0 * acc[1] / n),
                        dayOfStrategy.getOrDefault(mark, Map.of())));
            });
            // 보유시간 오름차순, 종가(EOD)는 맨 뒤
            curve.sort(Comparator.comparingInt(m -> m.markMinutes() == EOD_MARK ? Integer.MAX_VALUE : m.markMinutes()));

            // 평균수익률 최대 마크 = 권장 청산시점. 단일일 클러스터 마크는 제외 — 하루가 만든 허수를 권장
            // 청산시점으로 채택하면 그 값이 곧 게이트 채점 horizon이 돼 게이트 net까지 함께 오염된다.
            MarkStat best = curve.stream().filter(m -> !m.clustered())
                    .max(Comparator.comparingDouble(MarkStat::avgReturnPct)).orElse(null);
            result.add(new StrategyExitTiming(strategy,
                    curve.stream().mapToInt(MarkStat::samples).max().orElse(0),
                    curve,
                    best == null ? null : best.mark(),
                    best == null ? null : best.avgReturnPct(),
                    roundTripCostPct));
        });
        return result;
    }

    /**
     * 마크별 단일일 클러스터 판정(순수) — {@code ControlAnalysisService.toStat}·
     * {@code MultidayExitAnalysisService.cluster} 와 같은 규칙:
     * 건수 편중(비중&gt;80% 또는 거래일&lt;3) <b>또는</b> 최대기여일 제외(LOO) 시 net 부호 반전.
     *
     * <p>🐞 2026-08-26 추가: 클러스터 가드가 {@code control-diagnosis}(8/21)·{@code multiday-exit}(8/24)에는
     * 깔렸는데 <b>청산곡선에는 없어</b> 같은 데이터가 엔드포인트에 따라 정반대 결론을 냈다 — 실측 REVERSAL_L의
     * 곡선은 90분 +0.31% → 300분 <b>+2.28%</b>로 단조 상승해 "오래 들수록 좋다"로 보였지만, 진입일로 쪼개면
     * 8/24는 +0.18 → +0.11로 <b>완전히 평평</b>하고 상승분이 통째로 8/25 하루(+0.97 → +4.34)였다.</p>
     *
     * <p>⚠️ 이 곡선의 최대 마크는 곧 {@link StrategyHoldTimeProvider}의 보유시간이자
     * {@link StrategyPerformanceGate}의 채점 horizon이므로, 오염되면 <b>실제 청산 시점과 게이트 net이 함께</b>
     * 하루짜리 허수 위에 서게 된다 — 그래서 여기 가드가 다른 어느 엔드포인트보다 파급이 크다.</p>
     *
     * <p>진입일이 하나도 안 붙은 마크(조인 실패·구 표본)는 판정을 생략하고({@code clustered=false})
     * {@code distinctDays=0} 으로 <b>진단 부재임이 드러나게</b> 한다 — "클러스터 아님"과 혼동하지 않도록.</p>
     */
    static MarkStat cluster(String label, int mark, int n, double net, double winRate,
                            Map<String, double[]> days) {
        if (days.isEmpty() || n <= 0) {
            return new MarkStat(label, mark, n, net, winRate, 0, null, null, null, false);
        }
        double maxCount = 0;
        String topDay = null;
        double topAbs = -1;
        for (Map.Entry<String, double[]> e : days.entrySet()) {
            if (e.getValue()[0] > maxCount) maxCount = e.getValue()[0];
            if (Math.abs(e.getValue()[1]) > topAbs) {
                topAbs = Math.abs(e.getValue()[1]);
                topDay = e.getKey();
            }
        }
        double share = 100.0 * maxCount / n;
        Double netExTop = null;
        if (topDay != null && days.size() > 1) {
            int restN = n - (int) days.get(topDay)[0];
            if (restN > 0) netExTop = round2((net * n - days.get(topDay)[1]) / restN);
        }
        boolean clustered = share > MAX_DAY_SHARE_PCT
                || days.size() < MIN_DISTINCT_DAYS
                || (netExTop != null && Math.signum(net) != Math.signum(netExTop));
        return new MarkStat(label, mark, n, net, winRate,
                days.size(), round2(share), topDay, netExTop, clustered);
    }

    private String label(int mark) {
        return mark == EOD_MARK ? "종가(EOD)" : mark + "분";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
