package com.stockadvisor.service;

import com.stockadvisor.domain.FinancialFact;
import com.stockadvisor.repository.FinancialFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 멀티데이 전략 백테스트 — <b>월별 진입 + 트레일링 청산</b> 경로 시뮬.
 *
 * <p><b>왜 다시 만드나</b>(2026-08-28 사용자 지적): 앞선 {@link FinancialSpreadAnalysisService}는
 * {@code horizon=12개월} 고정 보유였는데 그건 <b>전략이 아니라 재무 갱신 주기</b>였다. 실제 전략은
 * 보유 상한 1개월 + 트레일링 청산이다. 그 오류의 대가가 컸다 — <b>독립 관측이 연 단위 9개뿐</b>이라
 * 한 해(2025)가 결과를 통째로 지배했다.</p>
 *
 * <p>수정: 재무 스코어는 공시 주기상 연 1회 갱신이 불가피하지만 <b>진입 시점은 그 유효기간 안에서 매달</b>
 * 가능하다(2024 재무는 2025-05~2026-04 동안 공개된 정보다). → 관측이 9 → <b>~110개</b>로 늘고
 * 특정 연도 의존이 사라진다.</p>
 *
 * <p>⚠️ <b>표본 기간 10년은 보유기간과 무관하게 필요하다</b> — 하락장(2018·2020·2022)을 표본에 넣기 위해서다.
 * 물타기·트레일링 계열은 정확히 하락장에서 죽는데, 최근 3년만 보면 거의 상승장뿐이라 그 위험이 표본에서 사라진다.</p>
 *
 * <p>⚠️ <b>물타기(분할 추가매수)는 아직 시뮬에 없다</b> — 진입 1회 기준이다. 청산 규칙의 기여를 먼저 격리해 보고,
 * 그 다음에 얹어야 무엇이 무엇을 만들었는지 분해할 수 있다.</p>
 */
@Service
public class MultidayBacktestService {

    private static final Logger log = LoggerFactory.getLogger(MultidayBacktestService.class);
    /** 재무 사용 개시 월 — 사업보고서 제출기한(3월 말) + 지연 여유. FinancialSpreadAnalysisService와 같은 규칙. */
    private static final int BASIS_MONTH = 5;

    private final FinancialFactRepository factRepository;
    private final JdbcTemplate jdbcTemplate;
    private final double roundTripCostPct;

    public MultidayBacktestService(FinancialFactRepository factRepository,
                                   JdbcTemplate jdbcTemplate,
                                   @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripCostPct) {
        this.factRepository = factRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.roundTripCostPct = roundTripCostPct;
    }

    /** 한 종목의 일봉 시계열(엔티티 대신 원시 배열 — 280만 행을 영속성 컨텍스트에 올리면 VM 메모리가 못 버틴다). */
    private record Series(int[] dates, int[] open, int[] high, int[] low, int[] close) {}

    /**
     * @param portfolioNetPct   선정 30종목 동일가중 · 트레일링 청산 · 비용차감
     * @param pickedHoldNetPct  <b>같은 종목</b>을 단순보유했을 때 — 차이가 곧 <b>청산 규칙의 기여</b>
     * @param universeHoldNetPct 유니버스 전체 단순보유 — 차이가 곧 <b>선정의 기여</b>
     * @param armedRatePct      트레일이 무장된 비율(= +armPct 도달 비율)
     */
    public record Cohort(String entryDate, int picked, double portfolioNetPct,
                         double pickedHoldNetPct, double universeHoldNetPct,
                         double excessVsUniversePct, double exitContribPct,
                         double armedRatePct, double avgPeakPct, double avgTroughPct) {}

    public record YearStat(String year, int cohorts, double portfolioNetPct,
                           double universeHoldNetPct, double excessPct) {}

    public record Arm(String tieBreak, int cohorts,
                      double avgPortfolioNetPct, double avgPickedHoldNetPct, double avgUniverseHoldNetPct,
                      double avgExcessPct, double avgExitContribPct,
                      double armedRatePct, double avgPeakPct, double avgTroughPct, double worstCohortPct,
                      int yearsPositive, int yearsTotal, Double excessExTopYearPct, String topYear,
                      List<YearStat> byYear, List<Cohort> cohortsDetail) {}

    public record Verdict(boolean pass, List<String> reasons) {}

    public record BacktestReport(int topN, int scoreMin, double armPct, double dropPct,
                                 int maxHoldMonths, int lookbackMonths, double roundTripCostPct,
                                 String rule, int stocksLoaded, int tradingDays,
                                 Arm strategy, Arm control, Verdict verdict, List<String> caveats) {}

    /**
     * @param armPct        트레일 무장 문턱 — 이 수익률에 도달해야 트레일이 걸린다(기본 5%)
     * @param dropPct       고점 대비 하락 청산폭(기본 2%)
     * @param maxHoldMonths 보유 상한(기본 1개월)
     * @param lookbackMonths 2차 정렬용 과거 수익률 창(기본 6개월 — 보유가 1개월이라 12개월은 너무 길다)
     */
    public BacktestReport run(Integer topN, Integer scoreMin, Double armPct, Double dropPct,
                              Integer maxHoldMonths, Integer lookbackMonths, Integer minEvaluated) {
        int n = (topN == null || topN <= 0) ? 30 : topN;
        int sMin = (scoreMin == null) ? 6 : scoreMin;
        double arm = (armPct == null) ? 5.0 : armPct;
        double drop = (dropPct == null) ? 2.0 : dropPct;
        int hold = (maxHoldMonths == null || maxHoldMonths <= 0) ? 1 : maxHoldMonths;
        int look = (lookbackMonths == null || lookbackMonths <= 0) ? 6 : lookbackMonths;
        int minEval = (minEvaluated == null || minEvaluated < 0) ? 5 : minEvaluated;

        Map<String, Series> prices = loadSeries();
        int[] calendar = tradingCalendar(prices);

        // (종목,사업연도) → F-Score
        Map<String, Map<Integer, Integer>> scores = new HashMap<>();
        for (FinancialFact f : factRepository.findAll()) {
            FinancialScore.Result r = FinancialScore.of(f);
            if (r.evaluated() < minEval) continue;
            try {
                scores.computeIfAbsent(f.getStockCode(), k -> new HashMap<>())
                        .put(Integer.parseInt(f.getBusinessYear()), r.score());
            } catch (NumberFormatException ignored) {
                // 사업연도가 비정상인 행은 건너뛴다(수집 단계에서 거의 없다)
            }
        }

        Arm strategy = simulateArm(prices, calendar, scores, n, sMin, arm, drop, hold, look, "FALLEN");
        Arm control = simulateArm(prices, calendar, scores, n, sMin, arm, drop, hold, look, "RISEN");

        log.info("[멀티데이백테스트] 코호트 {} · 전략 net {}% (초과 {}%p) · 대조군 net {}% (초과 {}%p)",
                strategy.cohorts(), strategy.avgPortfolioNetPct(), strategy.avgExcessPct(),
                control.avgPortfolioNetPct(), control.avgExcessPct());

        return new BacktestReport(n, sMin, arm, drop, hold, look, roundTripCostPct,
                String.format("진입: 매월 첫 거래일 종가 · 청산: 수익 +%.1f%% 도달 후 고점대비 -%.1f%% / 상한 %d개월 종가",
                        arm, drop, hold),
                prices.size(), calendar.length,
                strategy, control, verdict(strategy, control), caveats());
    }

    private Arm simulateArm(Map<String, Series> prices, int[] calendar,
                            Map<String, Map<Integer, Integer>> scores,
                            int topN, int scoreMin, double arm, double drop,
                            int holdMonths, int lookMonths, String tieBreak) {
        List<Cohort> cohorts = new ArrayList<>();
        LocalDate first = toDate(calendar[0]).withDayOfMonth(1).plusMonths(lookMonths + 1);
        LocalDate last = toDate(calendar[calendar.length - 1]);

        for (LocalDate m = first; !m.plusMonths(holdMonths).isAfter(last); m = m.plusMonths(1)) {
            int entryDate = onOrAfter(calendar, ymd(m));
            if (entryDate < 0) continue;
            int limitDate = onOrBefore(calendar, ymd(m.plusMonths(holdMonths)));
            int backDate = onOrAfter(calendar, ymd(m.minusMonths(lookMonths)));
            if (limitDate < 0 || backDate < 0) continue;

            Integer fy = validBusinessYear(m);
            if (fy == null) continue;

            record Cand(String code, double trailing) {}
            List<Cand> cands = new ArrayList<>();
            double uniSum = 0;
            int uniN = 0;

            for (Map.Entry<String, Series> e : prices.entrySet()) {
                Series s = e.getValue();
                int ei = idxOnOrAfter(s.dates(), entryDate);
                int li = idxOnOrBefore(s.dates(), limitDate);
                if (ei < 0 || li <= ei) continue;
                // 유니버스 벤치마크(단순보유) — 선정 여부와 무관하게 가격이 있는 전 종목
                uniSum += pct(s.close()[ei], s.close()[li]) - roundTripCostPct;
                uniN++;

                Map<Integer, Integer> byYear = scores.get(e.getKey());
                Integer sc = byYear == null ? null : byYear.get(fy);
                if (sc == null || sc < scoreMin) continue;
                int bi = idxOnOrAfter(s.dates(), backDate);
                if (bi < 0 || bi >= ei) continue;
                cands.add(new Cand(e.getKey(), pct(s.close()[bi], s.close()[ei])));
            }
            if (cands.isEmpty() || uniN == 0) continue;

            cands.sort("FALLEN".equals(tieBreak)
                    ? (a, b) -> Double.compare(a.trailing(), b.trailing())
                    : (a, b) -> Double.compare(b.trailing(), a.trailing()));
            List<Cand> picked = cands.subList(0, Math.min(topN, cands.size()));

            double netSum = 0, holdSum = 0, peakSum = 0, troughSum = 0;
            int armedCount = 0;
            for (Cand c : picked) {
                Series s = prices.get(c.code());
                int ei = idxOnOrAfter(s.dates(), entryDate);
                int li = idxOnOrBefore(s.dates(), limitDate);
                TrailingExitSimulator.Exit x = TrailingExitSimulator.run(
                        s.open(), s.high(), s.low(), s.close(), ei, li, arm, drop);
                netSum += pct(s.close()[ei], x.exitPrice()) - roundTripCostPct;
                holdSum += pct(s.close()[ei], s.close()[li]) - roundTripCostPct;
                peakSum += x.peakPct();
                troughSum += x.troughPct();
                if (x.armed()) armedCount++;
            }
            int k = picked.size();
            double port = netSum / k, pickHold = holdSum / k, uni = uniSum / uniN;
            cohorts.add(new Cohort(String.valueOf(entryDate), k, round(port), round(pickHold), round(uni),
                    round(port - uni), round(port - pickHold),
                    round(armedCount * 100.0 / k), round(peakSum / k), round(troughSum / k)));
        }
        return aggregate(tieBreak, cohorts);
    }

    private static Arm aggregate(String tieBreak, List<Cohort> cohorts) {
        if (cohorts.isEmpty()) {
            return new Arm(tieBreak, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, List.of(), List.of());
        }
        Map<String, List<Cohort>> byYear = new TreeMap<>();
        for (Cohort c : cohorts) byYear.computeIfAbsent(c.entryDate().substring(0, 4), k -> new ArrayList<>()).add(c);

        List<YearStat> years = new ArrayList<>();
        int positive = 0;
        String topYear = null;
        double topContribution = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, List<Cohort>> e : byYear.entrySet()) {
            List<Cohort> cs = e.getValue();
            double ex = cs.stream().mapToDouble(Cohort::excessVsUniversePct).average().orElse(0);
            years.add(new YearStat(e.getKey(), cs.size(),
                    round(cs.stream().mapToDouble(Cohort::portfolioNetPct).average().orElse(0)),
                    round(cs.stream().mapToDouble(Cohort::universeHoldNetPct).average().orElse(0)), round(ex)));
            if (ex > 0) positive++;
            double contribution = ex * cs.size();
            if (contribution > topContribution) {
                topContribution = contribution;
                topYear = e.getKey();
            }
        }
        // LOO — 최대기여연도를 빼도 부호가 유지되는지(이 시스템에서 클러스터 함정이 6회 재발했다)
        final String tY = topYear;
        List<Cohort> exTop = cohorts.stream().filter(c -> !c.entryDate().startsWith(tY)).toList();
        Double exTopExcess = exTop.isEmpty() ? null
                : round(exTop.stream().mapToDouble(Cohort::excessVsUniversePct).average().orElse(0));

        return new Arm(tieBreak, cohorts.size(),
                round(avg(cohorts, Cohort::portfolioNetPct)), round(avg(cohorts, Cohort::pickedHoldNetPct)),
                round(avg(cohorts, Cohort::universeHoldNetPct)), round(avg(cohorts, Cohort::excessVsUniversePct)),
                round(avg(cohorts, Cohort::exitContribPct)), round(avg(cohorts, Cohort::armedRatePct)),
                round(avg(cohorts, Cohort::avgPeakPct)), round(avg(cohorts, Cohort::avgTroughPct)),
                round(cohorts.stream().mapToDouble(Cohort::portfolioNetPct).min().orElse(0)),
                positive, years.size(), exTopExcess, topYear, years, cohorts);
    }

    /**
     * <b>사전 등록 판정</b> — 결과를 보고 기준을 정하지 않기 위해 코드에 박아둔다.
     * 이 시스템은 클러스터 함정을 6회 재발시켰고, 매번 수치를 본 <b>뒤에</b> 해석 기준을 만들었다.
     */
    private static Verdict verdict(Arm s, Arm c) {
        List<String> reasons = new ArrayList<>();
        boolean pass = true;
        if (s.cohorts() < 60) {
            pass = false;
            reasons.add("코호트 부족(" + s.cohorts() + " < 60)");
        }
        if (s.avgExcessPct() <= 0) {
            pass = false;
            reasons.add(String.format("유니버스 대비 초과수익 없음(%.2f%%p ≤ 0)", s.avgExcessPct()));
        }
        int need = (int) Math.ceil(s.yearsTotal() * 0.7);
        if (s.yearsPositive() < need) {
            pass = false;
            reasons.add(String.format("연도별 초과 부호 %d/%d < 요구 %d", s.yearsPositive(), s.yearsTotal(), need));
        }
        if (s.excessExTopYearPct() == null || s.excessExTopYearPct() <= 0) {
            pass = false;
            reasons.add(String.format("최대기여연도(%s) 제외 시 초과수익 소멸(%s)", s.topYear(), s.excessExTopYearPct()));
        }
        if (s.avgExcessPct() <= c.avgExcessPct()) {
            pass = false;
            reasons.add(String.format("대조군(방향 반전) 대비 우위 없음(%.2f ≤ %.2f)",
                    s.avgExcessPct(), c.avgExcessPct()));
        }
        if (pass) reasons.add("사전 등록 기준 전부 충족");
        return new Verdict(pass, reasons);
    }

    private static List<String> caveats() {
        return List.of(
                "생존편향 미해소: 유니버스가 '오늘 기준 시총 상위 1,500'이라 절대 net은 낙관 상한이다. "
                        + "그리고 편향은 버킷 간 비대칭이다(저품질일수록 폐지 확률이 높아 살아남은 표본이 더 선택됨) — "
                        + "유니버스 벤치마크에도 같은 편향이 걸리므로 excess(차이)를 볼 것.",
                "물타기(분할 추가매수)는 시뮬에 없다 — 진입 1회 기준이다. 청산 규칙의 기여를 먼저 격리한 것.",
                "손절이 없다(지정 규칙에 없음). 무장 전 하락은 보유 상한까지 간다 — avgTroughPct(MAE)로 그 대가를 볼 것.",
                "일봉 시뮬이라 하루 안의 고가·저가 순서를 모른다. 보수적 규약(전일 고점 기준 스톱 먼저 판정, "
                        + "갭 하락은 시가 체결)을 썼으므로 실제보다 낙관적이지는 않다.",
                "배당 미반영(수정주가) — 고배당 종목이 구조적으로 불리하게 평가된다.",
                "월별 코호트는 서로 겹치지 않지만(보유 1개월) 같은 시장 국면을 공유하므로 완전히 독립이 아니다.");
    }

    // ── 데이터 로딩 ─────────────────────────────────────

    private Map<String, Series> loadSeries() {
        Map<String, Series> out = new HashMap<>();
        int[][] buf = {new int[4096], new int[4096], new int[4096], new int[4096], new int[4096]};
        int[] size = {0};
        String[] current = {null};
        jdbcTemplate.query(
                "select stock_code, business_date, open_price, high_price, low_price, close_price "
                        + "from daily_price order by stock_code, business_date",
                rs -> {
                    String code = rs.getString(1);
                    if (current[0] != null && !current[0].equals(code)) {
                        out.put(current[0], freeze(buf, size[0]));
                        size[0] = 0;
                    }
                    current[0] = code;
                    if (size[0] == buf[0].length) {
                        for (int i = 0; i < 5; i++) buf[i] = Arrays.copyOf(buf[i], buf[i].length * 2);
                    }
                    int k = size[0]++;
                    buf[0][k] = Integer.parseInt(rs.getString(2));
                    buf[1][k] = (int) rs.getLong(3);
                    buf[2][k] = (int) rs.getLong(4);
                    buf[3][k] = (int) rs.getLong(5);
                    buf[4][k] = (int) rs.getLong(6);
                });
        if (current[0] != null && size[0] > 0) out.put(current[0], freeze(buf, size[0]));
        log.info("[멀티데이백테스트] 일봉 로드 {}종목", out.size());
        return out;
    }

    private static Series freeze(int[][] buf, int n) {
        return new Series(Arrays.copyOf(buf[0], n), Arrays.copyOf(buf[1], n),
                Arrays.copyOf(buf[2], n), Arrays.copyOf(buf[3], n), Arrays.copyOf(buf[4], n));
    }

    /** 전 종목 날짜 합집합 = 거래일 달력(진입일 결정용 — 특정 종목 휴장에 좌우되지 않게). */
    private static int[] tradingCalendar(Map<String, Series> prices) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Series s : prices.values()) for (int d : s.dates()) set.add(d);
        int[] out = new int[set.size()];
        int i = 0;
        for (int d : set) out[i++] = d;
        return out;
    }

    // ── 유틸 ────────────────────────────────────────────

    /** 날짜 D에 공개돼 있는 최신 사업연도. (Y+1)년 5월부터 (Y+2)년 4월까지 Y가 유효하다. */
    static Integer validBusinessYear(LocalDate d) {
        return d.getMonthValue() >= BASIS_MONTH ? d.getYear() - 1 : d.getYear() - 2;
    }

    private static double pct(double from, double to) {
        return from <= 0 ? 0 : (to - from) / from * 100;
    }

    private static double avg(List<Cohort> cs, java.util.function.ToDoubleFunction<Cohort> f) {
        return cs.stream().mapToDouble(f).average().orElse(0);
    }

    private static int ymd(LocalDate d) {
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private static LocalDate toDate(int ymd) {
        return LocalDate.of(ymd / 10000, ymd / 100 % 100, ymd % 100);
    }

    private static int onOrAfter(int[] sorted, int v) {
        int i = idxOnOrAfter(sorted, v);
        return i < 0 ? -1 : sorted[i];
    }

    private static int onOrBefore(int[] sorted, int v) {
        int i = idxOnOrBefore(sorted, v);
        return i < 0 ? -1 : sorted[i];
    }

    static int idxOnOrAfter(int[] sorted, int v) {
        int lo = 0, hi = sorted.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] >= v) { found = mid; hi = mid - 1; } else { lo = mid + 1; }
        }
        return found;
    }

    static int idxOnOrBefore(int[] sorted, int v) {
        int lo = 0, hi = sorted.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= v) { found = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return found;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
