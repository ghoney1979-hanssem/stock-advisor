package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final String[] STRATEGIES = {"MEAN_REVERSION_C", "INDEX_RELATIVE_D", "VALUE_REVERSAL_J"};
    private static final int[] HOLD_DAYS = {1, 3, 5, 10, 15};
    private static final double[] TRAIL_PCT = {5, 8, 10, 12};
    private static final int[] MA_PERIOD = {5, 10};
    private static final double[] STOP_PCT = {8, 12};
    // 단일일 클러스터 가드 — ControlAnalysisService/StrategyPerformanceGate 와 같은 기준.
    private static final double MAX_DAY_SHARE_PCT = 80.0;
    private static final int MIN_DISTINCT_DAYS = 3;

    private final OutcomeDailyMarkRepository dailyMarkRepository;
    private final double roundTripPct;
    private final int maxHoldDays;
    private final int minSamples;

    public MultidayExitAnalysisService(OutcomeDailyMarkRepository dailyMarkRepository,
                                       @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripPct,
                                       @Value("${stockadvisor.trading.multiday-max-hold-days:15}") int maxHoldDays,
                                       @Value("${stockadvisor.trading.multiday-exit-min-samples:20}") int minSamples) {
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
    public record MethodResult(String method, double param, double avgNetPct, double winRatePct, int samples,
                               int distinctDays, Double maxDaySharePct, String topDay,
                               Double netExTopDayPct, boolean clustered) { }

    public record MultidayExitComparison(String strategy, int outcomes, int fullPaths,
                                         List<MethodResult> methods, String recommended,
                                         double recommendedNetPct) { }

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
        List<MultidayExitComparison> out = new ArrayList<>();
        for (String s : STRATEGIES) {
            out.add(compareStrategy(s, fullPathsOnly));
        }
        return out;
    }

    private MultidayExitComparison compareStrategy(String strategy, boolean fullPathsOnly) {
        Map<Long, String> entryDates = new LinkedHashMap<>();
        for (Object[] row : dailyMarkRepository.findEntryDatesByStrategy(strategy)) {
            if (row != null && row.length >= 2 && row[0] != null) {
                entryDates.put(((Number) row[0]).longValue(), (String) row[1]);
            }
        }
        List<Path> all = buildPaths(dailyMarkRepository.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(strategy), entryDates);
        int fullPaths = (int) all.stream().filter(Path::complete).count();
        List<Path> paths = fullPathsOnly ? all.stream().filter(Path::complete).toList() : all;

        List<MethodResult> methods = new ArrayList<>();
        for (int n : HOLD_DAYS) methods.add(agg("보유 D+" + n, n, paths, p -> holdToDay(p, n, roundTripPct)));
        for (double t : TRAIL_PCT) methods.add(agg("트레일 " + (int) t + "%", t, paths, p -> trailing(p, t, roundTripPct)));
        for (int p : MA_PERIOD) methods.add(agg("MA" + p + " 이탈", p, paths, path -> maExit(path, p, roundTripPct)));
        for (double s : STOP_PCT) methods.add(agg("손절 -" + (int) s + "%", s, paths, path -> stopExit(path, s, roundTripPct)));

        // 권장 = 표본 충분(≥minSamples) + 비클러스터 방식 중 평균 net 최대.
        // 클러스터 제외가 이 가드의 요점이다(2026-08-24 실측): D 완주 코호트 313건 중 139건(44%)이 20260731
        // 하루라 "보유 D+5 +3.53%"가 나왔는데, 그 하루를 빼면 −2.42%로 부호가 뒤집힌다(전 horizon 동일).
        // 건수 비중 44%는 문턱(80%)을 통과하므로 LOO 부호 반전 없이는 못 잡는다 — ControlAnalysisService가
        // 2026-08-21에 받은 것과 같은 가드이며 여기엔 전파되지 않았었다.
        MethodResult best = methods.stream()
                .filter(m -> m.samples() >= minSamples && !m.clustered())
                .max(Comparator.comparingDouble(MethodResult::avgNetPct))
                .orElse(null);

        return new MultidayExitComparison(strategy, all.size(), fullPaths, methods,
                best == null ? "표본부족·클러스터" : best.method(),
                best == null ? 0.0 : best.avgNetPct());
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

    private interface Sim { OptionalDouble netPct(Path p); }

    private MethodResult agg(String method, double param, List<Path> paths, Sim sim) {
        List<Double> nets = new ArrayList<>();
        Map<String, int[]> cntByDay = new LinkedHashMap<>();
        Map<String, double[]> sumByDay = new LinkedHashMap<>();
        for (Path p : paths) {
            OptionalDouble r = sim.netPct(p);
            if (r.isEmpty()) continue;               // 미해결(데이터 소진) → 표본 제외
            double net = r.getAsDouble();
            nets.add(net);
            if (p.entryDate() == null) continue;     // 진입일 미상 → 일자 집계에서만 빠짐(net 평균엔 포함)
            cntByDay.computeIfAbsent(p.entryDate(), k -> new int[1])[0]++;
            sumByDay.computeIfAbsent(p.entryDate(), k -> new double[1])[0] += net;
        }
        if (nets.isEmpty()) return new MethodResult(method, param, 0.0, 0.0, 0, 0, null, null, null, false);
        int n = nets.size();
        double avg = nets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long wins = nets.stream().filter(x -> x > 0).count();
        Cluster c = cluster(n, avg, cntByDay, sumByDay);
        return new MethodResult(method, param, round2(avg), round2(100.0 * wins / n), n,
                c.distinctDays(), round2n(c.maxDaySharePct()), c.topDay(), round2n(c.netExTopDayPct()), c.clustered());
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

    /** D+N일 종가에 청산. 해당 거래일 마크가 있어야 해결(없으면 제외). */
    static OptionalDouble holdToDay(Path p, int n, double cost) {
        for (int i = 0; i < p.days().length; i++) {
            if (p.days()[i] == n) return OptionalDouble.of(net(p.buy(), p.closes()[i], cost));
        }
        return OptionalDouble.empty();
    }

    /** 고점 종가 대비 trail% 되돌림 시 청산(D1부터 판정). 미발동+완주면 마지막 종가, 미완주면 제외. */
    static OptionalDouble trailing(Path p, double trailPct, double cost) {
        long peak = p.buy();
        for (int i = 0; i < p.days().length; i++) {
            long c = p.closes()[i];
            if (p.days()[i] >= 1 && c <= peak * (1 - trailPct / 100.0)) {
                return OptionalDouble.of(net(p.buy(), c, cost));
            }
            if (c > peak) peak = c;
        }
        return holdEndOrEmpty(p, cost);
    }

    /** 종가 < MA(p) 첫 시점 청산(p개 종가 확보 후 판정). 미발동+완주면 마지막 종가, 미완주면 제외. */
    static OptionalDouble maExit(Path path, int period, double cost) {
        long[] c = path.closes();
        for (int i = period - 1; i < c.length; i++) {
            long sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += c[j];
            double ma = (double) sum / period;
            if (c[i] < ma) return OptionalDouble.of(net(path.buy(), c[i], cost));
        }
        return holdEndOrEmpty(path, cost);
    }

    /** 종가 ≤ 매수×(1−stop%) 첫 시점 청산. 미발동+완주면 마지막 종가, 미완주면 제외. */
    static OptionalDouble stopExit(Path p, double stopPct, double cost) {
        for (int i = 0; i < p.days().length; i++) {
            if (p.closes()[i] <= p.buy() * (1 - stopPct / 100.0)) {
                return OptionalDouble.of(net(p.buy(), p.closes()[i], cost));
            }
        }
        return holdEndOrEmpty(p, cost);
    }

    /** 트리거 미발동 시: 완주(D+maxHold 도달)면 마지막 종가로 청산, 아니면 미해결(제외). */
    private static OptionalDouble holdEndOrEmpty(Path p, double cost) {
        if (!p.complete() || p.closes().length == 0) return OptionalDouble.empty();
        return OptionalDouble.of(net(p.buy(), p.closes()[p.closes().length - 1], cost));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static Double round2n(Double v) {
        return v == null ? null : round2(v);
    }
}
