package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Feature-space 마이닝 리포트 (2026-08-12, 생성적 분석).
 *
 * <p>전략 단위 채점(무엇을 코딩했나)이 아니라, 쌓인 {@link TradeOutcome}을 <b>feature 축으로 bin</b>해
 * "어떤 조건 구간의 진입이 수익이었나"를 스캔한다 — 아직 전략화 안 된 수익 pocket을 surface(사람이 코딩하기 전에
 * 데이터가 먼저 제안). 전 전략 진입을 pool하므로 전략 교차 패턴이 드러난다.</p>
 *
 * <p><b>허수 방지</b>: 각 bucket에 교차거래일 가드(한 거래일 점유율 ≤ maxDaySharePct)를 적용 — 단일일 클러스터
 * pocket(예: 반등일 하루가 만든 가짜 +net)을 highlights에서 제외한다({@link StrategyPerformanceGate}와 동일 사상).
 * net = (종가−매수)/매수×100 − 왕복비용 − 슬리피지(close horizon). ⚠️ 전략 교차 pool이라 같은 종목·일자가
 * 여러 전략 행으로 중복 가능(v1 수용, includeControl=true면 미진입 후보까지 포함해 반사실 확대).</p>
 */
@Service
public class FeatureMiningService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;

    public FeatureMiningService(TradeOutcomeRepository tradeOutcomeRepository,
                                ExecutionCostModel executionCostModel,
                                @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
    }

    public record Bucket(String feature, String range, int n, int distinctDays, double maxDaySharePct,
                         double netAvgPct, double winRatePct, boolean clustered, String topStrategy) {}

    public record FeatureMining(String feature, List<Bucket> buckets) {}

    public record MiningReport(int rows, int lookbackDays, String market, String regime, int minSamples,
                               double maxDaySharePct, List<Bucket> highlights, List<Bucket> avoid,
                               List<FeatureMining> features) {}

    /** 숫자 feature bin 정의: 이름·추출자·경계(오름차순). */
    private record NumDef(String name, Function<TradeOutcome, Double> f, double[] edges) {}

    private List<NumDef> numericDefs() {
        List<NumDef> d = new ArrayList<>();
        d.add(new NumDef("거래량배수", TradeOutcome::getEntryVolumeRatio, new double[]{2, 4, 8, 15}));
        d.add(new NumDef("당일등락률%", TradeOutcome::getEntryChangeRate, new double[]{0, 2, 5, 10}));
        d.add(new NumDef("추천점수", TradeOutcome::getEntryRecScore, new double[]{40, 55, 70}));
        d.add(new NumDef("PER", TradeOutcome::getEntryPer, new double[]{0, 10, 20, 40}));
        d.add(new NumDef("PBR", TradeOutcome::getEntryPbr, new double[]{1, 2, 4}));
        d.add(new NumDef("시총(억)", o -> o.getEntryMarketCap() == null ? null : o.getEntryMarketCap().doubleValue(),
                new double[]{1000, 5000, 20000}));
        d.add(new NumDef("ATR%", TradeOutcome::getEntryAtrPct, new double[]{2, 4, 7}));
        d.add(new NumDef("고가거리%", TradeOutcome::getEntryDistHighPct, new double[]{-5, -2, 0}));
        d.add(new NumDef("ret5d%", TradeOutcome::getEntryRet5dPct, new double[]{-5, 0, 5, 10}));
        d.add(new NumDef("체결강도%", TradeOutcome::getEntryExecStrength, new double[]{100, 150, 200}));
        d.add(new NumDef("지수mom30", TradeOutcome::getEntryIndexMom30, new double[]{0}));
        d.add(new NumDef("뉴스1h건수", o -> o.getEntryNewsCnt1h() == null ? null : o.getEntryNewsCnt1h().doubleValue(),
                new double[]{1, 3}));
        return d;
    }

    private record CatDef(String name, Function<TradeOutcome, String> f) {}

    private List<CatDef> categoricalDefs() {
        return List.of(
                new CatDef("국면", TradeOutcome::getEntryMarketTrend),
                new CatDef("시장", TradeOutcome::getEntryMarket),
                new CatDef("업종", TradeOutcome::getEntrySector),
                new CatDef("전략", TradeOutcome::getStrategy));
    }

    public MiningReport mine(int lookbackDays, String market, String regime, int minSamples,
                             double maxDaySharePct, boolean includeControl) {
        String cutoff = LocalDate.now(SEOUL).minusDays(lookbackDays).format(YYYYMMDD);
        List<TradeOutcome> rows = new ArrayList<>();
        for (TradeOutcome o : tradeOutcomeRepository.findByAlertDateGreaterThanEqual(cutoff)) {
            if (!includeControl && o.isControl()) continue;
            if (o.getPriceClose() == null || o.getBuyPrice() <= 0) continue;
            if (market != null && !market.isBlank() && !market.equals(o.getEntryMarket())) continue;
            if (regime != null && !regime.isBlank() && !regime.equals(o.getEntryMarketTrend())) continue;
            rows.add(o);
        }

        List<FeatureMining> features = new ArrayList<>();
        for (NumDef def : numericDefs()) {
            features.add(mineFeature(def.name(), rows, o -> {
                Double v = def.f().apply(o);
                return v == null ? null : binLabel(v, def.edges());
            }, minSamples, maxDaySharePct));
        }
        for (CatDef def : categoricalDefs()) {
            features.add(mineFeature(def.name(), rows, o -> {
                String v = def.f().apply(o);
                return v == null || v.isBlank() ? null : v;
            }, minSamples, maxDaySharePct));
        }

        // 하이라이트: 가드 통과(비클러스터·표본충분) bucket 중 net 상위/하위
        List<Bucket> all = new ArrayList<>();
        for (FeatureMining fm : features) for (Bucket b : fm.buckets()) if (!b.clustered()) all.add(b);
        List<Bucket> highlights = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct).reversed()).limit(15).toList();
        List<Bucket> avoid = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct)).limit(10).toList();

        return new MiningReport(rows.size(), lookbackDays, market, regime, minSamples, maxDaySharePct,
                highlights, avoid, features);
    }

    private FeatureMining mineFeature(String feature, List<TradeOutcome> rows,
                                      Function<TradeOutcome, String> binner, int minSamples, double maxDaySharePct) {
        // bucketLabel → 순회 통계 누산
        Map<String, List<TradeOutcome>> byBucket = new LinkedHashMap<>();
        for (TradeOutcome o : rows) {
            String b = binner.apply(o);
            if (b == null) continue;
            byBucket.computeIfAbsent(b, k -> new ArrayList<>()).add(o);
        }
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<TradeOutcome>> e : byBucket.entrySet()) {
            List<TradeOutcome> g = e.getValue();
            if (g.size() < minSamples) continue;
            buckets.add(statOf(feature, e.getKey(), g, maxDaySharePct));
        }
        buckets.sort(Comparator.comparingDouble(Bucket::netAvgPct).reversed());
        return new FeatureMining(feature, buckets);
    }

    private Bucket statOf(String feature, String range, List<TradeOutcome> g, double maxDaySharePct) {
        double sum = 0; int wins = 0;
        Map<String, Integer> days = new LinkedHashMap<>();
        Map<String, Integer> strat = new LinkedHashMap<>();
        for (TradeOutcome o : g) {
            double net = netPct(o);
            sum += net;
            if (net > 0) wins++;
            days.merge(o.getAlertDate() == null ? "?" : o.getAlertDate(), 1, Integer::sum);
            strat.merge(o.getStrategy() == null ? "?" : o.getStrategy(), 1, Integer::sum);
        }
        int n = g.size();
        double maxShare = maxSharePct(days, n);
        boolean clustered = maxDaySharePct > 0 && maxShare > maxDaySharePct;
        String top = strat.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");
        return new Bucket(feature, range, n, days.size(), round2(maxShare),
                round2(sum / n), round2(100.0 * wins / n), clustered, top);
    }

    private double netPct(TradeOutcome o) {
        double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
        return (double) (o.getPriceClose() - o.getBuyPrice()) / o.getBuyPrice() * 100 - (roundTripCostPct + slip);
    }

    // ── 순수 코어(단위테스트 대상) ──────────────────────────────
    /** 값 v를 오름차순 경계 edges로 bin 라벨링. 예 edges[2,4,8]→ "<2","2~4","4~8","≥8". */
    static String binLabel(double v, double[] edges) {
        if (edges.length == 0) return "all";
        if (v < edges[0]) return "<" + fmt(edges[0]);
        for (int i = 0; i < edges.length - 1; i++) if (v < edges[i + 1]) return fmt(edges[i]) + "~" + fmt(edges[i + 1]);
        return "≥" + fmt(edges[edges.length - 1]);
    }

    /** 최대 단일 거래일 점유율(%). */
    static double maxSharePct(Map<String, Integer> days, int n) {
        if (n <= 0 || days.isEmpty()) return 0;
        int max = 0;
        for (int c : days.values()) if (c > max) max = c;
        return 100.0 * max / n;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
