package com.stockadvisor.service;

import com.stockadvisor.domain.DailyPrice;
import com.stockadvisor.domain.FinancialFact;
import com.stockadvisor.repository.DailyPriceRepository;
import com.stockadvisor.repository.FinancialFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>랭킹 스프레드 분석</b> — "재무 스코어에 종목 선정력이 있는가"를 전략 구현 <b>이전에</b> 답한다.
 *
 * <p>이 분석이 필요한 이유(2026-08-28 사용자 지적): 우상향한 종목에 물타기하면 +가 나오는 건 전략의 성질이 아니라
 * <b>표본의 성질</b>이다. 그래서 "수익 나나"가 아니라 <b>"스코어 상위가 하위보다 나은가"</b>를 물어야 한다.</p>
 *
 * <p><b>왜 스프레드가 생존편향에 강한가</b>: 유니버스가 "오늘 살아남아 시총 상위인 종목"이라 전 구간이 통째로
 * 들어올려지지만, 그 효과는 상위 버킷과 하위 버킷에 <b>공통으로</b> 걸린다. 절대 수익률은 못 믿어도
 * <b>버킷 간 차이</b>는 유효하다. (이 시스템의 진입-대조군 edge와 같은 논리 — lift는 반사실이 아니고 차이만 반사실이다.)</p>
 *
 * <p>⚠️ <b>look-ahead 방지가 이 분석 유효성의 전부다.</b> 사업보고서는 사업연도 종료 후 90일 이내(=3월 말) 제출이라,
 * Y년 재무를 Y년 중에 쓰면 <b>미래를 보는 것</b>이다. 기준일을 <b>(Y+1)년 5월 첫 거래일</b>로 잡아 제출 지연까지
 * 여유를 뒀다. 수급 소급 태깅의 "직전 거래일" 규칙에 해당하는 안전장치가 여기선 이것이다.</p>
 */
@Service
public class FinancialSpreadAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FinancialSpreadAnalysisService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 재무 사용 개시 월 — 사업보고서 제출기한(3월 말) + 지연 여유. */
    private static final int BASIS_MONTH = 5;

    private final FinancialFactRepository factRepository;
    private final DailyPriceRepository dailyPriceRepository;

    public FinancialSpreadAnalysisService(FinancialFactRepository factRepository,
                                          DailyPriceRepository dailyPriceRepository) {
        this.factRepository = factRepository;
        this.dailyPriceRepository = dailyPriceRepository;
    }

    /**
     * @param score      F-Score(0~7)
     * @param liftPct    전체 평균 대비(= 이 버킷 평균 − 전체 평균). <b>주지표</b> — 절대 수익률은 시장 드리프트가 지배한다
     */
    public record Bucket(int score, int samples, int stocks, double avgReturnPct, double medianReturnPct,
                         double winRatePct, double liftPct) {}

    /** 연도별 분해 — <b>부호가 해마다 유지되는지</b>가 다중검정 허수를 거르는 실질 기준이다. */
    public record YearStat(String basisYear, int samples,
                           int highSamples, double highAvgPct,
                           int lowSamples, double lowAvgPct,
                           Double spreadPct) {}

    /**
     * 실제 구성(상위 N종목 동일가중)의 연도별 성적 — 버킷 평균이 답하지 못하는 "30종목 뽑으면 어떻게 되나".
     *
     * @param excessPct 같은 해 유니버스 평균 대비 초과수익. <b>이게 주지표다</b>(절대값은 시장 드리프트가 지배)
     */
    public record PortfolioYear(String basisYear, int picked, double avgReturnPct,
                                double universeAvgPct, double excessPct, double winRatePct,
                                Double medianTrailingPct) {}

    /**
     * @param tieBreak  2차 정렬 규칙. FALLEN=직전 12개월 하위(많이 떨어진 순, 전략의 전제) /
     *                  RISEN=상위(<b>내장 대조군</b> — 같은 F-Score 안에서 방향만 뒤집어 비교) / NONE=코드순
     */
    public record PortfolioSim(int topN, int scoreMin, String tieBreak,
                               List<PortfolioYear> byYear,
                               double avgExcessPct, int yearsPositive, int yearsTotal) {}

    /**
     * 한 (종목, 기준연도)의 관측치.
     *
     * @param trailingPct 기준일 시점의 직전 12개월 수익률 — <b>과거 가격만</b> 참조하므로 look-ahead가 아니다.
     *                    F-Score가 0~7 정수라 1,500종목을 30개로 좁힐 수 없어(6~7점이 수백 개) 2차 정렬 축이 필요하다.
     */
    record Row(String basisYear, int score, double returnPct, String stockCode, Double trailingPct) {}

    public record SpreadReport(int horizonMonths, int minEvaluated, int highMin, int lowMax,
                               String basisRule, int factRows, int usableRows, int stocks,
                               double overallAvgPct,
                               List<Bucket> buckets, List<YearStat> byYear,
                               Double spreadHighMinusLowPct, Double highLiftPct,
                               int yearsPositive, int yearsTotal,
                               PortfolioSim portfolio, PortfolioSim portfolioControl,
                               List<String> caveats) {}

    /**
     * @param horizonMonths 보유 개월(기본 12) — 연 1회 리밸런싱 가정
     * @param minEvaluated  F-Score 판정 가능 항목 최소치(기본 5). 금융업 등 결손 종목이 하위 버킷을 오염시키는 것 방지
     * @param highMin       상위 그룹 하한 점수(기본 6)
     * @param lowMax        하위 그룹 상한 점수(기본 2)
     */
    public SpreadReport analyze(Integer horizonMonths, Integer minEvaluated, Integer highMin, Integer lowMax) {
        return analyze(horizonMonths, minEvaluated, highMin, lowMax, null, null);
    }

    /**
     * @param topN     포트폴리오 종목 수(기본 30). 선정력은 분산에서만 실현되므로 이 수가 결과를 크게 바꾼다
     * @param scoreMin 포트폴리오 편입 F-Score 하한(기본 = highMin)
     */
    public SpreadReport analyze(Integer horizonMonths, Integer minEvaluated, Integer highMin, Integer lowMax,
                                Integer topN, Integer scoreMin) {
        int horizon = (horizonMonths == null || horizonMonths <= 0) ? 12 : horizonMonths;
        int minEval = (minEvaluated == null || minEvaluated < 0) ? 5 : minEvaluated;
        int hi = (highMin == null) ? 6 : highMin;
        int lo = (lowMax == null) ? 2 : lowMax;
        int n = (topN == null || topN <= 0) ? 30 : topN;
        int portScoreMin = (scoreMin == null) ? hi : scoreMin;

        List<FinancialFact> facts = factRepository.findAll();
        // 종목별로 묶어 일봉을 1회만 로드한다(행 단위 조회면 수만 쿼리가 된다).
        Map<String, List<FinancialFact>> byStock = new HashMap<>();
        for (FinancialFact f : facts) {
            byStock.computeIfAbsent(f.getStockCode(), k -> new ArrayList<>()).add(f);
        }

        List<Row> rows = new ArrayList<>();

        for (Map.Entry<String, List<FinancialFact>> e : byStock.entrySet()) {
            String code = e.getKey();
            List<DailyPrice> prices = dailyPriceRepository
                    .findByStockCodeAndBusinessDateBetweenOrderByBusinessDateAsc(code, "00000000", "99999999");
            if (prices.isEmpty()) continue;

            for (FinancialFact f : e.getValue()) {
                FinancialScore.Result sc = FinancialScore.of(f);
                if (sc.evaluated() < minEval) continue;

                LocalDate basis = basisDate(f.getBusinessYear());
                if (basis == null) continue;
                Integer entryIdx = firstOnOrAfter(prices, basis.format(YYYYMMDD));
                if (entryIdx == null) continue;
                Integer exitIdx = firstOnOrAfter(prices, basis.plusMonths(horizon).format(YYYYMMDD));
                if (exitIdx == null) continue;   // horizon 끝이 데이터 밖 → 제외(미래를 추정하지 않는다)

                long entry = prices.get(entryIdx).getClosePrice();
                long exit = prices.get(exitIdx).getClosePrice();
                if (entry <= 0) continue;

                // 직전 12개월 수익률(과거만 참조 — look-ahead 아님). 데이터가 없으면 null → 포트폴리오 선정에서 제외.
                Double trailing = null;
                Integer backIdx = firstOnOrAfter(prices, basis.minusMonths(12).format(YYYYMMDD));
                if (backIdx != null && backIdx < entryIdx) {
                    long back = prices.get(backIdx).getClosePrice();
                    if (back > 0) trailing = (double) (entry - back) / back * 100;
                }
                rows.add(new Row(String.valueOf(basis.getYear()), sc.score(),
                        (double) (exit - entry) / entry * 100, code, trailing));
            }
        }

        double overall = rows.stream().mapToDouble(Row::returnPct).average().orElse(0);

        // ── 점수 버킷 ────────────────────────────────────
        Map<Integer, List<Row>> byScore = new TreeMap<>();
        for (Row r : rows) byScore.computeIfAbsent(r.score(), k -> new ArrayList<>()).add(r);

        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<Integer, List<Row>> e : byScore.entrySet()) {
            List<Row> rs = e.getValue();
            double avg = rs.stream().mapToDouble(Row::returnPct).average().orElse(0);
            double win = rs.stream().filter(r -> r.returnPct() > 0).count() * 100.0 / rs.size();
            long stocks = rs.stream().map(Row::stockCode).distinct().count();
            buckets.add(new Bucket(e.getKey(), rs.size(), (int) stocks, round(avg),
                    round(median(rs.stream().mapToDouble(Row::returnPct).sorted().toArray())),
                    round(win), round(avg - overall)));
        }

        // ── 연도별 분해(일관성 테스트) ──────────────────
        Map<String, List<Row>> byYearMap = new TreeMap<>();
        for (Row r : rows) byYearMap.computeIfAbsent(r.basisYear(), k -> new ArrayList<>()).add(r);

        List<YearStat> byYear = new ArrayList<>();
        int yearsPositive = 0, yearsWithSpread = 0;
        for (Map.Entry<String, List<Row>> e : byYearMap.entrySet()) {
            List<Row> rs = e.getValue();
            List<Row> high = rs.stream().filter(r -> r.score() >= hi).toList();
            List<Row> low = rs.stream().filter(r -> r.score() <= lo).toList();
            Double spread = null;
            double highAvg = high.stream().mapToDouble(Row::returnPct).average().orElse(0);
            double lowAvg = low.stream().mapToDouble(Row::returnPct).average().orElse(0);
            if (!high.isEmpty() && !low.isEmpty()) {
                spread = round(highAvg - lowAvg);
                yearsWithSpread++;
                if (spread > 0) yearsPositive++;
            }
            byYear.add(new YearStat(e.getKey(), rs.size(), high.size(), round(highAvg),
                    low.size(), round(lowAvg), spread));
        }

        List<Row> highAll = rows.stream().filter(r -> r.score() >= hi).toList();
        List<Row> lowAll = rows.stream().filter(r -> r.score() <= lo).toList();
        Double spread = (highAll.isEmpty() || lowAll.isEmpty()) ? null
                : round(highAll.stream().mapToDouble(Row::returnPct).average().orElse(0)
                - lowAll.stream().mapToDouble(Row::returnPct).average().orElse(0));
        Double highLift = highAll.isEmpty() ? null
                : round(highAll.stream().mapToDouble(Row::returnPct).average().orElse(0) - overall);

        // ── 실제 구성 시뮬(상위 N 동일가중) + 내장 대조군 ──
        // 버킷 평균은 "고득점이 나은가"만 답한다. 실제로 운용할 구성은 "매년 상위 30종목"이므로 따로 시뮬한다.
        PortfolioSim portfolio = simulate(byYearMap, n, portScoreMin, "FALLEN");
        PortfolioSim control = simulate(byYearMap, n, portScoreMin, "RISEN");

        log.info("[선정력] horizon={}개월 표본 {}행 · 전체평균 {}% · 스프레드 {} · 연도부호 {}/{} · 포트폴리오 초과 {}%(대조 {}%)",
                horizon, rows.size(), round(overall), spread, yearsPositive, yearsWithSpread,
                portfolio.avgExcessPct(), control.avgExcessPct());

        return new SpreadReport(horizon, minEval, hi, lo,
                "기준일 = (사업연도+1)년 " + BASIS_MONTH + "월 첫 거래일 — 사업보고서 제출기한(3월 말) 이후로 잡아 look-ahead 차단",
                facts.size(), rows.size(),
                (int) rows.stream().map(Row::stockCode).distinct().count(),
                round(overall), buckets, byYear, spread, highLift,
                yearsPositive, yearsWithSpread, portfolio, control, caveats());
    }

    /**
     * 매년 기준일에 상위 N종목을 뽑아 <b>동일가중</b>으로 horizon 보유했을 때의 성적.
     *
     * <p>선정 규칙: {@code F-Score ≥ scoreMin} 필터 → 2차 정렬로 N개. F-Score는 0~7 정수라 <b>필터일 뿐 랭킹이 아니고</b>,
     * 실제 구성을 재현하려면 2차 축이 반드시 필요하다(Piotroski 원 논문도 저평가 구간 <b>안에서</b> F-Score를 적용했다).
     * 여기선 이미 가진 데이터로 그 역할을 <b>직전 12개월 수익률</b>이 한다 — "우량기업 중 많이 떨어진 것"이라는
     * 전략의 전제와 정확히 같은 축이다.</p>
     *
     * <p>⚠️ <b>{@code RISEN}은 내장 대조군이다.</b> 같은 F-Score 풀 안에서 2차 정렬 방향만 뒤집어 뽑는다 —
     * FALLEN이 RISEN보다 나아야 "떨어진 것을 산다"는 전제가 지지된다. 둘이 비슷하면 F-Score만 작동한 것이고,
     * RISEN이 나으면 전략의 전제가 반증된 것이다(모멘텀이 이긴다).</p>
     *
     * <p>⚠️ 주지표는 절대 수익이 아니라 <b>같은 해 유니버스 평균 대비 초과분</b>이다 — 절대값은 시장 드리프트와
     * 생존편향이 지배하고, 그 둘은 유니버스 평균에도 똑같이 걸려 차감된다.</p>
     */
    static PortfolioSim simulate(Map<String, List<Row>> byYearMap, int topN, int scoreMin, String tieBreak) {
        List<PortfolioYear> years = new ArrayList<>();
        int positive = 0;
        double excessSum = 0;

        for (Map.Entry<String, List<Row>> e : byYearMap.entrySet()) {
            List<Row> all = e.getValue();
            double universeAvg = all.stream().mapToDouble(Row::returnPct).average().orElse(0);

            List<Row> pool = new ArrayList<>(all.stream()
                    .filter(r -> r.score() >= scoreMin)
                    .filter(r -> "NONE".equals(tieBreak) || r.trailingPct() != null)
                    .toList());
            if (pool.isEmpty()) continue;

            if ("FALLEN".equals(tieBreak)) {
                pool.sort((a, b) -> Double.compare(a.trailingPct(), b.trailingPct()));       // 많이 떨어진 순
            } else if ("RISEN".equals(tieBreak)) {
                pool.sort((a, b) -> Double.compare(b.trailingPct(), a.trailingPct()));       // 많이 오른 순
            } else {
                pool.sort((a, b) -> a.stockCode().compareTo(b.stockCode()));
            }
            List<Row> picked = pool.subList(0, Math.min(topN, pool.size()));

            double avg = picked.stream().mapToDouble(Row::returnPct).average().orElse(0);
            double win = picked.stream().filter(r -> r.returnPct() > 0).count() * 100.0 / picked.size();
            double excess = avg - universeAvg;
            Double medTrailing = picked.get(0).trailingPct() == null ? null
                    : round(median(picked.stream().mapToDouble(Row::trailingPct).sorted().toArray()));

            years.add(new PortfolioYear(e.getKey(), picked.size(), round(avg), round(universeAvg),
                    round(excess), round(win), medTrailing));
            excessSum += excess;
            if (excess > 0) positive++;
        }

        double avgExcess = years.isEmpty() ? 0 : excessSum / years.size();
        return new PortfolioSim(topN, scoreMin, tieBreak, years, round(avgExcess), positive, years.size());
    }

    private static List<String> caveats() {
        return List.of(
                "생존편향: 유니버스가 '오늘 기준 시총 상위 1,500'이라 절대 수익률은 낙관 상한이다. "
                        + "다만 편향이 상·하위 버킷에 공통으로 걸리므로 스프레드(차이)는 유효하다 — 절대값이 아니라 lift/스프레드를 볼 것.",
                "배당 미반영: 소스가 수정주가(가격 조정)라 배당 재투자가 빠진다. 고배당 우량주가 구조적으로 불리하게 평가된다.",
                "F-Score 7개 축소판 — 현금흐름 2개(CFO>0, CFO>순이익)가 빠져 '이익의 질'을 못 본다. "
                        + "발생액 항목이 원본에서 회계 부풀리기를 잡는 핵심이라, 상위 버킷에 분식 종목이 섞일 수 있다.",
                "연도별 부호를 반드시 볼 것 — 전체 스프레드가 양수여도 특정 1~2년이 만든 것이면 채택 금지(이 시스템이 반복해서 겪은 클러스터 함정).",
                "portfolio(FALLEN) vs portfolioControl(RISEN)을 반드시 함께 볼 것 — 같은 F-Score 풀에서 2차 정렬 방향만 "
                        + "뒤집은 내장 대조군이다. FALLEN이 RISEN보다 나아야 '떨어진 것을 산다'는 전제가 지지되고, "
                        + "RISEN이 나으면 전제가 반증된 것이다(모멘텀이 이긴다).",
                "30종목 동일가중 기준이다. 종목 수를 줄이면 스프레드가 실현되지 않는다 — 연 3~5%p 신호는 "
                        + "3종목 포트폴리오 변동성(~27%)에 완전히 묻힌다.");
    }

    /** 사업연도 Y → 사용 개시일 (Y+1)년 5월 1일. */
    private static LocalDate basisDate(String businessYear) {
        try {
            return LocalDate.of(Integer.parseInt(businessYear) + 1, BASIS_MONTH, 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 정렬된 일봉에서 date 이상인 첫 거래일 인덱스. 없으면 null(= horizon 끝이 데이터 밖). */
    static Integer firstOnOrAfter(List<DailyPrice> prices, String date) {
        int lo = 0, hi = prices.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (prices.get(mid).getBusinessDate().compareTo(date) >= 0) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found < 0 ? null : found;
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) return 0;
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 진단용 — 특정 종목·연도의 기준별 충족 내역. */
    public Map<String, Object> explain(String stockCode, String businessYear) {
        Map<String, Object> m = new LinkedHashMap<>();
        factRepository.findByBusinessYear(businessYear).stream()
                .filter(f -> f.getStockCode().equals(stockCode))
                .findFirst()
                .ifPresent(f -> {
                    FinancialScore.Result r = FinancialScore.of(f);
                    m.put("stockCode", stockCode);
                    m.put("businessYear", businessYear);
                    m.put("fsDiv", f.getFsDiv());
                    m.put("score", r.score());
                    m.put("evaluated", r.evaluated());
                    String[] names = {"ROA>0", "ΔROA>0", "레버리지↓", "유동비율↑", "신주발행없음", "영업이익률↑", "자산회전율↑"};
                    Map<String, Boolean> detail = new LinkedHashMap<>();
                    for (int i = 0; i < names.length; i++) detail.put(names[i], r.detail()[i]);
                    m.put("detail", detail);
                });
        if (m.isEmpty()) m.put("error", "해당 (종목,연도) 재무 없음");
        return m;
    }
}
