package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    /** 매수가 대비 일봉 종가 경로(거래일 오름차순). complete=D+maxHoldDays 도달(완주). */
    public record Path(long buy, int[] days, long[] closes, boolean complete) { }

    public record MethodResult(String method, double param, double avgNetPct, double winRatePct, int samples) { }

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
        List<Path> all = buildPaths(dailyMarkRepository.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(strategy));
        int fullPaths = (int) all.stream().filter(Path::complete).count();
        List<Path> paths = fullPathsOnly ? all.stream().filter(Path::complete).toList() : all;

        List<MethodResult> methods = new ArrayList<>();
        for (int n : HOLD_DAYS) methods.add(agg("보유 D+" + n, n, paths, p -> holdToDay(p, n, roundTripPct)));
        for (double t : TRAIL_PCT) methods.add(agg("트레일 " + (int) t + "%", t, paths, p -> trailing(p, t, roundTripPct)));
        for (int p : MA_PERIOD) methods.add(agg("MA" + p + " 이탈", p, paths, path -> maExit(path, p, roundTripPct)));
        for (double s : STOP_PCT) methods.add(agg("손절 -" + (int) s + "%", s, paths, path -> stopExit(path, s, roundTripPct)));

        // 권장 = 표본 충분(≥minSamples) 방식 중 평균 net 최대
        MethodResult best = methods.stream()
                .filter(m -> m.samples() >= minSamples)
                .max((a, b) -> Double.compare(a.avgNetPct(), b.avgNetPct()))
                .orElse(null);

        return new MultidayExitComparison(strategy, all.size(), fullPaths, methods,
                best == null ? "표본부족" : best.method(),
                best == null ? 0.0 : best.avgNetPct());
    }

    /** outcomeId별 일봉 마크를 (거래일 오름차순) 경로로 묶는다. */
    List<Path> buildPaths(List<OutcomeDailyMark> marks) {
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
            paths.add(new Path(g.get(0).getBuyPrice(), days, closes, complete));
        }
        return paths;
    }

    private interface Sim { OptionalDouble netPct(Path p); }

    private MethodResult agg(String method, double param, List<Path> paths, Sim sim) {
        List<Double> nets = new ArrayList<>();
        for (Path p : paths) {
            OptionalDouble r = sim.netPct(p);
            if (r.isPresent()) nets.add(r.getAsDouble());
        }
        if (nets.isEmpty()) return new MethodResult(method, param, 0.0, 0.0, 0);
        double avg = nets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long wins = nets.stream().filter(x -> x > 0).count();
        return new MethodResult(method, param, round2(avg), round2(100.0 * wins / nets.size()), nets.size());
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
}
