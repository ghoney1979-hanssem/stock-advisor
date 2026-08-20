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
 *
 * <p><b>⚠️ 진입-대조군 비교의 horizon 유의</b>(2026-08-14 수정): 대조군은 경량 추적이라 <b>exit 마크가 거의 없다</b>.
 * 따라서 {@code horizon=exit}에서는 대조군 커버리지가 낮아 {@code controlNetPct}/{@code edgeVsControlPct}가
 * <b>null로 비워진다</b>({@link #MIN_CONTROL_COVERAGE_PCT}) — 편향 부분집합으로 잘못된 결론을 내는 것보다 낫다.
 * <b>진입-대조군 반사실 비교는 {@code horizon=close}에서 수행</b>하고, exit horizon은 진입군 pocket 랭킹에만 쓸 것.</p>
 */
@Service
public class FeatureMiningService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 대조군 net·edge를 신뢰하려면 필요한 최소 커버리지(%).
     *
     * <p>🐞 2026-08-14 실측 버그: horizon="exit"이면 진입군은 권장청산마크로 평가되는데 <b>대조군은 exit 마크를
     * 거의 수집하지 않아</b>(경량 추적 — 가격 horizon만) 커버리지가 ~15%로 떨어진다. 그런데도 그 15%의
     * <b>편향된 부분집합</b>으로 controlNet을 계산해 {@code edgeVsControlPct}가 부호까지 뒤집혔다
     * (ret5d%&lt;-5 pocket: exit −2.51%p ↔ close <b>+0.13%p</b> / 지수mom30≥0: −1.56 ↔ −0.30).
     * → 커버리지 미달이면 <b>controlNet·edge를 null 처리</b>하고 커버리지 자체를 응답에 실어 소비자가 판정하게 한다.
     * 대조군 exit 마크 전량 수집은 추적 부하상 비현실적이라 "수집 확대"가 아니라 "신뢰도 노출"이 해법.</p>
     */
    static final double MIN_CONTROL_COVERAGE_PCT = 70.0;

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final ExitHorizonPriceResolver exitResolver;   // horizon="exit"(게이트 동일 청산시점) 지원
    private final double roundTripCostPct;

    public FeatureMiningService(TradeOutcomeRepository tradeOutcomeRepository,
                                ExecutionCostModel executionCostModel,
                                ExitHorizonPriceResolver exitResolver,
                                @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.exitResolver = exitResolver;
        this.roundTripCostPct = roundTripCostPct;
    }

    /**
     * @param n/netAvgPct/winRatePct/clustered/topStrategy  진입(entered) 기준 pocket 통계
     * @param controlN/controlNetPct                        같은 pocket의 대조군(미진입 후보) net — "진입이 미진입보다 나았나" 비교.
     *                                                      controlN은 <b>해당 horizon 가격이 실제로 있는</b> 대조군 수
     * @param controlTotalN                                 pocket의 전체 대조군 수(가격 유무 무관) — 커버리지 분모
     * @param controlCoveragePct                            controlN/controlTotalN×100. 이 값이 낮으면 대조군이 <b>편향된 부분집합</b>
     * @param edgeVsControlPct                              진입net − 대조군net(양수면 진입이 이 조건에서 가치 추가).
     *                                                      ⚠️ <b>커버리지 미달이면 null</b>(아래 MIN_CONTROL_COVERAGE_PCT 참조)
     */
    public record Bucket(String feature, String range, int n, int distinctDays, double maxDaySharePct,
                         double netAvgPct, double winRatePct, boolean clustered, String topStrategy,
                         int controlN, Double controlNetPct, Double edgeVsControlPct,
                         int controlTotalN, double controlCoveragePct) {}

    public record FeatureMining(String feature, List<Bucket> buckets) {}

    public record MiningReport(int rows, int lookbackDays, String horizon, String market, String regime, int minSamples,
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
        d.add(new NumDef("종목갭%", TradeOutcome::getEntryGapPct, new double[]{2, 4, 7}));        // K 갭 구간별 성과
        d.add(new NumDef("지수갭%", TradeOutcome::getEntryIndexGapPct, new double[]{0, 1, 2}));   // 지수 통째 갭업일 여부
        d.add(new NumDef("뉴스1h건수", o -> o.getEntryNewsCnt1h() == null ? null : o.getEntryNewsCnt1h().doubleValue(),
                new double[]{1, 3}));
        // 뉴스경과분(2026-08-21): 승자/패자 분석에서 <b>10개 전략 중 8개가 같은 방향</b>으로 갈렸다 —
        // 승자의 최신 뉴스가 더 최근이다(E 1,153 vs 2,204 / G 2,005 vs 3,697 / A 1,168 vs 3,027 / F 1,934 vs 2,439 / J 3,053 vs 4,823).
        // 전략별 조건이 아니라 <b>전 전략 공통</b>으로 나온 첫 신호라 bin별 net을 볼 가치가 있다.
        // 첫 경계 60분은 FRESH_BAD_NEWS 가드의 fresh-news-window-minutes와 같은 기준(해석 정합).
        // ⚠️ <b>대조군은 뉴스 태깅이 0%</b>(진입 시에만 종목당 1콜 lazy) → 이 축은 <b>진입군 랭킹 전용</b>이고
        //    edgeVsControlPct는 구조적으로 null이다(커버리지 0%). 반사실 비교를 하려면 대조군 태깅이 선행돼야 한다.
        d.add(new NumDef("뉴스경과분", o -> o.getEntryNewsAgeMin() == null ? null : o.getEntryNewsAgeMin().doubleValue(),
                new double[]{60, 240, 720, 2880}));
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

    public MiningReport mine(int lookbackDays, String horizon, String market, String regime, int minSamples,
                             double maxDaySharePct, boolean includeControl) {
        String cutoff = LocalDate.now(SEOUL).minusDays(lookbackDays).format(YYYYMMDD);
        List<TradeOutcome> entered = new ArrayList<>();
        List<TradeOutcome> control = new ArrayList<>();   // 미진입 후보(반사실 비교용) — includeControl일 때만
        for (TradeOutcome o : tradeOutcomeRepository.findByAlertDateGreaterThanEqual(cutoff)) {
            if (o.getBuyPrice() <= 0) continue;
            if (market != null && !market.isBlank() && !market.equals(o.getEntryMarket())) continue;
            if (regime != null && !regime.isBlank() && !regime.equals(o.getEntryMarketTrend())) continue;
            if (o.isControl()) { if (includeControl) control.add(o); } else entered.add(o);
        }

        // horizon 통일: outcomeId → net%. horizon="exit"이면 게이트와 동일하게 전략별 청산시점 가격(스윙=nextClose,
        // 그 외=권장청산마크 OutcomeSample). 마크 미수집이면 제외(fail-closed). ⚠️ 대조군은 exit 마크 미수집이라
        // exit horizon에선 자동 제외 → 진입-대조군 비교는 close horizon에서만 채워짐(대조군은 종가만 보유).
        Map<Long, Double> netByOutcome = new java.util.HashMap<>();
        List<TradeOutcome> both = new ArrayList<>(entered); both.addAll(control);
        Map<String, List<TradeOutcome>> byStrategy = new LinkedHashMap<>();
        for (TradeOutcome o : both) byStrategy.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        for (Map.Entry<String, List<TradeOutcome>> e : byStrategy.entrySet()) {
            String effHz = "exit".equals(horizon) ? exitResolver.horizonFor(e.getKey(), "exit") : horizon;
            Function<TradeOutcome, Long> px = exitResolver.priceFor(e.getKey(), effHz);
            for (TradeOutcome o : e.getValue()) {
                Long price = px.apply(o);
                if (price != null && price > 0) netByOutcome.put(o.getId(), netPct(o, price));
            }
        }
        List<TradeOutcome> re = entered.stream().filter(o -> netByOutcome.containsKey(o.getId())).toList();
        // ⚠️ 대조군은 <b>필터하지 않고 전량</b> 넘긴다 — bucket마다 (해당 horizon 가격 보유분 / 전체)로
        //    커버리지를 계산해야 편향 부분집합 여부를 판정할 수 있기 때문(MIN_CONTROL_COVERAGE_PCT).
        List<TradeOutcome> rc = control;

        List<FeatureMining> features = new ArrayList<>();
        for (NumDef def : numericDefs()) {
            features.add(mineFeature(def.name(), re, rc, o -> {
                Double v = def.f().apply(o);
                return v == null ? null : binLabel(v, def.edges());
            }, minSamples, maxDaySharePct, netByOutcome));
        }
        for (CatDef def : categoricalDefs()) {
            features.add(mineFeature(def.name(), re, rc, o -> {
                String v = def.f().apply(o);
                return v == null || v.isBlank() ? null : v;
            }, minSamples, maxDaySharePct, netByOutcome));
        }
        List<TradeOutcome> rows = re;

        // 하이라이트: 가드 통과(비클러스터·표본충분) bucket 중 net 상위/하위
        List<Bucket> all = new ArrayList<>();
        for (FeatureMining fm : features) for (Bucket b : fm.buckets()) if (!b.clustered()) all.add(b);
        List<Bucket> highlights = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct).reversed()).limit(15).toList();
        List<Bucket> avoid = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct)).limit(10).toList();

        return new MiningReport(rows.size(), lookbackDays, horizon == null ? "close" : horizon, market, regime,
                minSamples, maxDaySharePct, highlights, avoid, features);
    }

    private FeatureMining mineFeature(String feature, List<TradeOutcome> entered, List<TradeOutcome> control,
                                      Function<TradeOutcome, String> binner,
                                      int minSamples, double maxDaySharePct, Map<Long, Double> netByOutcome) {
        Map<String, List<TradeOutcome>> byBucket = binGroups(entered, binner);
        Map<String, List<TradeOutcome>> byBucketControl = binGroups(control, binner);
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<TradeOutcome>> e : byBucket.entrySet()) {
            List<TradeOutcome> g = e.getValue();
            if (g.size() < minSamples) continue;
            buckets.add(statOf(feature, e.getKey(), g,
                    byBucketControl.getOrDefault(e.getKey(), List.of()), maxDaySharePct, netByOutcome));
        }
        buckets.sort(Comparator.comparingDouble(Bucket::netAvgPct).reversed());
        return new FeatureMining(feature, buckets);
    }

    private Map<String, List<TradeOutcome>> binGroups(List<TradeOutcome> rows, Function<TradeOutcome, String> binner) {
        Map<String, List<TradeOutcome>> m = new LinkedHashMap<>();
        for (TradeOutcome o : rows) {
            String b = binner.apply(o);
            if (b != null) m.computeIfAbsent(b, k -> new ArrayList<>()).add(o);
        }
        return m;
    }

    private Bucket statOf(String feature, String range, List<TradeOutcome> g, List<TradeOutcome> controlG,
                          double maxDaySharePct, Map<Long, Double> netByOutcome) {
        double sum = 0; int wins = 0;
        Map<String, Integer> days = new LinkedHashMap<>();
        Map<String, Integer> strat = new LinkedHashMap<>();
        for (TradeOutcome o : g) {
            double net = netByOutcome.get(o.getId());
            sum += net;
            if (net > 0) wins++;
            days.merge(o.getAlertDate() == null ? "?" : o.getAlertDate(), 1, Integer::sum);
            strat.merge(o.getStrategy() == null ? "?" : o.getStrategy(), 1, Integer::sum);
        }
        int n = g.size();
        double enteredNet = round2(sum / n);
        double maxShare = maxSharePct(days, n);
        boolean clustered = maxDaySharePct > 0 && maxShare > maxDaySharePct;
        String top = strat.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");
        // 같은 pocket의 대조군(미진입 후보) net — "진입이 미진입보다 나았나".
        // 커버리지(=해당 horizon 가격 보유 대조군 / 전체 대조군)가 낮으면 편향 부분집합이라 net·edge를 내지 않는다.
        double cs = 0; int resolved = 0;
        for (TradeOutcome o : controlG) {
            Double net = netByOutcome.get(o.getId());
            if (net == null) continue;
            cs += net; resolved++;
        }
        double coverage = controlG.isEmpty() ? 0 : 100.0 * resolved / controlG.size();
        boolean trustworthy = resolved > 0 && coverage >= MIN_CONTROL_COVERAGE_PCT;
        Double controlNet = trustworthy ? round2(cs / resolved) : null;
        Double edge = controlNet == null ? null : round2(enteredNet - controlNet);
        return new Bucket(feature, range, n, days.size(), round2(maxShare),
                enteredNet, round2(100.0 * wins / n), clustered, top,
                resolved, controlNet, edge, controlG.size(), round2(coverage));
    }

    private double netPct(TradeOutcome o, long price) {
        double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
        return (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - (roundTripCostPct + slip);
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
