package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>종목 선정 축 탐색</b> — "무엇을 사야 하나"에 대한 후보를 한 번에 재고 걸러낸다.
 *
 * <p>지금까지 이 시스템이 시험한 선정 축은 사실상 <b>하나</b>였다(6개월 수익률). F-Score는 기각됐고,
 * 인트라데이 전략 12개는 전부 진입-대조군 edge가 음수였다. 즉 <b>검증된 선정 규칙이 없다.</b>
 * 여기서 사전 근거가 있는 축들을 같은 규칙으로 한 번에 잰다.</p>
 *
 * <p><b>다중검정 방어가 이 서비스 설계의 핵심이다</b> — 축 8개 × 방향 2개 = 16개를 돌리면
 * <b>하나는 우연히 통과한다</b>(2026-08-21 발굴 세션에서 필터 통과 pocket 50개가 전부 허수였다). 그래서:</p>
 * <ol>
 *   <li><b>탐색/확인 기간 분리</b> — {@code until}로 탐색 구간을 자르고, 고른 축만 {@code since}로 holdout에서
 *       <b>딱 한 번</b> 확인한다. 이 시스템이 지금까지 한 번도 안 쓴 방어책이다.</li>
 *   <li><b>내장 대조군</b> — 같은 축의 반대 방향이 자동으로 짝이 된다. 방향이 갈리지 않으면 그 축엔 신호가 없다.</li>
 *   <li><b>연도별 부호·LOO</b> — 클러스터 함정(이 시스템에서 6회 재발)을 축마다 판정한다.</li>
 * </ol>
 *
 * <p>⚠️ 청산은 <b>단순보유(1개월)</b>가 기본이다 — 트레일링이 −0.39%p로 손해라는 게 확인됐으므로
 * 선정 축을 재는 실험에 그 잡음을 섞으면 안 된다.</p>
 *
 * <p>⚠️ F-Score 필터는 기본 <b>해제</b>(scoreMin=0) — 이미 기각된 축을 필터로 남기면 선정 축 실험이 오염된다.</p>
 */
@Service
public class SelectionSweepService {

    private static final Logger log = LoggerFactory.getLogger(SelectionSweepService.class);

    private final JdbcTemplate jdbcTemplate;
    private final double roundTripCostPct;

    public SelectionSweepService(JdbcTemplate jdbcTemplate,
                                 @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripCostPct) {
        this.jdbcTemplate = jdbcTemplate;
        this.roundTripCostPct = roundTripCostPct;
    }

    private record Series(int[] dates, int[] high, int[] close, long[] volume) {}

    /**
     * @param excessPct        유니버스 동일가중 단순보유 대비 초과수익(%p/월). <b>주지표</b>
     * @param excessExTopYear  최대기여연도 제외 초과 — 클러스터 판정
     * @param vsOppositePct    같은 축 <b>반대 방향</b> 대비 우위(%p). 이게 0 근처면 그 축엔 신호가 없다
     */
    public record AxisResult(String axis, String direction, int cohorts,
                             double portfolioNetPct, double universeNetPct, double excessPct,
                             int yearsPositive, int yearsTotal, String topYear, Double excessExTopYear,
                             double worstCohortPct, Double vsOppositePct, boolean pass, List<String> fails) {}

    public record SweepReport(String window, int topN, int maxHoldMonths, double roundTripCostPct,
                              int stocksLoaded, int cohorts, String protocolNote,
                              List<AxisResult> results, List<String> caveats) {}

    /**
     * @param since/until 코호트 진입일 구간(yyyyMMdd). <b>탐색은 until로 자르고 holdout은 since로</b> 연다.
     * @param topN        상위 N종목 동일가중(기본 30)
     */
    public SweepReport sweep(String since, String until, Integer topN, Integer maxHoldMonths) {
        return sweep(since, until, topN, maxHoldMonths, null, null);
    }

    /**
     * @param minPriceKrw    진입일 종가 하한(원). 0/null=제한 없음
     * @param minTurnoverKrw 진입 직전 1개월 <b>일평균 거래대금</b> 하한(원). 0/null=제한 없음
     *
     * <p>⚠️ <b>이 두 필터가 없으면 결과가 거래 불가능한 종목으로 채워진다.</b> "많이 떨어진 30종목"을
     * 3,320종목 전체에서 뽑으면 대부분 <b>동전주·거래 거의 없는 종목</b>이 되고, 종가 기반 수익률은
     * <b>호가 단위 양자화와 호가 스프레드 튐(bid-ask bounce)</b>이 지배한다. 그건 단기 반전 신호로 위장하지만
     * 실제로는 체결이 불가능하거나 스프레드(수 %)가 수익을 통째로 먹는다 —
     * 백테스트는 왕복비용 0.22%만 빼므로 그 비용이 반영되지 않는다.</p>
     *
     * <p>⚠️ 라이브 시스템은 이미 같은 방어를 갖고 있다({@code min-price} 1,000원 ·
     * {@code min-turnover-krw} 5억). <b>백테스트가 그걸 안 쓰면 실제로 못 하는 매매를 측정하는 것</b>이다.</p>
     */
    public SweepReport sweep(String since, String until, Integer topN, Integer maxHoldMonths,
                             Long minPriceKrw, Long minTurnoverKrw) {
        int n = (topN == null || topN <= 0) ? 30 : topN;
        int hold = (maxHoldMonths == null || maxHoldMonths <= 0) ? 1 : maxHoldMonths;
        long minPrice = minPriceKrw == null ? 0 : minPriceKrw;
        long minTurnover = minTurnoverKrw == null ? 0 : minTurnoverKrw;

        Map<String, Series> prices = loadSeries();
        int[] calendar = tradingCalendar(prices);

        // 코호트별로 전 종목의 모든 축 값을 한 번만 계산해 두고, 축×방향마다 정렬만 다시 한다.
        record Pick(String code, double fwdNetPct, Double[] axisValues) {}
        record CohortData(String date, String year, List<Pick> picks, double universeNetPct) {}

        SelectionAxis[] axes = SelectionAxis.values();
        List<CohortData> cohorts = new ArrayList<>();

        LocalDate first = toDate(calendar[0]).withDayOfMonth(1).plusMonths(13);   // 12개월 축 확보
        LocalDate last = toDate(calendar[calendar.length - 1]);

        for (LocalDate m = first; !m.plusMonths(hold).isAfter(last); m = m.plusMonths(1)) {
            int entryDate = onOrAfter(calendar, ymd(m));
            int limitDate = onOrBefore(calendar, ymd(m.plusMonths(hold)));
            if (entryDate < 0 || limitDate < 0) continue;
            String ds = String.valueOf(entryDate);
            if (since != null && ds.compareTo(since) < 0) continue;
            if (until != null && ds.compareTo(until) > 0) continue;

            List<Pick> picks = new ArrayList<>();
            double uniSum = 0;
            int uniN = 0;
            for (Map.Entry<String, Series> e : prices.entrySet()) {
                Series s = e.getValue();
                int ei = idxOnOrAfter(s.dates(), entryDate);
                int li = idxOnOrBefore(s.dates(), limitDate);
                if (ei < 0 || li <= ei || s.close()[ei] <= 0) continue;
                if (minPrice > 0 && s.close()[ei] < minPrice) continue;

                int i1 = idxOnOrAfter(s.dates(), ymd(m.minusMonths(1)));
                if (minTurnover > 0) {
                    // 진입 직전 1개월 일평균 거래대금 — 라이브의 유동성 필터와 같은 기준.
                    Double turnover = SelectionAxis.TURNOVER.value(s.close(), s.high(), s.volume(), ei, i1, i1, i1, i1);
                    if (turnover == null || turnover < minTurnover) continue;
                }

                double fwd = (double) (s.close()[li] - s.close()[ei]) / s.close()[ei] * 100 - roundTripCostPct;
                uniSum += fwd;
                uniN++;

                int i3 = idxOnOrAfter(s.dates(), ymd(m.minusMonths(3)));
                int i6 = idxOnOrAfter(s.dates(), ymd(m.minusMonths(6)));
                int i12 = idxOnOrAfter(s.dates(), ymd(m.minusMonths(12)));
                Double[] vals = new Double[axes.length];
                for (int a = 0; a < axes.length; a++) {
                    vals[a] = axes[a].value(s.close(), s.high(), s.volume(), ei, i1, i3, i6, i12);
                }
                picks.add(new Pick(e.getKey(), fwd, vals));
            }
            if (uniN == 0 || picks.isEmpty()) continue;
            cohorts.add(new CohortData(ds, ds.substring(0, 4), picks, uniSum / uniN));
        }

        // 축×방향별 집계
        Map<String, double[]> excessByKey = new HashMap<>();   // 나중에 반대 방향 비교용
        List<AxisResult> results = new ArrayList<>();
        for (int a = 0; a < axes.length; a++) {
            for (String dir : new String[]{"LOW", "HIGH"}) {
                final int ai = a;
                List<double[]> perCohort = new ArrayList<>();   // {excess, portfolio, universe}
                List<String> years = new ArrayList<>();
                for (CohortData c : cohorts) {
                    List<Pick> eligible = c.picks().stream().filter(p -> p.axisValues()[ai] != null).toList();
                    if (eligible.size() < n) continue;
                    Comparator<Pick> cmp = Comparator.comparingDouble(p -> p.axisValues()[ai]);
                    List<Pick> sorted = new ArrayList<>(eligible);
                    sorted.sort("LOW".equals(dir) ? cmp : cmp.reversed());
                    List<Pick> picked = sorted.subList(0, n);
                    double port = picked.stream().mapToDouble(Pick::fwdNetPct).average().orElse(0);
                    perCohort.add(new double[]{port - c.universeNetPct(), port, c.universeNetPct()});
                    years.add(c.year());
                }
                results.add(summarize(axes[a].name(), dir, perCohort, years, excessByKey));
            }
        }
        // 반대 방향 대비 우위 채우기
        List<AxisResult> withOpposite = new ArrayList<>();
        for (AxisResult r : results) {
            double[] opp = excessByKey.get(r.axis() + ":" + ("LOW".equals(r.direction()) ? "HIGH" : "LOW"));
            Double vs = opp == null ? null : round(r.excessPct() - opp[0]);
            List<String> fails = new ArrayList<>(r.fails());
            if (vs != null && vs <= 0) fails.add("반대 방향 대비 우위 없음");
            withOpposite.add(new AxisResult(r.axis(), r.direction(), r.cohorts(), r.portfolioNetPct(),
                    r.universeNetPct(), r.excessPct(), r.yearsPositive(), r.yearsTotal(), r.topYear(),
                    r.excessExTopYear(), r.worstCohortPct(), vs, fails.isEmpty(), fails));
        }
        withOpposite.sort((x, y) -> Double.compare(y.excessPct(), x.excessPct()));

        String window = (since == null ? "처음" : since) + "~" + (until == null ? "끝" : until);
        log.info("[선정축탐색] {} · 코호트 {} · 통과 {}/{}", window, cohorts.size(),
                withOpposite.stream().filter(AxisResult::pass).count(), withOpposite.size());

        return new SweepReport(window, n, hold, roundTripCostPct, prices.size(), cohorts.size(),
                "탐색 구간에서 통과한 축만 holdout(since)에서 딱 한 번 확인할 것. "
                        + "16개를 돌리면 하나는 우연히 통과한다 — 탐색 결과 자체를 채택 근거로 쓰지 말 것.",
                withOpposite, caveats());
    }

    private static AxisResult summarize(String axis, String dir, List<double[]> perCohort,
                                        List<String> years, Map<String, double[]> excessByKey) {
        if (perCohort.isEmpty()) {
            excessByKey.put(axis + ":" + dir, new double[]{0});
            return new AxisResult(axis, dir, 0, 0, 0, 0, 0, 0, null, null, 0, null, false, List.of("표본 없음"));
        }
        double excess = perCohort.stream().mapToDouble(v -> v[0]).average().orElse(0);
        excessByKey.put(axis + ":" + dir, new double[]{excess});

        Map<String, List<Double>> byYear = new TreeMap<>();
        for (int i = 0; i < perCohort.size(); i++) {
            byYear.computeIfAbsent(years.get(i), k -> new ArrayList<>()).add(perCohort.get(i)[0]);
        }
        int positive = 0;
        String topYear = null;
        double topContribution = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, List<Double>> e : byYear.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avg > 0) positive++;
            double contribution = avg * e.getValue().size();
            if (contribution > topContribution) {
                topContribution = contribution;
                topYear = e.getKey();
            }
        }
        final String tY = topYear;
        List<Double> exTop = new ArrayList<>();
        for (int i = 0; i < perCohort.size(); i++) {
            if (!years.get(i).equals(tY)) exTop.add(perCohort.get(i)[0]);
        }
        Double exTopExcess = exTop.isEmpty() ? null
                : round(exTop.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        List<String> fails = new ArrayList<>();
        if (perCohort.size() < 40) fails.add("코호트 부족(" + perCohort.size() + " < 40)");
        if (excess <= 0) fails.add(String.format("초과수익 없음(%.2f%%p)", excess));
        int need = (int) Math.ceil(byYear.size() * 0.7);
        if (positive < need) fails.add(String.format("연도부호 %d/%d < %d", positive, byYear.size(), need));
        if (exTopExcess == null || exTopExcess <= 0) {
            fails.add(String.format("최대기여연도(%s) 제외 시 소멸(%s)", tY, exTopExcess));
        }

        return new AxisResult(axis, dir, perCohort.size(),
                round(perCohort.stream().mapToDouble(v -> v[1]).average().orElse(0)),
                round(perCohort.stream().mapToDouble(v -> v[2]).average().orElse(0)),
                round(excess), positive, byYear.size(), topYear, exTopExcess,
                round(perCohort.stream().mapToDouble(v -> v[1]).min().orElse(0)),
                null, false, fails);
    }

    private static List<String> caveats() {
        return List.of(
                "생존편향 미해소 — 유니버스가 '오늘 기준 시총 상위 1,500'이라 절대 net은 낙관 상한. "
                        + "유니버스 벤치마크에도 같은 편향이 걸리므로 excess(차이)만 볼 것.",
                "축 8개 × 방향 2개 = 16개를 한 번에 돌린다. 우연 통과가 반드시 섞이므로 "
                        + "탐색 구간 결과는 '후보 선별'까지만 쓰고, 채택은 holdout 확인 후에 할 것.",
                "청산은 단순보유(상한 개월) — 트레일링은 별도 실험에서 -0.39%p로 손해가 확인됐다.",
                "월별 코호트는 같은 시장 국면을 공유하므로 완전히 독립이 아니다(연도부호로 보정 판정).",
                "배당 미반영(수정주가) — 고배당 종목이 구조적으로 불리하게 평가된다.");
    }

    // ── 로딩·유틸 ───────────────────────────────────────

    private Map<String, Series> loadSeries() {
        Map<String, Series> out = new HashMap<>();
        int[] dates = new int[4096], high = new int[4096], close = new int[4096];
        long[] vol = new long[4096];
        int[] size = {0};
        String[] cur = {null};
        int[][] refs = {dates, high, close};
        long[][] vrefs = {vol};
        jdbcTemplate.query(
                "select stock_code, business_date, high_price, close_price, volume "
                        + "from daily_price order by stock_code, business_date",
                rs -> {
                    String code = rs.getString(1);
                    if (cur[0] != null && !cur[0].equals(code)) {
                        out.put(cur[0], new Series(Arrays.copyOf(refs[0], size[0]), Arrays.copyOf(refs[1], size[0]),
                                Arrays.copyOf(refs[2], size[0]), Arrays.copyOf(vrefs[0], size[0])));
                        size[0] = 0;
                    }
                    cur[0] = code;
                    if (size[0] == refs[0].length) {
                        for (int i = 0; i < 3; i++) refs[i] = Arrays.copyOf(refs[i], refs[i].length * 2);
                        vrefs[0] = Arrays.copyOf(vrefs[0], vrefs[0].length * 2);
                    }
                    int k = size[0]++;
                    refs[0][k] = Integer.parseInt(rs.getString(2));
                    refs[1][k] = (int) rs.getLong(3);
                    refs[2][k] = (int) rs.getLong(4);
                    vrefs[0][k] = rs.getLong(5);
                });
        if (cur[0] != null && size[0] > 0) {
            out.put(cur[0], new Series(Arrays.copyOf(refs[0], size[0]), Arrays.copyOf(refs[1], size[0]),
                    Arrays.copyOf(refs[2], size[0]), Arrays.copyOf(vrefs[0], size[0])));
        }
        log.info("[선정축탐색] 일봉 로드 {}종목", out.size());
        return out;
    }

    private static int[] tradingCalendar(Map<String, Series> prices) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Series s : prices.values()) for (int d : s.dates()) set.add(d);
        int[] out = new int[set.size()];
        int i = 0;
        for (int d : set) out[i++] = d;
        return out;
    }

    private static int ymd(LocalDate d) {
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private static LocalDate toDate(int ymd) {
        return LocalDate.of(ymd / 10000, ymd / 100 % 100, ymd % 100);
    }

    private static int onOrAfter(int[] s, int v) {
        int i = idxOnOrAfter(s, v);
        return i < 0 ? -1 : s[i];
    }

    private static int onOrBefore(int[] s, int v) {
        int i = idxOnOrBefore(s, v);
        return i < 0 ? -1 : s[i];
    }

    static int idxOnOrAfter(int[] s, int v) {
        int lo = 0, hi = s.length - 1, f = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] >= v) { f = mid; hi = mid - 1; } else { lo = mid + 1; }
        }
        return f;
    }

    static int idxOnOrBefore(int[] s, int v) {
        int lo = 0, hi = s.length - 1, f = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= v) { f = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return f;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
