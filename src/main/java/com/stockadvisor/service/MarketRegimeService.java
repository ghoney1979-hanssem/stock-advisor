package com.stockadvisor.service;

import com.stockadvisor.config.properties.MarketRegimeProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.VolatilityLevel;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.market.dto.KisMinuteCandleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 국면 엔진 (레이어 1). 지수 추종 ETF(프록시)의 일봉으로 시장의 <b>추세</b>(강세/중립/약세)와
 * <b>변동성</b>(저/중/고)을 판정한다. "하락장 리스크↓·상승장 수익↑"의 상위 입력 — 이후 레이어 2(국면조건부
 * 전략 배분)·레이어 3(국면연동 리스크)이 이 값을 소비한다.
 *
 * <p>추세: 종가 vs MA{maPeriod} + MA 기울기(최근 {slopeLookback}일). 변동성: 일간수익률 실현 표준편차(%).
 * 계산은 {@link #computeRegime}(순수 함수)로 분리해 KIS 없이 검증 가능. 일봉 기반이라 {refreshMinutes} TTL 캐시.</p>
 */
@Service
public class MarketRegimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeService.class);

    private final KisApiClient kisApiClient;
    private final MarketRegimeProperties props;

    private volatile Instant lastRefresh;

    // 장중 흐름(지수 프록시 최근 모멘텀) 캐시 — 스캔의 다수 종목이 시장별 1콜 재사용(TTL).
    private final Map<String, IntradayFlow> flowCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> flowAt = new ConcurrentHashMap<>();
    private static final long FLOW_TTL_SECONDS = 90;
    private volatile Map<String, MarketRegime> cache = Map.of();

    public MarketRegimeService(KisApiClient kisApiClient, MarketRegimeProperties props) {
        this.kisApiClient = kisApiClient;
        this.props = props;
    }

    /**
     * 시장 국면 스냅샷.
     *
     * @param trend         추세 국면, volatility 변동성 국면
     * @param close         최신 종가(프록시), ma 이동평균, maSlopePct MA 기울기(%), realizedVolPct 실현변동성(%)
     * @param samples       계산에 사용된 일봉 수, asOf 기준 영업일, available 데이터 충분 여부
     */
    public record MarketRegime(String market, String proxyCode, MarketTrend trend, VolatilityLevel volatility,
                               Double close, Double ma, Double maSlopePct, Double realizedVolPct,
                               int samples, String asOf, boolean available) {}

    // ── 국면 intraday 보정(2026-07-16) ── MA3 라벨은 후행이라 "BULL 라벨 + 당일 폭락"(7/13·7/16 실측 2회)에서
    // 게이트·노출상한이 확대된 채 폭락을 맞음. 당일 지수 등락률로 라벨을 즉시 보정:
    // 강등(빠르게, 지수 단독): 당일 ≤ −demote% → 1단계, ≤ −2×demote% → 2단계(BULL→BEAR).
    // 승격(보수적, 합의): 당일 ≥ +promote% AND 해당 시장 breadth 상승비율 ≥ 60%(신선) → 1단계만.
    // (비대칭 원칙: 리스크 축소는 빠르게, 확대는 느리게 — 7/10 breadth 88% 다음날 폭락 반례 반영.)
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.market-regime.intraday-demote-pct:2.0}")
    private double intradayDemotePct = 2.0;    // 0=강등 보정 비활성
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.market-regime.intraday-promote-pct:2.0}")
    private double intradayPromotePct = 2.0;   // 0=승격 보정 비활성
    private static final double PROMOTE_BREADTH_MIN = 60.0;   // 승격 합의용 breadth 상승비율 하한
    private static final long BREADTH_FRESH_MIN = 40;         // breadth 신선도(분)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketBreadthService breadthService;   // 승격 합의용 — 미주입(테스트)이면 승격 안 함(보수)
    void setBreadthService(MarketBreadthService s) { this.breadthService = s; }   // 테스트용

    private static final java.util.Map<String, String> INDEX_OF = java.util.Map.of("KOSPI", "0001", "KOSDAQ", "1001");

    /** 순수 보정 판정(테스트용 정적) — base 라벨을 당일 지수 등락·breadth 합의로 강등/승격. */
    static MarketTrend adjustTrend(MarketTrend base, Double dayChgPct, Double breadthAdvPct, boolean breadthFresh,
                                   double demotePct, double promotePct) {
        if (base == null || dayChgPct == null) return base;
        if (demotePct > 0 && dayChgPct <= -demotePct) {
            int steps = dayChgPct <= -2 * demotePct ? 2 : 1;
            return demote(base, steps);
        }
        if (promotePct > 0 && dayChgPct >= promotePct
                && breadthFresh && breadthAdvPct != null && breadthAdvPct >= PROMOTE_BREADTH_MIN) {
            return promote(base);
        }
        return base;
    }

    private static MarketTrend demote(MarketTrend t, int steps) {
        MarketTrend cur = t;
        for (int i = 0; i < steps; i++) {
            cur = switch (cur) { case BULL -> MarketTrend.NEUTRAL; default -> MarketTrend.BEAR; };
        }
        return cur;
    }

    private static MarketTrend promote(MarketTrend t) {
        return switch (t) { case BEAR -> MarketTrend.NEUTRAL; default -> MarketTrend.BULL; };
    }

    /** 시장의 당일 지수 등락률(서킷과 동일 소스, 60s 캐시). 미상이면 null(보정 생략). */
    private Double dayChangeOf(String market) {
        String idx = INDEX_OF.get(market);
        if (idx == null) return null;
        try {
            return kisApiClient.fetchIndexChangeRate(idx);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 반등일 판정(2026-07-22) — 당일 지수 급등(≥ minSurgePct)인데 기저(MA3, intraday 보정 前) 라벨이 아직 비강세.
     * "라벨이 못 따라온 V자 초입의 급반등일" — 순추세 계열이 갭 되돌림에 전멸한 날(7/15 −34,639 / 7/22 −81,672 실측).
     * 안정 강세장(기저 BULL)은 해당 없음. 판정 불가(데이터 없음)면 false(degrade open).
     */
    public boolean isReboundDay(String market, double minSurgePct) {
        refreshIfStale();
        MarketRegime base = cache.get(market);
        if (base == null || !base.available()) return false;
        // "지금" 등락률이 아니라 "당일 장중 고점" 기준 — 급등이 꺾여 +2% 아래로 내려온 뒤에도(fade 구간·오후)
        // 반등일 판정이 유지돼야 순추세 보류가 하루 종일 걸리고, 인버스 fade 확대(반등일 한정)도 작동한다.
        return isReboundDay(base.trend(), dayHighChangeOf(market), minSurgePct);
    }

    // 시장별 당일 지수 등락률 고점 추적(관측 시점 기반 — 1분 주기 호출로 충분). 날짜 바뀌면 리셋.
    private final Map<String, double[]> dayHighChg = new ConcurrentHashMap<>();   // market -> {epochDay, highPct}
    private Double dayHighChangeOf(String market) {
        Double chg = dayChangeOf(market);
        long today = LocalDate.now(ZoneId.of("Asia/Seoul")).toEpochDay();
        double[] cur = dayHighChg.get(market);
        if (cur == null || (long) cur[0] != today) cur = new double[]{today, Double.NEGATIVE_INFINITY};
        if (chg != null && chg > cur[1]) cur[1] = chg;
        dayHighChg.put(market, cur);
        return cur[1] == Double.NEGATIVE_INFINITY ? chg : cur[1];
    }

    /** 반등일 판정(순수) — 기저 비강세 + 당일 ≥ minSurgePct 급등. */
    static boolean isReboundDay(MarketTrend baseTrend, Double dayChgPct, double minSurgePct) {
        if (baseTrend == MarketTrend.BULL) return false;
        return dayChgPct != null && minSurgePct > 0 && dayChgPct >= minSurgePct;
    }

    /** intraday 보정 적용된 추세. base 미가용이면 그대로. */
    private MarketTrend adjustedTrendOf(String market, MarketRegime base) {
        if (base == null || !base.available()) return base == null ? null : base.trend();
        Double adv = null;
        boolean fresh = false;
        if (breadthService != null) {
            adv = breadthService.breadthPct(market);
            fresh = breadthService.isFresh(BREADTH_FRESH_MIN);
        }
        return adjustTrend(base.trend(), dayChangeOf(market), adv, fresh, intradayDemotePct, intradayPromotePct);
    }

    /** 특정 시장(KOSPI/KOSDAQ) 국면 — trend 는 intraday 보정 적용값. 데이터 없으면 NEUTRAL/MID 의 unavailable 스냅샷. */
    public MarketRegime regimeOf(String market) {
        refreshIfStale();
        MarketRegime r = cache.get(market);
        if (r == null) return unavailable(market, proxyOf(market));
        MarketTrend adjusted = adjustedTrendOf(market, r);
        if (adjusted == null || adjusted == r.trend()) return r;
        return new MarketRegime(r.market(), r.proxyCode(), adjusted, r.volatility(), r.close(), r.ma(),
                r.maSlopePct(), r.realizedVolPct(), r.samples(), r.asOf(), r.available());
    }

    /**
     * 전체 시장 추세 — KOSPI·KOSDAQ 종합(국면조건부 성과게이트의 단일 국면 입력).
     * 둘 다 미산출(데이터 부족/조회실패)이면 null → 게이트는 국면 무관 평가로 fallback.
     */
    public MarketTrend overallTrend() {
        MarketRegime k = regimeOf("KOSPI");    // intraday 보정 적용값
        MarketRegime q = regimeOf("KOSDAQ");
        MarketTrend kt = k.available() ? k.trend() : null;
        MarketTrend qt = q.available() ? q.trend() : null;
        return combineTrend(kt, qt);
    }

    /**
     * 종목 시장(KOSPI/KOSDAQ)의 추세 — 해당 시장 국면이 산출됐으면 그 추세, 아니면 전체(overall)로 fallback.
     * 종목 시장별 국면 매칭용(KOSDAQ 종목은 KOSDAQ 국면으로 판단).
     */
    public MarketTrend trendOf(String market) {
        if (market != null && !market.isBlank()) {
            MarketRegime r = regimeOf(market);
            if (r.available()) return r.trend();
        }
        return overallTrend();
    }

    /** 두 시장 추세를 종합(+1/0/-1 합산 부호). 한쪽 null이면 다른쪽, 둘 다 null이면 null. */
    public static MarketTrend combineTrend(MarketTrend a, MarketTrend b) {
        if (a == null) return b;
        if (b == null) return a;
        int score = trendScore(a) + trendScore(b);
        if (score > 0) return MarketTrend.BULL;
        if (score < 0) return MarketTrend.BEAR;
        return MarketTrend.NEUTRAL;
    }

    private static int trendScore(MarketTrend t) {
        return switch (t) {
            case BULL -> 1;
            case BEAR -> -1;
            case NEUTRAL -> 0;
        };
    }

    /** KOSPI·KOSDAQ 국면(가시화/관리 API용). */
    public List<MarketRegime> all() {
        refreshIfStale();
        List<MarketRegime> out = new ArrayList<>();
        out.add(regimeOf("KOSPI"));
        out.add(regimeOf("KOSDAQ"));
        return out;
    }

    private synchronized void refreshIfStale() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).toMinutes() < props.refreshMinutes()) {
            return;
        }
        refresh();
    }

    /** 프록시 일봉을 받아 국면 캐시를 갱신. 시장별 실패는 격리(해당 시장만 미산출). */
    public synchronized void refresh() {
        Map<String, MarketRegime> map = new LinkedHashMap<>();
        compute("KOSPI", props.kospiProxyCode(), map);
        compute("KOSDAQ", props.kosdaqProxyCode(), map);
        if (!map.isEmpty()) {
            cache = map;
            lastRefresh = Instant.now();
            log.info("시장 국면 갱신: {}", map.values().stream()
                    .map(r -> r.market() + "=" + r.trend().korean() + "/" + r.volatility().korean()).toList());
        }
    }

    private void compute(String market, String proxyCode, Map<String, MarketRegime> out) {
        try {
            KisDailyPriceResponse resp = kisApiClient.fetchDailyPrices(proxyCode);
            List<KisDailyPriceResponse.DailyPrice> rows = resp.output();
            if (rows == null || rows.isEmpty()) {
                out.put(market, unavailable(market, proxyCode));
                return;
            }
            // 응답은 최신일 우선 → 오래된→최신 순으로 뒤집어 시계열 구성
            List<Double> chrono = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0; i--) {
                Double c = parse(rows.get(i).closePrice());
                if (c != null && c > 0) chrono.add(c);
            }
            String asOf = rows.get(0).businessDate();
            out.put(market, computeRegime(market, proxyCode, chrono, asOf, props));
        } catch (Exception ex) {
            log.warn("시장 국면 계산 실패 [{}] proxy={}: {}", market, proxyCode, ex.getMessage());
            out.put(market, unavailable(market, proxyCode));
        }
    }

    /**
     * 종가 시계열(오래된→최신)로 국면을 계산하는 순수 함수.
     * 추세: 종가 vs MA + MA 기울기 / 변동성: 일간수익률 표준편차(%).
     */
    public static MarketRegime computeRegime(String market, String proxyCode, List<Double> closesChrono,
                                             String asOf, MarketRegimeProperties props) {
        int n = closesChrono.size();
        if (n < props.maPeriod()) {
            return new MarketRegime(market, proxyCode, MarketTrend.NEUTRAL, VolatilityLevel.MID,
                    n > 0 ? round2(closesChrono.get(n - 1)) : null, null, null, null, n, asOf, false);
        }
        double close = closesChrono.get(n - 1);
        double ma = avg(closesChrono, n - props.maPeriod(), n);

        // MA 기울기: slopeLookback 일 전 MA 대비. 데이터 부족하면 null.
        Double maSlopePct = null;
        int prevEnd = n - props.slopeLookback();
        if (prevEnd >= props.maPeriod()) {
            double maPrev = avg(closesChrono, prevEnd - props.maPeriod(), prevEnd);
            if (maPrev > 0) maSlopePct = (ma - maPrev) / maPrev * 100;
        }

        MarketTrend trend = classifyTrend(close, ma, maSlopePct);

        // 실현변동성: 최근 volPeriod 개 일간수익률(%)의 표준편차
        Double realizedVolPct = realizedVol(closesChrono, props.volPeriod());
        VolatilityLevel vol = classifyVol(realizedVolPct, props);

        return new MarketRegime(market, proxyCode, trend, vol,
                round2(close), round2(ma), round2(maSlopePct), round2(realizedVolPct), n, asOf, true);
    }

    private static MarketTrend classifyTrend(double close, double ma, Double slopePct) {
        boolean above = close >= ma;
        if (slopePct == null) {
            return above ? MarketTrend.BULL : MarketTrend.BEAR;   // 기울기 부족 시 위치만으로 약식 판정
        }
        if (above && slopePct > 0) return MarketTrend.BULL;
        if (!above && slopePct < 0) return MarketTrend.BEAR;
        return MarketTrend.NEUTRAL;   // 위치·기울기 엇갈림 = 횡보/전환
    }

    private static VolatilityLevel classifyVol(Double volPct, MarketRegimeProperties props) {
        if (volPct == null) return VolatilityLevel.MID;
        if (volPct >= props.volHighPct()) return VolatilityLevel.HIGH;
        if (volPct < props.volLowPct()) return VolatilityLevel.LOW;
        return VolatilityLevel.MID;
    }

    /** 최근 period 개 일간수익률(%)의 표본 표준편차. 데이터 부족 시 null. */
    private static Double realizedVol(List<Double> closes, int period) {
        int n = closes.size();
        if (n < period + 1) return null;
        List<Double> rets = new ArrayList<>();
        for (int i = n - period; i < n; i++) {
            double prev = closes.get(i - 1);
            if (prev > 0) rets.add((closes.get(i) - prev) / prev * 100);
        }
        if (rets.size() < 2) return null;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (rets.size() - 1);
        return Math.sqrt(var);
    }

    private static double avg(List<Double> list, int fromInclusive, int toExclusive) {
        double sum = 0;
        for (int i = fromInclusive; i < toExclusive; i++) sum += list.get(i);
        return sum / (toExclusive - fromInclusive);
    }

    private MarketRegime unavailable(String market, String proxyCode) {
        return new MarketRegime(market, proxyCode, MarketTrend.NEUTRAL, VolatilityLevel.MID,
                null, null, null, null, 0, null, false);
    }

    private String proxyOf(String market) {
        return "KOSDAQ".equals(market) ? props.kosdaqProxyCode() : props.kospiProxyCode();
    }

    /** 진입 시점 지수 장중 흐름 — 최근 10/60분 등락(%). "고점 대비 위치"가 아니라 "이전값 대비 방향/기울기". */
    public record IntradayFlow(Double mom10Pct, Double mom30Pct, Double mom60Pct, boolean available) {}

    /**
     * 지수(프록시 ETF) 장중 흐름 조회 — 프록시 분봉(당일치)에서 최근 10/60분 등락 계산.
     * {@value #FLOW_TTL_SECONDS}s 캐시(스캔의 다수 종목이 시장별 1콜만 재사용 → 부하 무시). 데이터 없으면 available=false.
     */
    public IntradayFlow intradayFlow(String market) {
        Instant now = Instant.now();
        Instant at = flowAt.get(market);
        IntradayFlow cached = flowCache.get(market);
        if (cached != null && at != null && Duration.between(at, now).getSeconds() < FLOW_TTL_SECONDS) {
            return cached;
        }
        IntradayFlow f = computeFlow(market);
        flowCache.put(market, f);
        flowAt.put(market, now);
        return f;
    }

    private IntradayFlow computeFlow(String market) {
        try {
            List<KisMinuteCandleResponse.Candle> candles = kisApiClient.fetchMinuteCandles(proxyOf(market)).output2();
            if (candles == null || candles.isEmpty()) return new IntradayFlow(null, null, null, false);
            return new IntradayFlow(momPct(candles, 10), momPct(candles, 30), momPct(candles, 60), true);
        } catch (Exception ex) {
            log.warn("지수 장중흐름 조회 실패 [{}] proxy={}: {}", market, proxyOf(market), ex.getMessage());
            return new IntradayFlow(null, null, null, false);
        }
    }

    /** 분봉(최신순, index 0=현재) 최근 lag분 등락(%). 표본 부족/무효면 null. */
    static Double momPct(List<KisMinuteCandleResponse.Candle> candles, int lag) {
        int k = Math.min(lag, candles.size());
        if (k < 2) return null;
        Double now = parse(candles.get(0).close());
        Double then = parse(candles.get(k - 1).close());
        if (now == null || then == null || then <= 0) return null;
        return round2((now - then) / then * 100);
    }

    private static Double parse(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.parseDouble(v.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }
}
