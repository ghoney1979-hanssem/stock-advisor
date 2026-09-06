package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.repository.DailyPriceRepository;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 멀티데이(2-3주) 청산 트리거 시뮬레이션 (Phase 2, 2026-08-07).
 *
 * <p>{@link OutcomeDailyMark}에 수집/백필된 <b>일봉 종가 경로</b>(D0..D+maxHoldDays)에 대해
 * 청산 트리거를 시뮬레이션해 전략별 평균 net 수익이 최대인 방식을 고른다. 2-3주 홀드엔
 * 일봉 종가 granularity가 적절(장중 스파이크 무시 = 손절은 다소 낙관, 결과 해석 시 유의).</p>
 *
 * <p>net = (청산종가−매수)/매수×100 − 왕복비용(%). 슬리피지는 일봉 시뮬에선 생략(문서화).
 * 각 트리거는 "해결(resolved)"된 경로만 표본에 포함 — 경로가 트리거 없이 <b>완주(D+maxHoldDays 도달)</b>하면
 * 마지막 종가로 청산(=끝까지 보유), 데이터가 모자라 미완주면 제외(“데이터 소진”을 청산으로 오집계 방지).</p>
 */
@Service
public class MultidayExitAnalysisService {

    // 🐞 2026-09-03: 분석 대상이 {C,D,J} 하드코딩이라, 수집 대상을 12개로 넓히고 백필까지 돌렸는데도
    //    여전히 셋만 나왔다 — 2026-08-14 exit-hold/exit-method가 A/B/C만 반환하던 것과 <b>같은 유형</b>이다
    //    (수집은 늘었는데 가시화가 안 따라와, 데이터가 있는데도 "없는 것처럼" 보인다).
    //    → 대상을 <b>설정(multiday-strategies) ∪ 마크가 실제로 있는 전략</b>에서 도출한다. 설정에서 빠져도
    //      과거 마크가 있으면 계속 보이고, 새로 추가하면 백필 즉시 나타난다.
    private static final int[] HOLD_DAYS = {1, 3, 5, 10, 15};
    private static final double[] TRAIL_PCT = {5, 8, 10, 12};
    private static final int[] MA_PERIOD = {5, 10};
    private static final double[] STOP_PCT = {8, 12};
    // 단일일 클러스터 가드 — ControlAnalysisService/StrategyPerformanceGate 와 같은 기준.
    private static final double MAX_DAY_SHARE_PCT = 80.0;
    private static final int MIN_DISTINCT_DAYS = 3;

    /** (일자,k) 유니버스 평균이 이 종목 수 미만이면 벤치마크로 안 쓴다 — 몇 종목짜리 평균은 잡음이다. */
    private static final int MIN_UNIVERSE_STOCKS = 50;

    private final java.util.Set<String> configuredStrategies;   // multiday-strategies 설정
    private final OutcomeDailyMarkRepository dailyMarkRepository;

    /**
     * 유니버스 단순보유 벤치마크 소스. <b>필드 주입</b>인 이유는 기존 5인자 생성자를 쓰는 순수 시뮬 테스트를
     * 건드리지 않기 위해서다(미주입이면 벤치마크 미가용으로 degrade — 종전 동작).
     */
    @Autowired(required = false)
    private DailyPriceRepository dailyPriceRepository;

    /** 유니버스 진입일 유동성 필터 — 라이브 진입 판정({@code signal.min-price}·{@code min-turnover-krw})과 같은 값. */
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.min-price:1000}")
    private long universeMinPrice = 1000;

    @org.springframework.beans.factory.annotation.Value("${stockadvisor.cost.execution.min-turnover-krw:500000000}")
    private long universeMinTurnoverKrw = 500_000_000L;
    private final double roundTripPct;
    private final int maxHoldDays;
    private final int minSamples;

    public MultidayExitAnalysisService(OutcomeDailyMarkRepository dailyMarkRepository,
                                       @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripPct,
                                       @Value("${stockadvisor.trading.multiday-max-hold-days:15}") int maxHoldDays,
                                       @Value("${stockadvisor.trading.multiday-exit-min-samples:20}") int minSamples,
                                       @Value("${stockadvisor.trading.multiday-strategies:}") String multidayCsv) {
        this.configuredStrategies = PolicyGate.parseCsv(multidayCsv);
        this.dailyMarkRepository = dailyMarkRepository;
        this.roundTripPct = roundTripPct;
        this.maxHoldDays = maxHoldDays;
        this.minSamples = minSamples;
    }

    /**
     * 매수가 대비 일봉 종가 경로(거래일 오름차순). complete=D+maxHoldDays 도달(완주).
     *
     * @param entryDate 진입일(yyyyMMdd) — 단일일 클러스터 판정용. 미상이면 null(그 표본은 일자 집계에서만 빠진다).
     */
    public record Path(long buy, int[] days, long[] closes, boolean complete, String entryDate) {
        /** 진입일 없는 호환 생성자 — 순수 시뮬 코어 테스트는 일자가 필요 없다. */
        public Path(long buy, int[] days, long[] closes, boolean complete) {
            this(buy, days, closes, complete, null);
        }
    }

    /**
     * 방식별 시뮬 결과 + <b>단일일 클러스터 진단</b>.
     *
     * @param distinctDays   이 방식이 해결한 표본이 걸친 서로 다른 진입일 수(0=진입일 미상)
     * @param maxDaySharePct 단일 진입일이 차지하는 최대 건수 비중(%)
     * @param topDay         net 합 기여 절대값이 가장 큰 진입일(yyyyMMdd)
     * @param netExTopDayPct 그 하루를 뺀 나머지 net 평균(%) — 남는 표본이 없으면 null
     * @param clustered      건수 편중 <b>또는</b> LOO 부호 반전 → 이 수치는 하루가 만든 허수
     */
    /**
     * @param universeNetPct      같은 진입일·<b>같은 보유 거래일수</b>로 유니버스를 동일가중 보유했을 때의 net(%).
     *                            비용은 전략과 같은 왕복비용을 빼 초과수익에서 상쇄된다. 벤치마크 미가용이면 null.
     * @param excessVsUniversePct 전략 net − 유니버스 net(%p). <b>이게 주지표다</b> — 절대 net은 시장 드리프트를 잰다.
     * @param excessSamples       초과수익을 계산할 수 있었던 표본 수(진입일·k가 인덱스에 있는 경로만).
     * @param excessExTopDayPct   초과수익 기여 최대일을 뺀 나머지 초과수익(%p) — 단일일 허수 진단.
     * @param excessClustered     초과수익 기준 단일일 클러스터(권장에서 제외).
     */
    public record MethodResult(String method, double param, double avgNetPct, double winRatePct, int samples,
                               int distinctDays, Double maxDaySharePct, String topDay,
                               Double netExTopDayPct, boolean clustered,
                               Double universeNetPct, Double excessVsUniversePct, int excessSamples,
                               String excessTopDay, Double excessExTopDayPct, boolean excessClustered) { }

    /**
     * @param benchmarkAvailable    유니버스 벤치마크를 붙였는가. false면 {@code recommended} 는 <b>절대 net 기준</b>이라
     *                              시장 드리프트를 전략 성과로 오독할 수 있다(그 사실이 드러나도록 노출한다).
     * @param recommendedExcessPct  권장 방식의 초과수익(%p). 벤치마크 미가용이면 null.
     */
    public record MultidayExitComparison(String strategy, int outcomes, int fullPaths,
                                         List<MethodResult> methods, String recommended,
                                         double recommendedNetPct, boolean benchmarkAvailable,
                                         Double recommendedExcessPct) { }

    public List<MultidayExitComparison> compare() {
        return compare(false);
    }

    /**
     * @param fullPathsOnly 완전 경로(D+15까지 마크가 다 찬 표본)만으로 비교 — <b>고정 코호트</b>.
     *
     * <p><b>왜 필요한가</b>(2026-08-21): 기본 비교는 horizon마다 표본이 다르다 — D는 D+1 n=890 → D+5 n=775 →
     * D+10 n=533으로 줄어든다. 뒤쪽 horizon일수록 "그만큼 오래 전에 진입한 것"만 남으므로 <b>서로 다른 시장 국면의
     * 부분집합</b>을 비교하는 셈이고, "보유를 늘릴수록 좋아진다"는 결론이 코호트 교체의 산물일 수 있다
     * (실측: D가 D+10 +3.16%인데 D+15는 −2.26%로 급락 — 표본이 533→174로 바뀐다). 같은 표본으로 고정하면
     * 그 교란 없이 <b>보유기간만</b>의 효과를 본다. 대가는 표본 급감(D 898→174)이라 둘을 함께 볼 것.</p>
     */
    public List<MultidayExitComparison> compare(boolean fullPathsOnly) {
        List<String> targets = targetStrategies();
        UniverseHoldIndex universe = buildUniverse(targets);
        List<MultidayExitComparison> out = new ArrayList<>();
        for (String s : targets) {
            out.add(compareStrategy(s, fullPathsOnly, universe));
        }
        return out;
    }

    /**
     * 유니버스 단순보유 벤치마크를 <b>한 번만</b> 만들어 전 전략이 공유한다(전략마다 조회하면 같은 집계를 N번 돈다).
     *
     * <p>구간은 <b>실제 진입일의 최소~최대</b>로 잡는다. 일봉이 없거나 조회가 실패하면
     * {@link UniverseHoldIndex#unavailable()} 로 degrade하고, 그 사실이 {@code benchmarkAvailable=false} 로 응답에 실린다
     * (조용히 종전 동작으로 돌아가면 "반사실 없이 권장"이라는 원래 결함이 되살아난다).</p>
     */
    private UniverseHoldIndex buildUniverse(List<String> targets) {
        if (dailyPriceRepository == null) return UniverseHoldIndex.unavailable();
        String from = null, to = null;
        for (String st : targets) {
            for (Object[] row : safeEntryDates(st)) {
                if (row == null || row.length < 2 || row[1] == null) continue;
                String d = (String) row[1];
                if (from == null || d.compareTo(from) < 0) from = d;
                if (to == null || d.compareTo(to) > 0) to = d;
            }
        }
        if (from == null) return UniverseHoldIndex.unavailable();
        try {
            return UniverseHoldIndex.of(
                    dailyPriceRepository.universeForwardReturns(from, to, maxHoldDays, universeMinPrice, universeMinTurnoverKrw),
                    MIN_UNIVERSE_STOCKS);
        } catch (Exception e) {
            return UniverseHoldIndex.unavailable();
        }
    }

    private List<Object[]> safeEntryDates(String strategy) {
        try {
            List<Object[]> rows = dailyMarkRepository.findEntryDatesByStrategy(strategy);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 분석 대상 = 설정 ∪ 마크 보유 전략(정렬). 하드코딩 금지 — 수집이 늘면 분석도 따라와야 한다. */
    public List<String> targetStrategies() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(configuredStrategies);
        try {
            all.addAll(dailyMarkRepository.findDistinctStrategies());
        } catch (Exception ignored) {
            // 조회 실패 → 설정만으로 degrade
        }
        return new ArrayList<>(all);
    }

    private MultidayExitComparison compareStrategy(String strategy, boolean fullPathsOnly, UniverseHoldIndex universe) {
        Map<Long, String> entryDates = new LinkedHashMap<>();
        for (Object[] row : safeEntryDates(strategy)) {
            if (row != null && row.length >= 2 && row[0] != null) {
                entryDates.put(((Number) row[0]).longValue(), (String) row[1]);
            }
        }
        List<Path> all = buildPaths(dailyMarkRepository.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(strategy), entryDates);
        int fullPaths = (int) all.stream().filter(Path::complete).count();
        List<Path> paths = fullPathsOnly ? all.stream().filter(Path::complete).toList() : all;

        List<MethodResult> methods = new ArrayList<>();
        for (int n : HOLD_DAYS) methods.add(agg("보유 D+" + n, n, paths, universe, p -> holdToDayExit(p, n, roundTripPct)));
        for (double t : TRAIL_PCT) methods.add(agg("트레일 " + (int) t + "%", t, paths, universe, p -> trailingExit(p, t, roundTripPct)));
        for (int p : MA_PERIOD) methods.add(agg("MA" + p + " 이탈", p, paths, universe, path -> maExitAt(path, p, roundTripPct)));
        for (double st : STOP_PCT) methods.add(agg("손절 -" + (int) st + "%", st, paths, universe, path -> stopExitAt(path, st, roundTripPct)));

        // 권장 = 표본 충분(≥minSamples) + 비클러스터 방식 중에서 고른다.
        // 클러스터 제외가 첫 번째 가드다(2026-08-24 실측): D 완주 코호트 313건 중 139건(44%)이 20260731
        // 하루라 "보유 D+5 +3.53%"가 나왔는데, 그 하루를 빼면 −2.42%로 부호가 뒤집힌다(전 horizon 동일).
        // 건수 비중 44%는 문턱(80%)을 통과하므로 LOO 부호 반전 없이는 못 잡는다.
        //
        // 🔴 두 번째 가드가 2026-09-07에 추가된 유니버스 반사실이다. 위 클러스터 가드를 통과하고도
        //    "보유 D+15가 최고"라는 권장이 나왔는데, 그 정체는 완주 코호트의 진입일이 반등 구간(7/20~8/13)에
        //    몰려 만든 시장 드리프트였다(10전략 중 9개가 유니버스 단순보유에 졌다). 절대 net만 보는 한
        //    이 엔드포인트는 <b>지수에 지는 규칙</b>을 계속 권장한다 → 벤치마크가 있으면 초과수익>0 을 요구한다.
        List<MethodResult> eligible = methods.stream()
                .filter(m -> m.samples() >= minSamples && !m.clustered())
                .toList();
        boolean benchmarked = universe.available() && eligible.stream().anyMatch(m -> m.excessVsUniversePct() != null);
        MethodResult best;
        if (benchmarked) {
            best = eligible.stream()
                    .filter(m -> m.excessVsUniversePct() != null && m.excessVsUniversePct() > 0)
                    .filter(m -> m.excessSamples() >= minSamples && !m.excessClustered())
                    .max(Comparator.comparingDouble(MethodResult::excessVsUniversePct))
                    .orElse(null);
        } else {
            best = eligible.stream().max(Comparator.comparingDouble(MethodResult::avgNetPct)).orElse(null);
        }

        String label = best != null ? best.method()
                : benchmarked ? "유니버스 미달(초과수익>0 방식 없음)" : "표본부족·클러스터";
        return new MultidayExitComparison(strategy, all.size(), fullPaths, methods, label,
                best == null ? 0.0 : best.avgNetPct(), benchmarked,
                best == null ? null : best.excessVsUniversePct());
    }

    /** outcomeId별 일봉 마크를 (거래일 오름차순) 경로로 묶는다(진입일 미상). */
    List<Path> buildPaths(List<OutcomeDailyMark> marks) {
        return buildPaths(marks, Map.of());
    }

    /** outcomeId별 일봉 마크를 경로로 묶는다. entryDates는 클러스터 판정용(없으면 진입일 null). */
    List<Path> buildPaths(List<OutcomeDailyMark> marks, Map<Long, String> entryDates) {
        Map<Long, List<OutcomeDailyMark>> byOutcome = new LinkedHashMap<>();
        for (OutcomeDailyMark m : marks) {
            byOutcome.computeIfAbsent(m.getOutcomeId(), k -> new ArrayList<>()).add(m);
        }
        List<Path> paths = new ArrayList<>();
        for (List<OutcomeDailyMark> g : byOutcome.values()) {
            g.sort((a, b) -> Integer.compare(a.getMarkDays(), b.getMarkDays()));
            int[] days = new int[g.size()];
            long[] closes = new long[g.size()];
            boolean complete = false;
            for (int i = 0; i < g.size(); i++) {
                days[i] = g.get(i).getMarkDays();
                closes[i] = g.get(i).getClosePrice();
                if (days[i] >= maxHoldDays) complete = true;
            }
            paths.add(new Path(g.get(0).getBuyPrice(), days, closes, complete,
                    entryDates.get(g.get(0).getOutcomeId())));
        }
        return paths;
    }

    /** 시뮬 청산 결과 — <b>청산 거래일(D+day)</b>을 함께 돌려준다. 유니버스 벤치마크를 같은 보유기간으로 맞추려면 필요하다. */
    public record Exit(int day, double netPct) { }

    private interface Sim { Optional<Exit> exit(Path p); }

    private MethodResult agg(String method, double param, List<Path> paths, UniverseHoldIndex universe, Sim sim) {
        List<Double> nets = new ArrayList<>();
        Map<String, int[]> cntByDay = new LinkedHashMap<>();
        Map<String, double[]> sumByDay = new LinkedHashMap<>();
        // 초과수익은 별도 집계다 — 벤치마크가 없는 (진입일,k) 표본은 net에는 남고 초과수익에서만 빠진다.
        List<Double> excess = new ArrayList<>();
        List<Double> uni = new ArrayList<>();
        Map<String, int[]> exCntByDay = new LinkedHashMap<>();
        Map<String, double[]> exSumByDay = new LinkedHashMap<>();
        for (Path p : paths) {
            Optional<Exit> r = sim.exit(p);
            if (r.isEmpty()) continue;               // 미해결(데이터 소진) → 표본 제외
            Exit e = r.get();
            double net = e.netPct();
            nets.add(net);
            if (p.entryDate() == null) continue;     // 진입일 미상 → 일자 집계에서만 빠짐(net 평균엔 포함)
            cntByDay.computeIfAbsent(p.entryDate(), k -> new int[1])[0]++;
            sumByDay.computeIfAbsent(p.entryDate(), k -> new double[1])[0] += net;

            OptionalDouble u = universe.hold(p.entryDate(), e.day());
            if (u.isEmpty()) continue;
            // 유니버스에도 같은 왕복비용을 물린다 → 초과수익에서 비용이 상쇄돼 "선정이 값을 했나"만 남는다.
            double uNet = u.getAsDouble() - roundTripPct;
            double ex = net - uNet;
            uni.add(uNet);
            excess.add(ex);
            exCntByDay.computeIfAbsent(p.entryDate(), k -> new int[1])[0]++;
            exSumByDay.computeIfAbsent(p.entryDate(), k -> new double[1])[0] += ex;
        }
        if (nets.isEmpty()) {
            return new MethodResult(method, param, 0.0, 0.0, 0, 0, null, null, null, false,
                    null, null, 0, null, null, false);
        }
        int n = nets.size();
        double avg = nets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long wins = nets.stream().filter(x -> x > 0).count();
        Cluster c = cluster(n, avg, cntByDay, sumByDay);

        Double uniAvg = null, exAvg = null, exExTop = null;
        String exTop = null;
        boolean exClustered = false;
        if (!excess.isEmpty()) {
            uniAvg = uni.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            exAvg = excess.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Cluster ce = cluster(excess.size(), exAvg, exCntByDay, exSumByDay);
            exTop = ce.topDay();
            exExTop = ce.netExTopDayPct();
            exClustered = ce.clustered();
        }
        return new MethodResult(method, param, round2(avg), round2(100.0 * wins / n), n,
                c.distinctDays(), round2n(c.maxDaySharePct()), c.topDay(), round2n(c.netExTopDayPct()), c.clustered(),
                round2n(uniAvg), round2n(exAvg), excess.size(), exTop, round2n(exExTop), exClustered);
    }

    record Cluster(int distinctDays, Double maxDaySharePct, String topDay,
                   Double netExTopDayPct, boolean clustered) { }

    /**
     * 단일일 클러스터 판정(순수) — {@code ControlAnalysisService.toStat} 과 같은 규칙:
     * 건수 편중(비중&gt;80% 또는 거래일&lt;3) <b>또는</b> 최대기여일 제외(LOO) 시 net 부호 반전.
     *
     * <p>진입일이 하나도 안 붙은 표본(구 백필·조인 실패)은 일자 집계가 비어 판정을 생략한다(clustered=false).
     * 진단 부재를 "클러스터 아님"으로 오해하지 않도록 {@code distinctDays=0} 이 함께 노출된다.</p>
     */
    static Cluster cluster(int n, double net, Map<String, int[]> cntByDay, Map<String, double[]> sumByDay) {
        if (cntByDay.isEmpty()) return new Cluster(0, null, null, null, false);
        int days = cntByDay.size();
        double share = 100.0 * cntByDay.values().stream().mapToInt(c -> c[0]).max().orElse(0) / n;
        String topDay = sumByDay.entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue()[0])))
                .map(Map.Entry::getKey).orElse(null);
        Double netExTop = null;
        if (topDay != null && days > 1) {
            int restN = n - cntByDay.get(topDay)[0];
            if (restN > 0) netExTop = (net * n - sumByDay.get(topDay)[0]) / restN;
        }
        boolean clustered = share > MAX_DAY_SHARE_PCT
                || days < MIN_DISTINCT_DAYS
                || (netExTop != null && Math.signum(net) != Math.signum(netExTop));
        return new Cluster(days, share, topDay, netExTop, clustered);
    }

    // ── 순수 시뮬 코어 (단위테스트 대상) ──────────────────────────────

    private static double net(long buy, long exitClose, double cost) {
        return (double) (exitClose - buy) / buy * 100.0 - cost;
    }

    /**
     * 시뮬 코어는 <b>청산 거래일을 포함한</b> {@code Optional<Exit>} 를 돌려주고, 종전 {@code OptionalDouble}
     * 시그니처는 얇은 위임으로 남긴다 — 규칙을 복제하면 두 경로가 조용히 갈라진다(같은 이유로 P의 청산 판정도
     * 라이브 함수를 그대로 재사용한다).
     */
    private static OptionalDouble toDouble(Optional<Exit> e) {
        return e.map(x -> OptionalDouble.of(x.netPct())).orElseGet(OptionalDouble::empty);
    }

    /** D+N일 종가에 청산. 해당 거래일 마크가 있어야 해결(없으면 제외). */
    static Optional<Exit> holdToDayExit(Path p, int n, double cost) {
        for (int i = 0; i < p.days().length; i++) {
            if (p.days()[i] == n) return Optional.of(new Exit(n, net(p.buy(), p.closes()[i], cost)));
        }
        return Optional.empty();
    }

    static OptionalDouble holdToDay(Path p, int n, double cost) {
        return toDouble(holdToDayExit(p, n, cost));
    }

    /** 고점 종가 대비 trail% 되돌림 시 청산(D1부터 판정). 미발동+완주면 마지막 종가, 미완주면 제외. */
    static Optional<Exit> trailingExit(Path p, double trailPct, double cost) {
        long peak = p.buy();
        for (int i = 0; i < p.days().length; i++) {
            long c = p.closes()[i];
            if (p.days()[i] >= 1 && c <= peak * (1 - trailPct / 100.0)) {
                return Optional.of(new Exit(p.days()[i], net(p.buy(), c, cost)));
            }
            if (c > peak) peak = c;
        }
        return holdEndOrEmpty(p, cost);
    }

    static OptionalDouble trailing(Path p, double trailPct, double cost) {
        return toDouble(trailingExit(p, trailPct, cost));
    }

    /** 종가 < MA(p) 첫 시점 청산(p개 종가 확보 후 판정). 미발동+완주면 마지막 종가, 미완주면 제외. */
    static Optional<Exit> maExitAt(Path path, int period, double cost) {
        long[] c = path.closes();
        for (int i = period - 1; i < c.length; i++) {
            long sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += c[j];
            double ma = (double) sum / period;
            if (c[i] < ma) return Optional.of(new Exit(path.days()[i], net(path.buy(), c[i], cost)));
        }
        return holdEndOrEmpty(path, cost);
    }

    static OptionalDouble maExit(Path path, int period, double cost) {
        return toDouble(maExitAt(path, period, cost));
    }

    /** 종가 ≤ 매수×(1−stop%) 첫 시점 청산. 미발동+완주면 마지막 종가, 미완주면 제외. */
    static Optional<Exit> stopExitAt(Path p, double stopPct, double cost) {
        for (int i = 0; i < p.days().length; i++) {
            if (p.closes()[i] <= p.buy() * (1 - stopPct / 100.0)) {
                return Optional.of(new Exit(p.days()[i], net(p.buy(), p.closes()[i], cost)));
            }
        }
        return holdEndOrEmpty(p, cost);
    }

    static OptionalDouble stopExit(Path p, double stopPct, double cost) {
        return toDouble(stopExitAt(p, stopPct, cost));
    }

    /** 트리거 미발동 시: 완주(D+maxHold 도달)면 마지막 종가로 청산, 아니면 미해결(제외). */
    private static Optional<Exit> holdEndOrEmpty(Path p, double cost) {
        if (!p.complete() || p.closes().length == 0) return Optional.empty();
        int last = p.closes().length - 1;
        return Optional.of(new Exit(p.days()[last], net(p.buy(), p.closes()[last], cost)));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static Double round2n(Double v) {
        return v == null ? null : round2(v);
    }
}
