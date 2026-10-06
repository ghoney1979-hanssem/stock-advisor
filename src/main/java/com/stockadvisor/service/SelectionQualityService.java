package com.stockadvisor.service;

import com.stockadvisor.domain.SelectionPrereg;
import com.stockadvisor.repository.SelectionPreregRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 선정 품질 측정 — <b>"고른 종목이 시장보다 나았나"</b>를 청산 규칙과 분리해 잰다(2026-10-06 코드화).
 *
 * <p>진입분과 대조군(전략이 거른 후보) 모두에 대해 <b>진입일 종가 → D+5/10/15 종가</b> 수익에서
 * 같은 날 유니버스 동일가중(가격 ≥1,000원·거래대금 ≥5억) 수익을 뺀 초과수익(gross)을 붙인다.
 * {@code daily_price}로 계산하므로 대조군에도 멀티데이 반사실이 생긴다 — 그동안 "대조군엔 일봉 마크가 없어
 * multiday 비교 불가"였던 공백을 이 방식이 메운다. 2026-10-01 스크립트({@code scripts/selection-quality/export.sql})의 이식이다.</p>
 *
 * <p>두 기능: ① {@link #explore} — 표본 내 탐색(전략별 진입/거른 것 비교, feature 3분위 상−하) ②
 * {@link #check} — 사전등록 규칙의 <b>표본 밖</b> 판정. 탐색→등록→대기→판정을 매달 반복하는 루프의 도구다.</p>
 *
 * <p>⚠️ 초과수익은 <b>선정 기준 보정</b>의 판단 지표다(사용자 원칙 2026-10-06). 살지 말지는 절대수익으로 본다.</p>
 */
@Service
public class SelectionQualityService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int[] HORIZONS = {5, 10, 15};
    /** 탐색 3분위 계산의 최소 표본(전략별 진입분). */
    static final int MIN_TERCILE_N = 30;

    /** feature 키 → trade_outcome 컬럼. {@code crowd}는 아래 {@link #crowdScore}로 계산되는 복합 점수다. */
    public static final Map<String, String> FEATURES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("chg", "entry_change_rate");
        m.put("vr", "entry_volume_ratio");
        m.put("cap", "entry_market_cap");
        m.put("rec", "entry_rec_score");
        m.put("r5", "entry_ret5d_pct");
        m.put("dh", "entry_dist_high_pct");
        m.put("atr", "entry_atr_pct");
        m.put("ex", "entry_exec_strength");
        m.put("nc", "entry_news_cnt_1h");
        m.put("fr", "entry_frgn_ntby_ratio");
        m.put("og", "entry_orgn_ntby_ratio");
        m.put("obi", "entry_obi5");
        m.put("gap", "entry_gap_pct");
        m.put("br", "entry_market_breadth_pct");
        m.put("m30", "entry_index_mom30");
        m.put("per", "entry_per");
        m.put("pbr", "entry_pbr");
        FEATURES = java.util.Collections.unmodifiableMap(m);
    }
    public static final String CROWD = "crowd";

    private final JdbcTemplate jdbcTemplate;
    private final SelectionPreregRepository preregRepository;
    private final Set<String> inverseCodes;
    private final long minPriceKrw;
    private final long minTurnoverKrw;

    public SelectionQualityService(JdbcTemplate jdbcTemplate,
                                   SelectionPreregRepository preregRepository,
                                   @Value("${stockadvisor.inverse-codes:114800,251340}") List<String> inverseCodes,
                                   @Value("${stockadvisor.sleeve.min-price-krw:1000}") long minPriceKrw,
                                   @Value("${stockadvisor.sleeve.min-turnover-krw:500000000}") long minTurnoverKrw) {
        this.jdbcTemplate = jdbcTemplate;
        this.preregRepository = preregRepository;
        this.inverseCodes = Set.copyOf(inverseCodes);
        this.minPriceKrw = minPriceKrw;
        this.minTurnoverKrw = minTurnoverKrw;
    }

    // ── 데이터 ─────────────────────────────────────────────────────────────────────

    /** 한 표본. {@code x}는 D+5/10/15 초과수익(gross, %p) — 미도래면 null. */
    public record Row(long id, String strategy, boolean control, String reject, String alertDate,
                      Map<String, Double> f, Double[] x) {
        Double x(int horizon) {
            for (int i = 0; i < HORIZONS.length; i++) if (HORIZONS[i] == horizon) return x[i];
            throw new IllegalArgumentException("horizon " + horizon);
        }

        Double feature(String key) {
            return f.get(key);
        }
    }

    /**
     * "관심 집중도" 복합 점수(0~5) — 단일축 연구 7건이 같은 방향("이미 관심받은 종목을 사면 진다")을 가리킨 지표들을
     * <b>고정 임계</b>로 이진화해 합산한다. 임계는 daily-analysis 스킬 4-A 템플릿 그대로(튜닝 금지 — 임계 선택도 자유도다).
     * 미태깅(null)은 0점(템플릿의 SQL {@code case when}과 같은 처리).
     */
    static double crowdScore(Map<String, Double> f) {
        int s = 0;
        if (ge(f.get("vr"), 8)) s++;
        if (ge(f.get("chg"), 5)) s++;
        if (ge(f.get("nc"), 3)) s++;
        double flow = (f.get("fr") == null ? 0 : f.get("fr")) + (f.get("og") == null ? 0 : f.get("og"));
        if (flow >= 2) s++;
        if (ge(f.get("ex"), 150)) s++;
        return s;
    }

    private static boolean ge(Double v, double cut) {
        return v != null && v >= cut;
    }

    /** {@code since~until} 진입분+대조군 로드(인버스 ETF 제외). */
    List<Row> load(String since, String until) {
        StringBuilder cols = new StringBuilder();
        for (var e : FEATURES.entrySet()) cols.append(", o.").append(e.getValue()).append(" f_").append(e.getKey());
        String sql = """
                with r as materialized (
                  select stock_code, business_date, close_price, volume,
                         row_number() over (partition by stock_code order by business_date) rn
                  from daily_price where business_date >= ?),
                u as materialized (
                  select b.business_date d, f.rn - b.rn k,
                         avg((f.close_price - b.close_price) * 100.0 / b.close_price) ret
                  from r b join r f on f.stock_code = b.stock_code and f.rn - b.rn in (5, 10, 15)
                  where b.business_date >= ? and b.business_date <= ?
                    and b.close_price >= ? and b.close_price::numeric * b.volume >= ?
                  group by 1, 2)
                select o.id, o.strategy, o.stock_code, o.control_sample, o.reject_reason, o.alert_date%s,
                       b.close_price c0, f5.close_price c5, f10.close_price c10, f15.close_price c15,
                       u5.ret u5, u10.ret u10, u15.ret u15
                from trade_outcome o
                join r b on b.stock_code = o.stock_code and b.business_date = o.alert_date
                left join r f5 on f5.stock_code = o.stock_code and f5.rn = b.rn + 5
                left join r f10 on f10.stock_code = o.stock_code and f10.rn = b.rn + 10
                left join r f15 on f15.stock_code = o.stock_code and f15.rn = b.rn + 15
                left join u u5 on u5.d = o.alert_date and u5.k = 5
                left join u u10 on u10.d = o.alert_date and u10.k = 10
                left join u u15 on u15.d = o.alert_date and u15.k = 15
                where o.alert_date >= ? and o.alert_date <= ?
                """.formatted(cols);
        // 인버스는 지수 하락이 곧 수익이라 유니버스 차감이 의미를 뒤집는다 — toRow가 null로 걸러낸다(export.sql과 동일).
        List<Row> rows = new ArrayList<>(jdbcTemplate.query(sql, (rs, i) -> toRow(rs),
                since, since, until, minPriceKrw, minTurnoverKrw, since, until));
        rows.removeIf(r -> r == null);
        return rows;
    }

    private Row toRow(ResultSet rs) throws SQLException {
        if (inverseCodes.contains(rs.getString("stock_code"))) return null;
        Map<String, Double> f = new HashMap<>();
        for (String k : FEATURES.keySet()) f.put(k, dbl(rs, "f_" + k));
        f.put(CROWD, crowdScore(f));
        Double c0 = dbl(rs, "c0");
        Double[] x = new Double[HORIZONS.length];
        for (int i = 0; i < HORIZONS.length; i++) {
            int h = HORIZONS[i];
            x[i] = excess(c0, dbl(rs, "c" + h), dbl(rs, "u" + h));
        }
        return new Row(rs.getLong("id"), rs.getString("strategy"), rs.getBoolean("control_sample"),
                rs.getString("reject_reason") == null ? "" : rs.getString("reject_reason"),
                rs.getString("alert_date"), f, x);
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).doubleValue();
    }

    /** 진입일 종가 대비 D+k 종가 수익 − 같은 날 유니버스 D+k 수익(%p). 하나라도 없으면 null(추정하지 않는다). */
    static Double excess(Double c0, Double ck, Double universe) {
        if (c0 == null || ck == null || universe == null || c0 <= 0) return null;
        return (ck / c0 - 1) * 100 - universe;
    }

    // ── 순수 계산 ───────────────────────────────────────────────────────────────────

    /** 매칭 키 — 같은 전략·같은 날끼리만 비교한다(전략 1개면 '같은 날'과 같다). */
    static String key(Row r) {
        return r.strategy() + "|" + r.alertDate();
    }

    /** 같은 키(전략·날)에 양쪽 표본이 다 있는 날만 → (hi 평균 − lo 평균). 국면·시장 드리프트가 상쇄된다. */
    static Map<String, Double> dayDiffs(List<Row> lo, List<Row> hi, int horizon) {
        Map<String, List<Double>> a = group(lo, horizon), b = group(hi, horizon);
        Map<String, Double> out = new TreeMap<>();
        for (var e : a.entrySet()) {
            List<Double> hv = b.get(e.getKey());
            if (hv != null) out.put(e.getKey(), mean(hv) - mean(e.getValue()));
        }
        return out;
    }

    private static Map<String, List<Double>> group(List<Row> rows, int horizon) {
        Map<String, List<Double>> m = new HashMap<>();
        for (Row r : rows) {
            Double v = r.x(horizon);
            if (v != null) m.computeIfAbsent(key(r), k -> new ArrayList<>()).add(v);
        }
        return m;
    }

    static double mean(List<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return s / v.size();
    }

    /** 표본 밖 판정 결과. {@code verdict}: PASS / FAIL / 판정보류. */
    public record Judgement(String verdict, Double diffPct, Double looMin, Double looMax,
                            int sameSignDays, int matchedDays) {}

    /**
     * 사전등록 판정(2026-10-01 기준, {@code prereg_check.py}와 동일): ① 매칭 거래일 ≥ minDays
     * ② 평균 부호가 표본 내와 같고 크기 ≥ 표본 내의 1/3 ③ 하루씩 빼도(LOO) 부호 유지 ④ 같은 부호인 날이 과반.
     */
    static Judgement judge(Map<String, Double> dd, double inSample, int minDays) {
        int n = dd.size();
        if (n < Math.max(minDays, 2)) return new Judgement("판정보류", null, null, null, 0, n);
        double m = mean(new ArrayList<>(dd.values()));
        double[] r = looRange(dd);
        double lo = r[0], hi = r[1];
        boolean pos = inSample > 0;
        int same = 0;
        for (double v : dd.values()) if ((v > 0) == pos) same++;
        boolean looOk = pos ? lo > 0 : hi < 0;
        boolean ok = (m > 0) == pos && Math.abs(m) >= Math.abs(inSample) / 3 && looOk && same * 2 > n;
        return new Judgement(ok ? "PASS" : "FAIL", round2(m), round2(lo), round2(hi), same, n);
    }

    /** 오름차순 정렬된 값의 분위수(최근접 순위). */
    static double quantile(List<Double> sorted, double q) {
        int idx = (int) Math.floor(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }

    // ── ① 탐색 ─────────────────────────────────────────────────────────────────────

    public record Spread(String strategy, String feature, double loCut, double hiCut, int nLo, int nHi,
                         int matchedDays, Double diffX5, Double diffX10, Double diffX15,
                         Double looMin, Double looMax, int sameSignDays, boolean stable) {}

    public record ReasonEdge(String reason, int n, Double avgExcess, int matchedDays, Double edgeEnteredMinusReason) {}

    public record StrategyQuality(String strategy, int enteredN, int enteredMatured, int enteredDays,
                                  Double excessX5, Double excessX10, Double excessX15, Double excessExTopDay,
                                  List<ReasonEdge> rejected, List<Spread> spreads) {}

    public record ExploreReport(String since, String until, int horizon, int rows, int matured,
                                List<StrategyQuality> strategies, Spread crowdPooled, List<String> caveats) {}

    /**
     * 표본 내 탐색. 전략별로 ① 진입분 초과수익 ② 거른 사유별 같은 날 매칭 edge(진입−거른 것, +면 필터 유효)
     * ③ feature 3분위 상−하(같은 날 매칭, 3지평 + LOO) ④ 복합 점수 crowd(고정 컷 ≥2 vs 0, 전 전략 풀).
     */
    public ExploreReport explore(String since, String until, int horizon) {
        String to = until == null || until.isBlank() ? today() : until;
        List<Row> rows = load(since, to);
        int matured = (int) rows.stream().filter(r -> r.x(horizon) != null).count();
        Map<String, List<Row>> byStrategy = new TreeMap<>();
        for (Row r : rows) byStrategy.computeIfAbsent(r.strategy(), k -> new ArrayList<>()).add(r);

        List<StrategyQuality> out = new ArrayList<>();
        for (var e : byStrategy.entrySet()) {
            List<Row> entered = e.getValue().stream().filter(r -> !r.control()).toList();
            if (entered.isEmpty()) continue;
            out.add(strategyQuality(e.getKey(), entered,
                    e.getValue().stream().filter(Row::control).toList(), horizon));
        }
        List<Row> allEntered = rows.stream().filter(r -> !r.control()).toList();
        Spread crowd = spread("*", CROWD, allEntered, 0, 2, horizon);
        return new ExploreReport(since, to, horizon, rows.size(), matured, out, crowd, List.of(
                "표본 내 탐색이다 — 여기서 고른 컷은 채택 근거가 아니라 사전등록 후보다(POST /selection-prereg).",
                "feature 3분위 × 전략 × 3지평을 한꺼번에 보므로 다중검정이다. stable=true도 우연일 수 있다.",
                "초과수익은 gross(비용 미차감)이고 청산 규칙과 무관한 '고른 종목의 질'이다. 살지 말지는 절대 net으로 판단할 것.",
                "외인·기관 수급(fr·og)은 소급 태깅이라 최근 행은 비어 있을 수 있다 — 점검 전 backfill-investor-flow."));
    }

    private StrategyQuality strategyQuality(String strategy, List<Row> entered, List<Row> controls, int horizon) {
        List<Double> xs = new ArrayList<>();
        Map<String, double[]> byDay = new HashMap<>();
        for (Row r : entered) {
            Double v = r.x(horizon);
            if (v == null) continue;
            xs.add(v);
            double[] a = byDay.computeIfAbsent(r.alertDate(), k -> new double[2]);
            a[0]++;
            a[1] += v;
        }
        Double exTop = null;
        if (byDay.size() >= 2) {
            var top = byDay.entrySet().stream().max(Comparator.comparingDouble(en -> en.getValue()[1])).get();
            double s = 0;
            for (double v : xs) s += v;
            exTop = round2((s - top.getValue()[1]) / (xs.size() - top.getValue()[0]));
        }
        Map<String, List<Row>> byReason = new TreeMap<>();
        for (Row r : controls) byReason.computeIfAbsent(r.reject().isBlank() ? "(미상)" : r.reject(), k -> new ArrayList<>()).add(r);
        List<ReasonEdge> reasons = new ArrayList<>();
        for (var e : byReason.entrySet()) {
            List<Double> rv = e.getValue().stream().map(r -> r.x(horizon)).filter(v -> v != null).toList();
            Map<String, Double> dd = dayDiffs(e.getValue(), entered, horizon);   // 진입 − 거른 것
            reasons.add(new ReasonEdge(e.getKey(), e.getValue().size(), rv.isEmpty() ? null : round2(mean(rv)),
                    dd.size(), dd.isEmpty() ? null : round2(mean(new ArrayList<>(dd.values())))));
        }
        List<Spread> spreads = new ArrayList<>();
        for (String feat : featureKeys()) {
            List<Double> vals = new ArrayList<>();
            for (Row r : entered) if (r.feature(feat) != null && r.x(horizon) != null) vals.add(r.feature(feat));
            if (vals.size() < MIN_TERCILE_N) continue;
            vals.sort(Double::compare);
            double lo = quantile(vals, 1.0 / 3), hi = quantile(vals, 2.0 / 3);
            if (lo >= hi) continue;   // 값이 몰려 있어 3분위가 갈리지 않음
            Spread s = spread(strategy, feat, entered, lo, hi, horizon);
            if (s.matchedDays() >= 3) spreads.add(s);
        }
        spreads.sort(Comparator.comparing((Spread s) -> !s.stable())
                .thenComparing(s -> -Math.abs(s.diffX10() == null ? 0 : s.diffX10())));
        return new StrategyQuality(strategy, entered.size(), xs.size(), byDay.size(),
                avgOf(entered, 5), avgOf(entered, 10), avgOf(entered, 15), exTop, reasons, spreads);
    }

    private static List<String> featureKeys() {
        List<String> k = new ArrayList<>(FEATURES.keySet());
        k.add(CROWD);
        return k;
    }

    private static Double avgOf(List<Row> rows, int h) {
        List<Double> v = rows.stream().map(r -> r.x(h)).filter(x -> x != null).toList();
        return v.isEmpty() ? null : round2(mean(v));
    }

    /** lo(≤loCut) vs hi(≥hiCut) 같은 날 매칭 차이 — 3지평 + 주지평 LOO. stable = 3지평 부호 동일 AND LOO 부호 유지. */
    static Spread spread(String strategy, String feat, List<Row> entered, double loCut, double hiCut, int horizon) {
        List<Row> lo = new ArrayList<>(), hi = new ArrayList<>();
        for (Row r : entered) {
            Double v = r.feature(feat);
            if (v == null) continue;
            if (v <= loCut) lo.add(r);
            if (v >= hiCut) hi.add(r);
        }
        Double[] d = new Double[HORIZONS.length];
        for (int i = 0; i < HORIZONS.length; i++) {
            Map<String, Double> dd = dayDiffs(lo, hi, HORIZONS[i]);
            d[i] = dd.isEmpty() ? null : round2(mean(new ArrayList<>(dd.values())));
        }
        Map<String, Double> main = dayDiffs(lo, hi, horizon);
        double[] loo = looRange(main);
        Double dMain = main.isEmpty() ? null : mean(new ArrayList<>(main.values()));
        int same = 0;
        if (dMain != null) for (double v : main.values()) if ((v > 0) == (dMain > 0)) same++;
        boolean stable = loo != null && d[0] != null && d[1] != null && d[2] != null
                && Math.signum(d[0]) == Math.signum(d[1]) && Math.signum(d[1]) == Math.signum(d[2])
                && (dMain > 0 ? loo[0] > 0 : loo[1] < 0);
        return new Spread(strategy, feat, round2(loCut), round2(hiCut), lo.size(), hi.size(), main.size(),
                d[0], d[1], d[2], loo == null ? null : round2(loo[0]), loo == null ? null : round2(loo[1]),
                same, stable);
    }

    /** 하루씩 뺀 평균의 [최소, 최대]. 2일 미만이면 null. */
    static double[] looRange(Map<String, Double> dd) {
        int n = dd.size();
        if (n < 2) return null;
        double sum = 0;
        for (double v : dd.values()) sum += v;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double v : dd.values()) {
            double loo = (sum - v) / (n - 1);
            lo = Math.min(lo, loo);
            hi = Math.max(hi, loo);
        }
        return new double[]{lo, hi};
    }

    // ── ② 사전등록 ──────────────────────────────────────────────────────────────────

    public record RegisterRequest(String name, String strategy, String feature, Integer horizon,
                                  Double loCut, Double hiCut, Double inSampleDiffPct,
                                  String inSampleFrom, String inSampleTo, String oosFrom,
                                  Integer minDays, String action) {}

    /**
     * 규칙 등록 — 같은 이름이 있으면 거부한다(수정 불가가 설계). {@code oosFrom}을 비우면 <b>내일</b>부터 —
     * 등록 시점에 이미 존재하는 데이터는 표본 밖이 될 수 없다.
     */
    public SelectionPrereg register(RegisterRequest q) {
        if (q.name() == null || q.name().isBlank()) throw new IllegalArgumentException("name 필수");
        if (preregRepository.existsByName(q.name()))
            throw new IllegalArgumentException("이미 등록된 이름: " + q.name() + " — 사전등록 규칙은 수정할 수 없다. 새 이름으로 등록할 것");
        if (!FEATURES.containsKey(q.feature()) && !CROWD.equals(q.feature()))
            throw new IllegalArgumentException("알 수 없는 feature: " + q.feature());
        int h = q.horizon() == null ? 10 : q.horizon();
        if (h != 5 && h != 10 && h != 15) throw new IllegalArgumentException("horizon은 5/10/15");
        if (q.loCut() == null || q.hiCut() == null || q.loCut() > q.hiCut())
            throw new IllegalArgumentException("loCut ≤ hiCut 필요");
        if (q.inSampleDiffPct() == null || q.inSampleDiffPct() == 0)
            throw new IllegalArgumentException("inSampleDiffPct(표본 내 상위−하위, 0 아님) 필수");
        String oos = q.oosFrom() == null || q.oosFrom().isBlank()
                ? LocalDate.now(SEOUL).plusDays(1).format(YYYYMMDD) : q.oosFrom();
        if (q.inSampleTo() == null || q.inSampleTo().compareTo(oos) >= 0)
            throw new IllegalArgumentException("inSampleTo < oosFrom 이어야 한다(표본 내·밖 겹침 금지)");
        return preregRepository.save(new SelectionPrereg(q.name(), q.strategy() == null ? "*" : q.strategy(),
                q.feature(), h, q.loCut(), q.hiCut(), q.inSampleDiffPct(),
                q.inSampleFrom() == null ? "" : q.inSampleFrom(), q.inSampleTo(), oos,
                q.minDays() == null ? 8 : q.minDays(), q.action() == null ? "" : q.action()));
    }

    public record PreregResult(String name, String strategy, String feature, int horizon, double loCut, double hiCut,
                               double inSampleDiffPct, String oosFrom, String registeredAt,
                               int nLo, int nHi, Judgement judgement, String action) {}

    public record CheckReport(String asOf, int oosRowsMatured, List<PreregResult> rules, List<String> notes) {}

    /** 등록된 전 규칙을 각자의 표본 밖(진입일 ≥ oosFrom)으로 판정한다. */
    public CheckReport check() {
        List<SelectionPrereg> rules = preregRepository.findAllByOrderByIdAsc();
        String today = today();
        if (rules.isEmpty()) return new CheckReport(today, 0, List.of(), List.of("등록된 규칙 없음"));
        String earliest = rules.stream().map(SelectionPrereg::getOosFrom).min(String::compareTo).get();
        List<Row> rows = load(earliest, today).stream().filter(r -> !r.control()).toList();
        List<PreregResult> out = new ArrayList<>();
        int maturedMax = 0;
        for (SelectionPrereg p : rules) {
            Predicate<Row> in = r -> r.alertDate().compareTo(p.getOosFrom()) >= 0
                    && ("*".equals(p.getStrategy()) || p.getStrategy().equals(r.strategy()));
            List<Row> oos = rows.stream().filter(in).toList();
            maturedMax = Math.max(maturedMax, (int) oos.stream().filter(r -> r.x(p.getHorizon()) != null).count());
            List<Row> lo = new ArrayList<>(), hi = new ArrayList<>();
            for (Row r : oos) {
                Double v = r.feature(p.getFeature());
                if (v == null) continue;
                if (v <= p.getLoCut()) lo.add(r);
                if (v >= p.getHiCut()) hi.add(r);
            }
            Judgement j = judge(dayDiffs(lo, hi, p.getHorizon()), p.getInSampleDiffPct(), p.getMinDays());
            out.add(new PreregResult(p.getName(), p.getStrategy(), p.getFeature(), p.getHorizon(),
                    p.getLoCut(), p.getHiCut(), p.getInSampleDiffPct(), p.getOosFrom(),
                    p.getRegisteredAt().toString(), lo.size(), hi.size(), j, p.getAction()));
        }
        return new CheckReport(today, maturedMax, out, List.of(
                "판정 기준(사전등록): 매칭 거래일 ≥ minDays · 부호 동일 & 크기 ≥ 표본 내 1/3 · LOO 부호 유지 · 같은 부호 날 과반.",
                "PASS만 적용 검토. 결과를 보고 컷을 바꾸지 말 것 — 바꾸려면 새 이름으로 재등록하고 다시 기다린다.",
                "fr·og 규칙은 점검 전에 POST /backfill-investor-flow 를 먼저 돌릴 것(소급 태깅)."));
    }

    private static String today() {
        return LocalDate.now(SEOUL).format(YYYYMMDD);
    }
}
