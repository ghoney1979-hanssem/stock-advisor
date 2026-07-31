package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 데이터 기반 청산(매도) 방식 비교: 기록된 가격경로에
 * ① 시간기반(권장 보유시간) ② 트레일링 스탑
 * ③ 신호기반(VWAP 이탈) ④ 신호기반(추세전환 근사)
 * 을 시뮬레이션해 전략별로 평균수익이 가장 높은 방식을 추천한다. (고정 TP/SL은 제외)
 *
 * <p>⚠️ 가격경로가 분 단위 이산(마크)값이라 청산 판정은 근사.
 * ③ VWAP 이탈은 각 마크에 저장된 실측 VWAP(wghn_avrg_stck_prc) 대비 가격 하향돌파로 판정 —
 * VWAP 지표가 저장된 표본만 집계(과거 표본은 vwap=null 이라 제외).
 * ④ 추세전환은 직전 마크 대비 하락으로 근사하는 가격경로 프록시.</p>
 *
 * <p>모든 방식의 평균수익률·승률은 <b>왕복 매매비용(stockadvisor.cost.round-trip-pct, 기본 0.18%)을
 * 차감한 순수익</b> 기준(승/패 판정도 net&gt;0). 단 MFE/MAE는 잠재 가격 변동폭이라 비용 미차감(gross).
 * 슬리피지는 미반영.</p>
 */
@Service
public class ExitStrategyService {

    private static final int EOD_MARK = -1;
    private static final double[] DEFAULT_TRAIL_GRID = {1.0, 2.0, 3.0, 5.0, 7.0, 10.0};   // 트레일링 되돌림 % 후보
    private static final double[] FLOW_THRESHOLD_GRID = {0.0, -0.3};   // ⑤ 지수흐름 반전 임계(mom30 ≤ 이 값이면 청산) 후보

    private final OutcomeSampleRepository outcomeSampleRepository;
    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;   // 왕복 매매비용(수수료+거래세) — 슬리피지는 ExecutionCostModel 별도 가산
    private final double[] trailGrid;         // 트레일링 되돌림% 후보(env 튜닝 가능)
    private final int trendConfirm;           // 추세전환: N회 연속 하락 마크여야 청산(라이브 confirm과 동일 파라미터)

    public ExitStrategyService(OutcomeSampleRepository outcomeSampleRepository,
                               TradeOutcomeRepository tradeOutcomeRepository,
                               ExecutionCostModel executionCostModel,
                               @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                               @Value("${stockadvisor.exit.trail-grid-pct:}") String trailGridCsv,
                               @Value("${stockadvisor.trading.adaptive-exit-method.trend-confirm:3}") int trendConfirm) {
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
        this.trailGrid = parseGrid(trailGridCsv);
        this.trendConfirm = Math.max(1, trendConfirm);
    }

    /** CSV("1,2,3,5,7,10") → double[]. 비거나 파싱 실패 시 기본 그리드. */
    private static double[] parseGrid(String csv) {
        if (csv == null || csv.isBlank()) return DEFAULT_TRAIL_GRID;
        try {
            String[] parts = csv.split(",");
            double[] g = new double[parts.length];
            for (int i = 0; i < parts.length; i++) g[i] = Double.parseDouble(parts[i].trim());
            return g.length == 0 ? DEFAULT_TRAIL_GRID : g;
        } catch (NumberFormatException e) {
            return DEFAULT_TRAIL_GRID;
        }
    }

    /** 해당 매수가 기준 왕복비용(수수료 + 추정 슬리피지) %. */
    private double costPct(long buy) {
        return roundTripCostPct + executionCostModel.estimateRoundTripSlippagePct(buy);
    }

    public record ExitMethod(String method, String detail, double avgReturnPct, double winRatePct, int samples,
                             com.stockadvisor.domain.ExitMethodType type, double param) {}

    /** 전략별 적응형 청산방식 추천(평균수익 최대). param: TRAILING=되돌림%, 그 외 미사용. */
    public record BestExit(String strategy, com.stockadvisor.domain.ExitMethodType type, double param,
                           double avgReturnPct, int samples) {}

    /** 전략별 평균수익 최대 청산방식(구조화) — ExitMethodProvider 가 소비. */
    public List<BestExit> recommend() {
        List<BestExit> out = new ArrayList<>();
        for (ExitComparison c : compare()) {
            ExitMethod best = c.methods().stream()
                    .max(Comparator.comparingDouble(ExitMethod::avgReturnPct)).orElse(null);
            if (best != null) {
                out.add(new BestExit(c.strategy(), best.type(), best.param(), best.avgReturnPct(), best.samples()));
            }
        }
        return out;
    }
    public record ExitComparison(String strategy, int outcomes, List<ExitMethod> methods,
                                 Double avgMfePct, Double avgMaePct,
                                 String recommended, Double recommendedAvgReturnPct,
                                 double roundTripCostPct) {}

    record Point(int mark, long price, Double vwap) {}   // 패키지 가시성 — 시뮬 순수함수 테스트용

    public record TrailPoint(double trailPct, int samples, Double avgNetPct, Double winRatePct) {}
    public record StrategyTrailGrid(String strategy, List<TrailPoint> grid) {}

    /** 전략별 트레일링 되돌림% 격자(1~10%) 전체의 평균 net·승률 — 최선만이 아니라 전 %를 노출. */
    public List<StrategyTrailGrid> trailingGrid() {
        Map<Long, List<Point>> pathByOutcome = new HashMap<>();
        Map<Long, String> stratByOutcome = new HashMap<>();
        Map<Long, Long> buyByOutcome = new HashMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findAll()) {
            pathByOutcome.computeIfAbsent(s.getOutcomeId(), k -> new ArrayList<>())
                    .add(new Point(s.getMarkMinutes(), s.getPrice(), s.getVwap()));
            stratByOutcome.put(s.getOutcomeId(), s.getStrategy());
            buyByOutcome.put(s.getOutcomeId(), s.getBuyPrice());
        }
        pathByOutcome.values().forEach(p -> p.sort(Comparator.comparingInt(
                pt -> pt.mark() == EOD_MARK ? Integer.MAX_VALUE : pt.mark())));
        Map<String, List<Long>> idsByStrategy = new TreeMap<>();
        stratByOutcome.forEach((id, strat) -> idsByStrategy.computeIfAbsent(strat, k -> new ArrayList<>()).add(id));

        List<StrategyTrailGrid> out = new ArrayList<>();
        idsByStrategy.forEach((strategy, ids) -> {
            List<TrailPoint> grid = new ArrayList<>();
            for (double trail : trailGrid) {
                double sumR = 0; int wins = 0, n = 0;
                for (Long id : ids) {
                    long buy = buyByOutcome.get(id);
                    if (buy <= 0) continue;
                    double r = simulateTrailing(pathByOutcome.get(id), buy, trail) - costPct(buy);
                    sumR += r; if (r > 0) wins++; n++;
                }
                grid.add(new TrailPoint(trail, n, n == 0 ? null : round2(sumR / n), n == 0 ? null : round2(100.0 * wins / n)));
            }
            out.add(new StrategyTrailGrid(strategy, grid));
        });
        return out;
    }

    public List<ExitComparison> compare() {
        Map<Long, List<Point>> pathByOutcome = new HashMap<>();
        Map<Long, String> stratByOutcome = new HashMap<>();
        Map<Long, Long> buyByOutcome = new HashMap<>();
        for (OutcomeSample s : outcomeSampleRepository.findAll()) {
            pathByOutcome.computeIfAbsent(s.getOutcomeId(), k -> new ArrayList<>())
                    .add(new Point(s.getMarkMinutes(), s.getPrice(), s.getVwap()));
            stratByOutcome.put(s.getOutcomeId(), s.getStrategy());
            buyByOutcome.put(s.getOutcomeId(), s.getBuyPrice());
        }
        pathByOutcome.values().forEach(p -> p.sort(Comparator.comparingInt(
                pt -> pt.mark() == EOD_MARK ? Integer.MAX_VALUE : pt.mark())));

        Map<Long, TradeOutcome> outcomeById = new HashMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) outcomeById.put(o.getId(), o);

        // (시장|날짜)별 지수경로 앵커: 그날 그 시장 진입들의 (진입시각 → 진입시점 지수등락률). 흐름반전 시뮬용.
        Map<String, TreeMap<java.time.Instant, Double>> anchorSeries = new HashMap<>();
        for (TradeOutcome o : outcomeById.values()) {
            if (o.getEntryMarket() == null || o.getEntryMarketChange() == null || o.getAlertTime() == null) continue;
            anchorSeries.computeIfAbsent(o.getEntryMarket() + "|" + o.getAlertDate(), k -> new TreeMap<>())
                    .put(o.getAlertTime(), o.getEntryMarketChange());
        }

        Map<String, List<Long>> idsByStrategy = new TreeMap<>();
        stratByOutcome.forEach((id, strat) -> idsByStrategy.computeIfAbsent(strat, k -> new ArrayList<>()).add(id));

        List<ExitComparison> result = new ArrayList<>();
        idsByStrategy.forEach((strategy, ids) -> {
            List<ExitMethod> methods = new ArrayList<>();

            // ① 시간기반: 마크별 평균수익 → 최고 마크
            Map<Integer, double[]> markAgg = new TreeMap<>();
            for (Long id : ids) {
                long buy = buyByOutcome.get(id);
                if (buy <= 0) continue;
                for (Point pt : pathByOutcome.get(id)) {
                    double r = ret(pt.price(), buy) - costPct(buy);   // 순수익(수수료+슬리피지)
                    double[] a = markAgg.computeIfAbsent(pt.mark(), k -> new double[3]);
                    a[0] += r; if (r > 0) a[1]++; a[2]++;
                }
            }
            ExitMethod timeBased = bestTimeExit(markAgg);
            if (timeBased != null) methods.add(timeBased);

            // ② 트레일링 스탑: 되돌림 격자 → 최고
            ExitMethod bestTrail = null;
            for (double trail : trailGrid) {
                double sumR = 0; int wins = 0, n = 0;
                for (Long id : ids) {
                    long buy = buyByOutcome.get(id);
                    if (buy <= 0) continue;
                    double r = simulateTrailing(pathByOutcome.get(id), buy, trail) - costPct(buy);
                    sumR += r; if (r > 0) wins++; n++;
                }
                if (n == 0) continue;
                double avg = round2(sumR / n);
                if (bestTrail == null || avg > bestTrail.avgReturnPct()) {
                    bestTrail = new ExitMethod("트레일링", String.format("되돌림 %.1f%%", trail),
                            avg, round2(100.0 * wins / n), n, com.stockadvisor.domain.ExitMethodType.TRAILING, trail);
                }
            }
            if (bestTrail != null) methods.add(bestTrail);

            // ③ 신호기반(VWAP 이탈): 가격이 VWAP 아래로 내려가는 첫 시점 청산 (진짜 신호청산)
            double vSum = 0; int vWins = 0, vN = 0;
            for (Long id : ids) {
                long buy = buyByOutcome.get(id);
                if (buy <= 0) continue;
                Double r = simulateVwapExit(pathByOutcome.get(id), buy);
                if (r == null) continue;   // VWAP 데이터 없는 과거 표본 제외
                double rn = r - costPct(buy);
                vSum += rn; if (rn > 0) vWins++; vN++;
            }
            if (vN > 0) {
                methods.add(new ExitMethod("신호기반(VWAP 이탈)", "가격<VWAP 첫 시점",
                        round2(vSum / vN), round2(100.0 * vWins / vN), vN, com.stockadvisor.domain.ExitMethodType.VWAP, 0));
            }

            // ④ 신호기반(추세전환 근사): 직전 마크 대비 하락 시 청산 (가격경로 프록시)
            double sumR = 0; int wins = 0, n = 0;
            for (Long id : ids) {
                long buy = buyByOutcome.get(id);
                if (buy <= 0) continue;
                double r = simulateTrendReversal(pathByOutcome.get(id), buy) - costPct(buy);
                sumR += r; if (r > 0) wins++; n++;
            }
            if (n > 0) {
                methods.add(new ExitMethod("신호기반(추세전환 근사)", trendConfirm + "회 연속 마크 하락 시",
                        round2(sumR / n), round2(100.0 * wins / n), n, com.stockadvisor.domain.ExitMethodType.TREND_REVERSAL, 0));
            }

            // ⑤ 신호기반(지수흐름 반전): 진입 시장의 지수 mom30(경로 보간)이 임계 이하로 음전하는 첫 마크 청산.
            //    지수경로는 같은 (시장,날짜) 진입들의 entry_market_change 앵커 보간(FlowBacktag 과 동일 발상) — 앵커 부족 표본 제외.
            ExitMethod bestFlow = null;
            for (double th : FLOW_THRESHOLD_GRID) {
                double fSum = 0; int fWins = 0, fN = 0;
                for (Long id : ids) {
                    long buy = buyByOutcome.get(id);
                    TradeOutcome o = outcomeById.get(id);
                    if (buy <= 0 || o == null) continue;
                    Double r = simulateFlowReversal(pathByOutcome.get(id), buy, o, anchorSeries, th);
                    if (r == null) continue;   // 지수경로 앵커 부족 표본 제외
                    double rn = r - costPct(buy);
                    fSum += rn; if (rn > 0) fWins++; fN++;
                }
                if (fN == 0) continue;
                double avg = round2(fSum / fN);
                if (bestFlow == null || avg > bestFlow.avgReturnPct()) {
                    bestFlow = new ExitMethod("신호기반(지수흐름 반전)", String.format("지수 mom30 ≤ %.1f%%", th),
                            avg, round2(100.0 * fWins / fN), fN, com.stockadvisor.domain.ExitMethodType.FLOW_REVERSAL, th);
                }
            }
            if (bestFlow != null) methods.add(bestFlow);

            // MFE/MAE
            double sumMfe = 0, sumMae = 0; int m = 0;
            for (Long id : ids) {
                TradeOutcome o = outcomeById.get(id);
                long buy = buyByOutcome.get(id);
                if (o == null || buy <= 0 || o.getPeakPrice() == null || o.getTroughPrice() == null) continue;
                sumMfe += ret(o.getPeakPrice(), buy);
                sumMae += ret(o.getTroughPrice(), buy);
                m++;
            }
            Double avgMfe = m == 0 ? null : round2(sumMfe / m);
            Double avgMae = m == 0 ? null : round2(sumMae / m);

            // 추천: 평균수익 최고 방식
            ExitMethod best = methods.stream().max(Comparator.comparingDouble(ExitMethod::avgReturnPct)).orElse(null);
            result.add(new ExitComparison(strategy, ids.size(), methods, avgMfe, avgMae,
                    best == null ? "데이터 부족" : best.method() + " (" + best.detail() + ")",
                    best == null ? null : best.avgReturnPct(), roundTripCostPct));
        });
        return result;
    }

    /**
     * 지수흐름 반전 청산 시뮬: 각 마크의 절대시각에서 지수 mom30(= 지수등락률(t) − 지수등락률(t−30분), 앵커 보간)이
     * {@code thresholdPct} 이하로 음전하는 첫 마크에서 청산. 미발동 시 EOD. 앵커 부족(보간 불가)이면 null(표본 제외).
     * 정적/순수 — KIS 없이 단위테스트.
     */
    static Double simulateFlowReversal(List<Point> path, long buy, TradeOutcome outcome,
                                       Map<String, TreeMap<java.time.Instant, Double>> anchorSeries, double thresholdPct) {
        if (outcome.getEntryMarket() == null || outcome.getAlertTime() == null) return null;
        TreeMap<java.time.Instant, Double> series = anchorSeries.get(outcome.getEntryMarket() + "|" + outcome.getAlertDate());
        if (series == null || series.size() < 3) return null;   // 앵커 3개 미만이면 보간 신뢰 불가
        boolean judged = false;
        for (Point pt : path) {
            if (pt.mark() == EOD_MARK) break;
            java.time.Instant t = outcome.getAlertTime().plusSeconds(pt.mark() * 60L);
            Double now = interpolate(series, t);
            Double prev = interpolate(series, t.minusSeconds(30 * 60L));
            if (now == null || prev == null) continue;   // 이 마크는 판정 불가 — 다음 마크
            judged = true;
            if (now - prev <= thresholdPct) return ret(pt.price(), buy);   // 흐름 음전 → 청산
        }
        if (!judged) return null;   // 한 번도 판정 못 했으면 표본 제외(경로 커버리지 부족)
        return ret(path.get(path.size() - 1).price(), buy);   // 미발동 → EOD
    }

    /** 앵커(시각→지수등락률) 선형 보간. 범위 밖(양끝 15분 초과)이면 null. */
    static Double interpolate(TreeMap<java.time.Instant, Double> series, java.time.Instant t) {
        var floor = series.floorEntry(t);
        var ceil = series.ceilingEntry(t);
        if (floor == null && ceil == null) return null;
        if (floor == null) return java.time.Duration.between(t, ceil.getKey()).toMinutes() <= 15 ? ceil.getValue() : null;
        if (ceil == null) return java.time.Duration.between(floor.getKey(), t).toMinutes() <= 15 ? floor.getValue() : null;
        long span = java.time.Duration.between(floor.getKey(), ceil.getKey()).toSeconds();
        if (span == 0) return floor.getValue();
        double w = (double) java.time.Duration.between(floor.getKey(), t).toSeconds() / span;
        return floor.getValue() + (ceil.getValue() - floor.getValue()) * w;
    }

    /** 트레일링: 고점 대비 trail% 되돌림 시 청산. 미발동 시 EOD. */
    private double simulateTrailing(List<Point> path, long buy, double trail) {
        long peak = path.get(0).price();
        for (Point pt : path) {
            if (pt.price() > peak) peak = pt.price();
            double drawdown = peak > 0 ? (double) (peak - pt.price()) / peak * 100 : 0;
            if (drawdown >= trail) return ret(pt.price(), buy);
        }
        return ret(path.get(path.size() - 1).price(), buy);
    }

    /**
     * VWAP 이탈 청산: 가격이 VWAP 아래로 내려가는 첫 마크에서 청산. 미발동 시 EOD.
     * VWAP 데이터가 전혀 없으면(과거 표본) null 반환 → 집계 제외.
     */
    private Double simulateVwapExit(List<Point> path, long buy) {
        boolean hasVwap = false;
        for (Point pt : path) {
            if (pt.vwap() == null) continue;
            hasVwap = true;
            if (pt.price() < pt.vwap()) return ret(pt.price(), buy);
        }
        if (!hasVwap) return null;
        return ret(path.get(path.size() - 1).price(), buy);
    }

    /** 추세전환 근사: N회 연속 마크 하락 시 청산(단일 눌림 휩쏘 방지). 미발동 시 EOD. */
    private double simulateTrendReversal(List<Point> path, long buy) {
        int down = 0;
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i).price() < path.get(i - 1).price()) {
                down++;
                if (down >= trendConfirm) return ret(path.get(i).price(), buy);
            } else {
                down = 0;
            }
        }
        return ret(path.get(path.size() - 1).price(), buy);
    }

    private ExitMethod bestTimeExit(Map<Integer, double[]> markAgg) {
        String bestMark = null; double bestAvg = -1e9, bestWin = 0; int bestN = 0, bestMarkMin = 0;
        for (Map.Entry<Integer, double[]> e : markAgg.entrySet()) {
            double[] a = e.getValue();
            int n = (int) a[2];
            if (n == 0) continue;
            double avg = a[0] / n;
            if (avg > bestAvg) { bestAvg = avg; bestWin = 100.0 * a[1] / n; bestMark = label(e.getKey()); bestN = n; bestMarkMin = e.getKey(); }
        }
        return bestMark == null ? null
                : new ExitMethod("시간기반", bestMark, round2(bestAvg), round2(bestWin), bestN,
                        com.stockadvisor.domain.ExitMethodType.TIME, bestMarkMin);
    }

    private static double ret(long price, long buy) {
        return (double) (price - buy) / buy * 100;
    }

    private String label(int mark) {
        return mark == EOD_MARK ? "종가(EOD)" : mark + "분";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
