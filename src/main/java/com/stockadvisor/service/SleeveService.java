package com.stockadvisor.service;

import com.stockadvisor.domain.SleeveSelection;
import com.stockadvisor.repository.SleeveSelectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 멀티데이 슬리브 — <b>섀도우 포워드 검증</b>. 실주문은 내지 않는다.
 *
 * <p><b>왜 이것만 남았나</b>(2026-08-28): 선정 축 8개 × 방향 2 × 보유주기 3 × 구간 2 = 96조합을 돌려
 * <b>`HIGH_52W_HIGH`(52주 최고가 근접 종목 매수) 하나만</b> 살아남았다 — 탐색·holdout × 1/3/6개월
 * <b>6개 창 전부 초과수익 양수</b>이고 반대 방향은 6개 창 전부 음수였다. 문헌의 52-week high momentum과 같은 축이다.</p>
 *
 * <p>⚠️ <b>그런데 holdout을 3회 소진했다</b> — 더는 백테스트로 검증할 수 없다. 남은 수단이 실시간 포워드뿐이라
 * 이 서비스가 존재한다. 여기서 쌓이는 기록만이 앞으로 유일하게 오염되지 않은 증거다.</p>
 *
 * <p>⚠️ <b>기존 인트라데이 파이프라인({@code StrategyEvaluator}·{@code TradingStrategy})을 쓰지 않는다.</b>
 * 그쪽은 거래량 급증 게이트·종목당 1포지션·분 단위 청산을 전제하는데 이 슬리브는 전부 어긋난다.
 * 공유하는 것은 {@code daily_price} 하나뿐이다.</p>
 *
 * <p>⚠️ <b>절대 성과가 아니라 벤치마크 대비로 읽을 것</b> — 백테스트에서 이 축은 횡보장(2016~2022,
 * KOSPI 연 2.13%)에선 지수를 크게 이겼지만 강세장(2023~2026, KOSPI 연 35.65%)에선 크게 졌다.
 * 그래서 리포트가 유니버스 동일가중 벤치마크를 항상 함께 낸다.</p>
 */
@Service
public class SleeveService {

    private static final Logger log = LoggerFactory.getLogger(SleeveService.class);
    public static final String STRATEGY = "HIGH_52W_HIGH";

    private final SleeveSelectionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final int topN;
    private final int holdMonths;
    private final long minPriceKrw;
    private final long minTurnoverKrw;
    private final double roundTripCostPct;

    public SleeveService(SleeveSelectionRepository repository,
                         JdbcTemplate jdbcTemplate,
                         @Value("${stockadvisor.sleeve.enabled:false}") boolean enabled,
                         @Value("${stockadvisor.sleeve.top-n:30}") int topN,
                         @Value("${stockadvisor.sleeve.hold-months:3}") int holdMonths,
                         @Value("${stockadvisor.sleeve.min-price-krw:1000}") long minPriceKrw,
                         @Value("${stockadvisor.sleeve.min-turnover-krw:500000000}") long minTurnoverKrw,
                         @Value("${stockadvisor.cost.round-trip-pct:0.22}") double roundTripCostPct) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.topN = topN;
        this.holdMonths = holdMonths;
        this.minPriceKrw = minPriceKrw;
        this.minTurnoverKrw = minTurnoverKrw;
        this.roundTripCostPct = roundTripCostPct;
    }

    /** 한 종목의 최근 구간 일봉(52주 고가·유동성 계산에 필요한 만큼만 로드). */
    private record Recent(int[] dates, int[] high, int[] close, long[] volume) {}

    /** @param axisValue 종가 ÷ 52주 최고가. 1.0에 가까울수록 신고가 근접 */
    public record Candidate(String stockCode, long price, double axisValue, double turnover) {}

    public record RebalanceReport(String strategy, String rebalanceDate, int universeSize, int picked,
                                  boolean persisted, String skipReason, List<Candidate> picks) {}

    /**
     * @param avgReturnPct     선정 종목 동일가중 수익률(비용 차감)
     * @param benchmarkPct     같은 기간 유니버스 동일가중(비용 차감) — <b>이게 판정 기준이다</b>
     * @param closed           계획 보유기간이 지나 성과가 확정됐나
     */
    public record CycleResult(String rebalanceDate, String asOfDate, int holdings, boolean closed,
                              double avgReturnPct, double benchmarkPct, double excessPct,
                              double winRatePct, double bestPct, double worstPct) {}

    public record SleeveReport(String strategy, int cycles, double avgExcessPct, int cyclesPositive,
                               List<CycleResult> results, List<String> notes) {}

    // ── 리밸런싱 ────────────────────────────────────────

    /**
     * 리밸런싱 실행 — 선정 결과를 <b>기록</b>한다(주문 없음).
     *
     * <p>⚠️ 같은 날 재실행은 무시한다(멱등) — 스케줄과 수동 트리거가 겹쳐도 표본이 중복되지 않게.</p>
     */
    @Transactional
    public RebalanceReport rebalance(boolean dryRun) {
        String latestData = jdbcTemplate.queryForObject(
                "select max(business_date) from daily_price", String.class);
        if (latestData == null) {
            return new RebalanceReport(STRATEGY, null, 0, 0, false, "일봉 데이터 없음", List.of());
        }
        List<Candidate> picks = selectAt(latestData);
        int universeSize = lastUniverseSize;

        if (!dryRun && !repository.findByStrategyAndRebalanceDate(STRATEGY, latestData).isEmpty()) {
            return new RebalanceReport(STRATEGY, latestData, universeSize, picks.size(), false,
                    "이미 기록된 리밸런싱 일자(멱등)", picks);
        }
        if (!dryRun) {
            List<SleeveSelection> rows = new ArrayList<>();
            for (int i = 0; i < picks.size(); i++) {
                Candidate c = picks.get(i);
                rows.add(new SleeveSelection(latestData, STRATEGY, c.stockCode(), i + 1,
                        c.price(), c.axisValue(), holdMonths));
            }
            repository.saveAll(rows);
            log.info("[슬리브] 리밸런싱 기록 {} · {}종목(유니버스 {})", latestData, rows.size(), universeSize);
        }
        return new RebalanceReport(STRATEGY, latestData, universeSize, picks.size(), !dryRun, null, picks);
    }

    private int lastUniverseSize;

    /** 기준일의 선정 결과. 유동성·가격 필터는 <b>라이브와 같은 기준</b>이라야 실행 가능한 결과가 나온다. */
    public List<Candidate> selectAt(String asOfDate) {
        Map<String, Recent> series = loadRecent(asOfDate, 13);
        List<Candidate> cands = new ArrayList<>();
        for (Map.Entry<String, Recent> e : series.entrySet()) {
            Candidate c = candidate(e.getKey(), e.getValue(), minPriceKrw, minTurnoverKrw);
            if (c != null) cands.add(c);
        }
        lastUniverseSize = cands.size();
        cands.sort(Comparator.comparingDouble(Candidate::axisValue).reversed());   // 신고가 근접 순
        return cands.size() <= topN ? cands : new ArrayList<>(cands.subList(0, topN));
    }

    /**
     * 한 종목의 후보 판정(<b>순수 정적</b>). 조건 미달이면 null.
     *
     * <p>⚠️ 유동성·가격 필터가 <b>결과의 진위를 가른다</b> — 이걸 빼고 백테스트했을 때
     * "단기 반전 연 49% 초과"라는 허수가 나왔고, 필터를 넣자 −0.45%로 붕괴했다(동전주 호가 튐).</p>
     */
    static Candidate candidate(String code, Recent r, long minPrice, long minTurnover) {
        int n = r.dates().length;
        if (n < 200) return null;                       // 52주 고가를 말하려면 최소 1년치가 있어야 한다
        int last = n - 1;
        long price = r.close()[last];
        if (price <= 0 || price < minPrice) return null;

        long peak = 0;
        for (int i = 0; i < n; i++) if (r.high()[i] > peak) peak = r.high()[i];
        if (peak <= 0) return null;

        // 직전 20거래일 평균 거래대금 — 라이브 유동성 필터와 같은 기준
        int from = Math.max(0, last - 19);
        double turnover = 0;
        for (int i = from; i <= last; i++) turnover += (double) r.close()[i] * r.volume()[i];
        turnover /= (last - from + 1);
        if (minTurnover > 0 && turnover < minTurnover) return null;

        return new Candidate(code, price, (double) price / peak, turnover);
    }

    // ── 성과 리포트 ─────────────────────────────────────

    /** 기록된 사이클별 성과. 성과는 저장하지 않고 매번 {@code daily_price}에서 재계산한다. */
    public SleeveReport report() {
        List<SleeveSelection> all = repository.findByStrategyOrderByRebalanceDateAscRankNoAsc(STRATEGY);
        if (all.isEmpty()) {
            return new SleeveReport(STRATEGY, 0, 0, 0, List.of(), notes());
        }
        String latestData = jdbcTemplate.queryForObject(
                "select max(business_date) from daily_price", String.class);

        Map<String, List<SleeveSelection>> byCycle = new LinkedHashMap<>();
        for (SleeveSelection s : all) {
            byCycle.computeIfAbsent(s.getRebalanceDate(), k -> new ArrayList<>()).add(s);
        }

        List<CycleResult> results = new ArrayList<>();
        double excessSum = 0;
        int positive = 0;
        for (Map.Entry<String, List<SleeveSelection>> e : byCycle.entrySet()) {
            String start = e.getKey();
            int hold = e.getValue().get(0).getHoldMonths();
            String plannedEnd = LocalDate.parse(start, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                    .plusMonths(hold).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            boolean closed = latestData != null && latestData.compareTo(plannedEnd) >= 0;
            String asOf = closed ? plannedEnd : latestData;

            Map<String, Long> endPrices = pricesAsOf(
                    e.getValue().stream().map(SleeveSelection::getStockCode).toList(), asOf);

            List<Double> rets = new ArrayList<>();
            for (SleeveSelection s : e.getValue()) {
                Long end = endPrices.get(s.getStockCode());
                if (end == null || s.getEntryPrice() <= 0) continue;
                rets.add((double) (end - s.getEntryPrice()) / s.getEntryPrice() * 100 - roundTripCostPct);
            }
            if (rets.isEmpty()) continue;

            double avg = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double bench = universeReturn(start, asOf);
            double excess = avg - bench;
            results.add(new CycleResult(start, asOf, rets.size(), closed,
                    round(avg), round(bench), round(excess),
                    round(rets.stream().filter(v -> v > 0).count() * 100.0 / rets.size()),
                    round(rets.stream().mapToDouble(Double::doubleValue).max().orElse(0)),
                    round(rets.stream().mapToDouble(Double::doubleValue).min().orElse(0))));
            excessSum += excess;
            if (excess > 0) positive++;
        }
        return new SleeveReport(STRATEGY, results.size(),
                results.isEmpty() ? 0 : round(excessSum / results.size()), positive, results, notes());
    }

    private List<String> notes() {
        return List.of(
                "섀도우 기록이다 — 실주문과 무관하다.",
                "판정은 절대 수익이 아니라 excessPct(유니버스 동일가중 대비)로 할 것. "
                        + "백테스트상 이 축은 횡보장(KOSPI 연 2.13%)에선 지수를 크게 이기고 "
                        + "강세장(KOSPI 연 35.65%)에선 크게 졌다 — 절대값은 국면이 지배한다.",
                "holdout을 3회 소진해 백테스트로는 더 검증할 수 없다. 여기 쌓이는 기록만이 오염되지 않은 증거다.",
                "사이클이 최소 8~10개(2~3년) 쌓이기 전에는 채택/기각 판정을 하지 말 것 — "
                        + "3개월 보유라 표본이 느리게 쌓인다.");
    }

    /** 같은 구간 유니버스(동일 필터) 동일가중 수익률 — 판정의 기준선. */
    private double universeReturn(String start, String end) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select a.stock_code, a.close_price s, b.close_price e "
                        + "from daily_price a join daily_price b on b.stock_code = a.stock_code "
                        + "where a.business_date = ? and b.business_date = ? and a.close_price >= ?",
                start, end, minPriceKrw);
        if (rows.isEmpty()) return 0;
        double sum = 0;
        for (Map<String, Object> r : rows) {
            double s = ((Number) r.get("s")).doubleValue();
            double e = ((Number) r.get("e")).doubleValue();
            if (s > 0) sum += (e - s) / s * 100 - roundTripCostPct;
        }
        return round(sum / rows.size());
    }

    private Map<String, Long> pricesAsOf(List<String> codes, String date) {
        Map<String, Long> out = new HashMap<>();
        if (codes.isEmpty() || date == null) return out;
        String in = String.join(",", codes.stream().map(c -> "'" + c.replace("'", "") + "'").toList());
        jdbcTemplate.query(
                "select stock_code, close_price from daily_price "
                        + "where stock_code in (" + in + ") and business_date = "
                        + "(select max(business_date) from daily_price d2 "
                        + " where d2.stock_code = daily_price.stock_code and d2.business_date <= ?)",
                rs -> { out.put(rs.getString(1), rs.getLong(2)); }, date);
        return out;
    }

    private Map<String, Recent> loadRecent(String asOfDate, int months) {
        String from = LocalDate.parse(asOfDate, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                .minusMonths(months).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        Map<String, List<long[]>> buf = new HashMap<>();
        jdbcTemplate.query(
                "select stock_code, business_date, high_price, close_price, volume from daily_price "
                        + "where business_date between ? and ? order by stock_code, business_date",
                rs -> {
                    buf.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new long[]{Long.parseLong(rs.getString(2)), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
                }, from, asOfDate);

        Map<String, Recent> out = new HashMap<>();
        for (Map.Entry<String, List<long[]>> e : buf.entrySet()) {
            List<long[]> v = e.getValue();
            int[] d = new int[v.size()], h = new int[v.size()], c = new int[v.size()];
            long[] vol = new long[v.size()];
            for (int i = 0; i < v.size(); i++) {
                d[i] = (int) v.get(i)[0];
                h[i] = (int) v.get(i)[1];
                c[i] = (int) v.get(i)[2];
                vol[i] = v.get(i)[3];
            }
            out.put(e.getKey(), new Recent(d, h, c, vol));
        }
        return out;
    }

    // ── 스케줄 ──────────────────────────────────────────

    /**
     * 평일 장 마감 후 점검 — 계획 보유기간이 지났으면 리밸런싱한다.
     *
     * <p>⚠️ 여기 두는 이유는 {@code SignalScheduler}(실매매 경로)를 건드리지 않기 위해서다.
     * 이 잡은 읽기 + 새 테이블 쓰기뿐이라 라이브 매매에 영향이 없다.</p>
     */
    @Scheduled(cron = "${stockadvisor.sleeve.cron:0 10 17 * * MON-FRI}")
    public void scheduledRebalance() {
        if (!enabled) return;
        try {
            String latest = repository.findLatestRebalanceDate(STRATEGY);
            if (latest != null) {
                LocalDate next = LocalDate.parse(latest, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                        .plusMonths(holdMonths);
                if (LocalDate.now().isBefore(next)) return;   // 아직 보유 중
            }
            rebalance(false);
        } catch (Exception e) {
            log.warn("[슬리브] 스케줄 리밸런싱 실패(무시): {}", e.toString());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
