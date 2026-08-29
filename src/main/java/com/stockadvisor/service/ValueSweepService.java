package com.stockadvisor.service;


import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.FinancialFact;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.FinancialFactRepository;
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
 * 가치 축 선정 백테스트(2026-08-29) — <b>저PBR·고이익수익률</b>이 유니버스 동일가중을 이기는가.
 *
 * <p><b>왜 지금인가</b>: 가격·거래량 축 8개(2026-08-28 sweep)는 HIGH_52W 하나만 살았고, F-Score(퀄리티)는
 * 스프레드가 음수였다. 그런데 Piotroski 원 논문은 F-Score를 <b>저PBR 상위 20% 안에서만</b> 썼다 —
 * 즉 가치 필터 없이 퀄리티를 기각한 셈이라 검증 순서가 거꾸로였다. 한국 시장에서 역사적으로 가장 강했던
 * 프리미엄(저PBR)이 이 시스템에선 아직 한 번도 측정되지 않았다.</p>
 *
 * <p><b>과거 시총 복원</b>: 주식수ᵧ = 현 상장주식수 × (자본금ᵧ ÷ 최신 자본금), 미상이면 자본금÷액면가. 일봉이
 * 수정주가라 액면분할과 정합하고 증자·감자는 자본금에 반영된다. 시총 = 진입일 종가 × 주식수ᵧ.</p>
 *
 * <p><b>look-ahead</b>: 사업연도 Y 재무는 (Y+1)년 5월~(Y+2)년 4월에만 유효({@link MultidayBacktestService#validBusinessYear}).
 * <b>표본 자격</b>: 라이브와 같은 저가주(1,000원)·거래대금(5억) 필터. 진입은 매월 첫 거래일 종가, 단순보유 후 종가 청산.</p>
 *
 * <p><b>축 4개 × 방향 2</b>: PBR(LOW=가치) · EP(이익수익률 = 순이익/시총, HIGH=가치; 적자는 음수라 자연히 하위) ·
 * PBR_Q / EP_Q(F-Score ≥ scoreMin 안에서만 — Piotroski 구조). 반대 방향이 내장 대조군.
 * 판정은 {@code SelectionSweepService}와 같은 사전 등록 기준 + <b>반대방향 마진 ≥0.3%p</b>(8/28 교훈: 마진 0으로는
 * 방향이 안 갈리는 축이 통과했다).</p>
 */
@Service
public class ValueSweepService {

    private static final Logger log = LoggerFactory.getLogger(ValueSweepService.class);
    private static final double OPPOSITE_MARGIN_PCT = 0.3;

    private final JdbcTemplate jdbcTemplate;
    private final FinancialFactRepository factRepository;
    private final CompanyRepository companyRepository;
    private final double roundTripCostPct;

    public ValueSweepService(JdbcTemplate jdbcTemplate, FinancialFactRepository factRepository,
                             CompanyRepository companyRepository,
                             @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripCostPct) {
        this.jdbcTemplate = jdbcTemplate;
        this.factRepository = factRepository;
        this.companyRepository = companyRepository;
        this.roundTripCostPct = roundTripCostPct;
    }

    private record Series(int[] dates, int[] close, long[] volume) {}
    /** 사업연도별 재무 스냅샷 — 주식수는 reconstructShares 로 복원. */
    record Fund(long shares, long equity, long netIncome, int fscore, int evaluated) {}

    /**
     * @param universeNetPct  <b>커버드 유니버스</b>(그 축 값이 있는 종목 전체) 동일가중 net — 재무가 있는 종목은 현 워치리스트
     *                        생존자라 전 유니버스보다 구조적으로 유리하다. 같은 커버리지 안에서 비교해야 "가치"만 남는다.
     * @param fullUniverseNetPct 참고용 전 유니버스(폐지 포함) net.
     * @param quintileExcess  q1(값 최저)~q5(최고) 5분위별 커버드 대비 초과 — 단조성 확인용(양끝만 좋으면 노이즈).
     */
    public record AxisResult(String axis, String direction, int cohorts, double portfolioNetPct, double universeNetPct,
                             double fullUniverseNetPct, Map<String, Double> quintileExcess, double excessPct, int yearsPositive, int yearsTotal, String topYear, Double excessExTopYear,
                             double worstCohortPct, Double vsOppositePct, boolean pass, List<String> fails,
                             Map<String, Double> excessByYear) {}
    public record Diag(String year, int universe, int withPbr, double medianPbr, double medianEpPct) {}
    public record Report(String window, int topN, int maxHoldMonths, int scoreMin, double roundTripCostPct,
                         int stocks, int stocksWithFace, int cohorts, List<AxisResult> results,
                         List<Diag> diagnostics, List<String> caveats) {}

    public Report sweep(String since, String until, Integer topN, Integer maxHoldMonths, Integer scoreMin,
                        Integer minEvaluated, Long minPriceKrw, Long minTurnoverKrw) {
        int n = topN == null || topN <= 0 ? 30 : topN;
        int hold = maxHoldMonths == null || maxHoldMonths <= 0 ? 1 : maxHoldMonths;
        int sMin = scoreMin == null ? 6 : scoreMin;
        int minEval = minEvaluated == null ? 5 : minEvaluated;
        long minPrice = minPriceKrw == null ? 1000 : minPriceKrw;
        long minTurnover = minTurnoverKrw == null ? 500_000_000L : minTurnoverKrw;

        Map<String, Series> prices = loadSeries();
        int[] calendar = tradingCalendar(prices);
        Map<String, Long> face = new HashMap<>(), listed = new HashMap<>();
        for (Company c : companyRepository.findAll()) {
            if (c.getFaceValue() != null && c.getFaceValue() > 0) face.put(c.getStockCode(), c.getFaceValue());
            if (c.getListedShares() != null && c.getListedShares() > 0) listed.put(c.getStockCode(), c.getListedShares());
        }
        // 종목별 최신 사업연도 자본금 — 주식수ᵧ = 현 상장주식수 × (자본금ᵧ / 최신 자본금). 자본금÷액면가만 쓰면
        // 이익소각(자본금 불변·주식수 감소)과 우선주 자본금이 시총을 부풀린다(실측 삼성전자 8.98B vs 상장 5.85B,
        // 현대차 298M vs 205M). 현 상장주식수를 기준점으로 삼고 자본금 변화로만 과거를 스케일하면 둘 다 피한다.
        List<FinancialFact> facts = factRepository.findAll();
        Map<String, Long> latestCapital = new HashMap<>();
        Map<String, Integer> latestYear = new HashMap<>();
        for (FinancialFact f : facts) {
            try {
                int y = Integer.parseInt(f.getBusinessYear());
                if (f.getCapitalStock() > 0 && y > latestYear.getOrDefault(f.getStockCode(), -1)) {
                    latestYear.put(f.getStockCode(), y);
                    latestCapital.put(f.getStockCode(), f.getCapitalStock());
                }
            } catch (NumberFormatException ignored) { /* 비정상 연도 행 */ }
        }
        Map<String, Map<Integer, Fund>> funds = new HashMap<>();
        for (FinancialFact f : facts) {
            Long shares = reconstructShares(listed.get(f.getStockCode()), latestCapital.get(f.getStockCode()),
                    f.getCapitalStock(), face.get(f.getStockCode()));
            if (shares == null) continue;
            FinancialScore.Result r = FinancialScore.of(f);
            try {
                funds.computeIfAbsent(f.getStockCode(), k -> new HashMap<>())
                        .put(Integer.parseInt(f.getBusinessYear()),
                                new Fund(shares, f.getTotalEquity(), f.getNetIncome(), r.score(), r.evaluated()));
            } catch (NumberFormatException ignored) { /* 비정상 연도 행 */ }
        }

        String[] axes = {"PBR", "EP", "PBR_Q", "EP_Q"};
        record Pick(String code, double fwd, Double[] v) {}
        record Cohort(String date, String year, List<Pick> picks, double uni, Diag diag) {}
        List<Cohort> cohorts = new ArrayList<>();
        Map<String, List<Double>> pbrByYear = new TreeMap<>(), epByYear = new TreeMap<>();
        Map<String, int[]> covByYear = new TreeMap<>();

        LocalDate first = toDate(calendar[0]).withDayOfMonth(1).plusMonths(1);
        LocalDate last = toDate(calendar[calendar.length - 1]);
        for (LocalDate m = first; !m.plusMonths(hold).isAfter(last); m = m.plusMonths(1)) {
            int entryDate = onOrAfter(calendar, ymd(m));
            int limitDate = onOrBefore(calendar, ymd(m.plusMonths(hold)));
            if (entryDate < 0 || limitDate < 0) continue;
            String ds = String.valueOf(entryDate);
            if (since != null && !since.isBlank() && ds.compareTo(since) < 0) continue;
            if (until != null && !until.isBlank() && ds.compareTo(until) > 0) continue;
            Integer fy = MultidayBacktestService.validBusinessYear(m);

            List<Pick> picks = new ArrayList<>();
            double uniSum = 0;
            int uniN = 0, withPbr = 0;
            for (Map.Entry<String, Series> e : prices.entrySet()) {
                Series s = e.getValue();
                int ei = idx(s.dates(), entryDate, true), li = idx(s.dates(), limitDate, false);
                if (ei < 0 || li <= ei || s.close()[ei] <= 0 || s.close()[ei] < minPrice) continue;
                if (minTurnover > 0 && avgTurnover(s, ei, 20) < minTurnover) continue;
                double fwd = (double) (s.close()[li] - s.close()[ei]) / s.close()[ei] * 100 - roundTripCostPct;
                uniSum += fwd;
                uniN++;
                Map<Integer, Fund> byYear = funds.get(e.getKey());
                Fund f = byYear == null ? null : byYear.get(fy);
                Double[] v = new Double[axes.length];
                if (f != null && f.evaluated() >= minEval) {
                    double mcap = (double) s.close()[ei] * f.shares();
                    Double pbr = valuation(mcap, f.equity(), true);
                    Double ep = valuation(mcap, f.netIncome(), false);
                    v[0] = pbr;
                    v[1] = ep;
                    boolean quality = f.fscore() >= sMin;
                    v[2] = quality ? pbr : null;
                    v[3] = quality ? ep : null;
                    if (pbr != null) {
                        withPbr++;
                        pbrByYear.computeIfAbsent(ds.substring(0, 4), k -> new ArrayList<>()).add(pbr);
                    }
                    if (ep != null) epByYear.computeIfAbsent(ds.substring(0, 4), k -> new ArrayList<>()).add(ep);
                }
                picks.add(new Pick(e.getKey(), fwd, v));
            }
            if (uniN == 0) continue;
            covByYear.computeIfAbsent(ds.substring(0, 4), k -> new int[2]);
            covByYear.get(ds.substring(0, 4))[0] += uniN;
            covByYear.get(ds.substring(0, 4))[1] += withPbr;
            cohorts.add(new Cohort(ds, ds.substring(0, 4), picks, uniSum / uniN, null));
        }

        Map<String, Double> excessByKey = new HashMap<>();
        List<AxisResult> raw = new ArrayList<>();
        for (int a = 0; a < axes.length; a++) {
            for (String dir : new String[]{"LOW", "HIGH"}) {
                final int ai = a;
                List<double[]> per = new ArrayList<>();
                List<String> years = new ArrayList<>();
                for (Cohort c : cohorts) {
                    List<Pick> el = c.picks().stream().filter(p -> p.v()[ai] != null).toList();
                    if (el.size() < n) continue;
                    Comparator<Pick> cmp = Comparator.comparingDouble(p -> p.v()[ai]);
                    List<Pick> sorted = new ArrayList<>(el);
                    sorted.sort("LOW".equals(dir) ? cmp : cmp.reversed());
                    double port = sorted.subList(0, n).stream().mapToDouble(Pick::fwd).average().orElse(0);
                    double cov = el.stream().mapToDouble(Pick::fwd).average().orElse(0);   // 커버드 유니버스(같은 커버리지)
                    double[] row = new double[9];
                    row[0] = port - cov; row[1] = port; row[2] = cov; row[3] = c.uni();
                    List<Pick> asc = new ArrayList<>(el);
                    asc.sort(Comparator.comparingDouble(p -> p.v()[ai]));
                    for (int q = 0; q < 5; q++) {
                        int from = asc.size() * q / 5, to = asc.size() * (q + 1) / 5;
                        row[4 + q] = asc.subList(from, to).stream().mapToDouble(Pick::fwd).average().orElse(0) - cov;
                    }
                    per.add(row);
                    years.add(c.year());
                }
                raw.add(summarize(axes[a], dir, per, years, excessByKey));
            }
        }
        List<AxisResult> results = new ArrayList<>();
        for (AxisResult r : raw) {
            Double opp = excessByKey.get(r.axis() + ":" + ("LOW".equals(r.direction()) ? "HIGH" : "LOW"));
            Double vs = opp == null ? null : round(r.excessPct() - opp);
            List<String> fails = new ArrayList<>(r.fails());
            if (vs != null && vs < OPPOSITE_MARGIN_PCT) fails.add("반대 방향 대비 마진 부족(" + vs + " < " + OPPOSITE_MARGIN_PCT + ")");
            results.add(new AxisResult(r.axis(), r.direction(), r.cohorts(), r.portfolioNetPct(), r.universeNetPct(),
                    r.fullUniverseNetPct(), r.quintileExcess(), r.excessPct(), r.yearsPositive(), r.yearsTotal(), r.topYear(), r.excessExTopYear(),
                    r.worstCohortPct(), vs, fails.isEmpty(), fails, r.excessByYear()));
        }
        results.sort((x, y) -> Double.compare(y.excessPct(), x.excessPct()));

        List<Diag> diags = new ArrayList<>();
        for (Map.Entry<String, int[]> e : covByYear.entrySet()) {
            diags.add(new Diag(e.getKey(), e.getValue()[0], e.getValue()[1],
                    round(median(pbrByYear.getOrDefault(e.getKey(), List.of()))),
                    round(median(epByYear.getOrDefault(e.getKey(), List.of())))));
        }
        String window = (since == null ? "처음" : since) + "~" + (until == null ? "끝" : until);
        log.info("[가치축탐색] {} · 코호트 {} · 통과 {}/{}", window, cohorts.size(),
                results.stream().filter(AxisResult::pass).count(), results.size());
        return new Report(window, n, hold, sMin, roundTripCostPct, prices.size(), face.size(), cohorts.size(),
                results, diags, caveats());
    }

    /**
     * 사업연도 주식수 복원(순수). 1순위: 현 상장주식수 × (자본금ᵧ ÷ 최신 자본금) — 이익소각·우선주 편향 회피.
     * 2순위(상장주식수 미상): 자본금ᵧ ÷ 액면가. 둘 다 불가면 null.
     */
    static Long reconstructShares(Long listedNow, Long capitalLatest, long capitalY, Long faceValue) {
        if (capitalY <= 0) return null;
        if (listedNow != null && listedNow > 0 && capitalLatest != null && capitalLatest > 0) {
            return Math.round(listedNow * ((double) capitalY / capitalLatest));
        }
        if (faceValue != null && faceValue > 0) return capitalY / faceValue;
        return null;
    }

    /**
     * 시총 대비 지표(순수). {@code ratio=true}면 PBR(=시총/자본, 자본 ≤0이면 null — 자본잠식은 순위가 뒤집힘),
     * false면 이익수익률 %(=순이익/시총×100, 적자는 음수 그대로 — 자연히 하위로 간다).
     */
    static Double valuation(double mcap, long denomOrNumer, boolean ratio) {
        if (mcap <= 0) return null;
        if (ratio) return denomOrNumer <= 0 ? null : mcap / denomOrNumer;
        return denomOrNumer / mcap * 100;
    }

    private static AxisResult summarize(String axis, String dir, List<double[]> per, List<String> years,
                                        Map<String, Double> excessByKey) {
        if (per.isEmpty()) {
            excessByKey.put(axis + ":" + dir, 0.0);
            return new AxisResult(axis, dir, 0, 0, 0, 0, Map.of(), 0, 0, 0, null, null, 0, null, false, List.of("코호트 0"), Map.of());
        }
        Map<String, List<Double>> byYear = new TreeMap<>();
        for (int i = 0; i < per.size(); i++) byYear.computeIfAbsent(years.get(i), k -> new ArrayList<>()).add(per.get(i)[0]);
        int pos = 0;
        String topYear = null;
        double topContribution = Double.NEGATIVE_INFINITY;
        Map<String, Double> exYear = new TreeMap<>();
        for (Map.Entry<String, List<Double>> e : byYear.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            exYear.put(e.getKey(), round(avg));
            if (avg > 0) pos++;
            double contribution = avg * e.getValue().size();
            if (contribution > topContribution) { topContribution = contribution; topYear = e.getKey(); }
        }
        double excess = per.stream().mapToDouble(x -> x[0]).average().orElse(0);
        double port = per.stream().mapToDouble(x -> x[1]).average().orElse(0);
        double uni = per.stream().mapToDouble(x -> x[2]).average().orElse(0);
        double full = per.stream().mapToDouble(x -> x[3]).average().orElse(0);
        Map<String, Double> quint = new TreeMap<>();
        for (int q = 0; q < 5; q++) { final int qi = 4 + q; quint.put("q" + (q + 1), round(per.stream().mapToDouble(x -> x[qi]).average().orElse(0))); }
        double worst = per.stream().mapToDouble(x -> x[1]).min().orElse(0);
        final String tY = topYear;
        List<Double> exTop = new ArrayList<>();
        for (int i = 0; i < per.size(); i++) if (!years.get(i).equals(tY)) exTop.add(per.get(i)[0]);
        Double exTopExcess = exTop.isEmpty() ? null : round(exTop.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        List<String> fails = new ArrayList<>();
        if (per.size() < 40) fails.add("코호트 부족(" + per.size() + " < 40)");
        if (excess <= 0) fails.add("초과수익 없음(" + round(excess) + "%p)");
        int need = (int) Math.ceil(byYear.size() * 0.7);
        if (pos < need) fails.add("연도부호 " + pos + "/" + byYear.size() + " < " + need);
        if (exTopExcess == null || exTopExcess <= 0) fails.add("최대기여연도(" + topYear + ") 제외 시 소멸(" + exTopExcess + ")");
        excessByKey.put(axis + ":" + dir, excess);
        return new AxisResult(axis, dir, per.size(), round(port), round(uni), round(full), quint, round(excess), pos, byYear.size(),
                topYear, exTopExcess, round(worst), null, fails.isEmpty(), fails, exYear);
    }

    private static List<String> caveats() {
        return List.of(
                "주식수는 자본금÷액면가로 복원한 근사 — 우선주 자본금 포함·무액면주·자사주 미반영. 순위(횡단면)에는 대체로 무해하나 절대 PBR은 diagnostics 중앙값으로 자릿수를 확인할 것.",
                "생존편향: 재무는 현 워치리스트 1,324종목만 있어 폐지 종목이 빠진다. 저PBR(=부실 근접)이 가장 불리한 축이므로 저PBR의 열위는 편향일 수 있고, 우위는 편향을 이겨낸 것이다.",
                "배당 미반영(수정주가) — 가치주는 대개 고배당이라 구조적으로 불리하게 평가된다.",
                "판정은 탐색(until)에서 고르고 holdout(since)에서 한 번만 확인할 것. 이 시스템은 holdout을 이미 4회 썼다.");
    }

    // ── 데이터 ─────────────────────────────────────────

    private Map<String, Series> loadSeries() {
        Map<String, Series> out = new HashMap<>();
        int[][] ib = {new int[4096], new int[4096]};
        long[][] lb = {new long[4096]};
        int[] size = {0};
        String[] cur = {null};
        jdbcTemplate.query("select stock_code, business_date, close_price, volume from daily_price order by stock_code, business_date",
                rs -> {
                    String code = rs.getString(1);
                    if (cur[0] != null && !cur[0].equals(code)) {
                        out.put(cur[0], new Series(Arrays.copyOf(ib[0], size[0]), Arrays.copyOf(ib[1], size[0]), Arrays.copyOf(lb[0], size[0])));
                        size[0] = 0;
                    }
                    cur[0] = code;
                    if (size[0] == ib[0].length) {
                        ib[0] = Arrays.copyOf(ib[0], ib[0].length * 2);
                        ib[1] = Arrays.copyOf(ib[1], ib[1].length * 2);
                        lb[0] = Arrays.copyOf(lb[0], lb[0].length * 2);
                    }
                    int k = size[0]++;
                    ib[0][k] = Integer.parseInt(rs.getString(2));
                    ib[1][k] = (int) rs.getLong(3);
                    lb[0][k] = rs.getLong(4);
                });
        if (cur[0] != null && size[0] > 0) out.put(cur[0], new Series(Arrays.copyOf(ib[0], size[0]), Arrays.copyOf(ib[1], size[0]), Arrays.copyOf(lb[0], size[0])));
        return out;
    }

    private static double avgTurnover(Series s, int idx, int days) {
        int from = Math.max(0, idx - days + 1);
        double sum = 0;
        for (int t = from; t <= idx; t++) sum += (double) s.close()[t] * s.volume()[t];
        return sum / (idx - from + 1);
    }

    private static int[] tradingCalendar(Map<String, Series> prices) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Series s : prices.values()) for (int d : s.dates()) set.add(d);
        int[] out = new int[set.size()];
        int i = 0;
        for (int d : set) out[i++] = d;
        return out;
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(xs);
        s.sort(null);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }

    private static int ymd(LocalDate d) { return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth(); }
    private static LocalDate toDate(int ymd) { return LocalDate.of(ymd / 10000, ymd / 100 % 100, ymd % 100); }

    private static int onOrAfter(int[] s, int v) {
        int i = Arrays.binarySearch(s, v);
        if (i >= 0) return s[i];
        int ins = -i - 1;
        return ins < s.length ? s[ins] : -1;
    }

    private static int onOrBefore(int[] s, int v) {
        int i = Arrays.binarySearch(s, v);
        if (i >= 0) return s[i];
        int ins = -i - 1;
        return ins > 0 ? s[ins - 1] : -1;
    }

    /** 종목 날짜 배열에서 v 이상(after=true) / 이하 첫 인덱스. 없으면 -1. */
    private static int idx(int[] s, int v, boolean after) {
        int i = Arrays.binarySearch(s, v);
        if (i >= 0) return i;
        int ins = -i - 1;
        if (after) return ins < s.length ? ins : -1;
        return ins > 0 ? ins - 1 : -1;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
