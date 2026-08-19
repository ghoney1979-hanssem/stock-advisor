package com.stockadvisor.service;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.market.dto.KisDailyPriceResponse.DailyPrice;
import com.stockadvisor.market.dto.KisMinuteCandleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * KIS 일별 시세로 "거래량 급증 + 상승추이" 신호를 판정한다.
 *
 * <p>판정: 당일 거래량 ≥ 최근 {@code lookbackDays}일 평균 × {@code volumeMultiplier}
 * AND 당일 등락률 ≥ {@code minChangeRate}%.</p>
 */
@Service
public class MarketSignalService {

    private static final Logger log = LoggerFactory.getLogger(MarketSignalService.class);

    private final KisApiClient kisApiClient;
    private final SignalProperties properties;

    public MarketSignalService(KisApiClient kisApiClient, SignalProperties properties) {
        this.kisApiClient = kisApiClient;
        this.properties = properties;
    }

    /**
     * 종목의 현재 신호를 판정한다. 데이터 부족/동시호가/휴장 시 empty.
     */
    public Optional<SignalResult> evaluate(String stockCode) {
        // 동시호가·장외 시간대 제외 — 연속매매(09:00~15:20) 밖이면 KIS 호출도 생략
        if (!isContinuousSession()) {
            return Optional.empty();
        }

        KisDailyPriceResponse response = kisApiClient.fetchDailyPrices(stockCode);
        List<DailyPrice> rows = response.output();
        if (rows == null || rows.size() < 2) {
            log.debug("일별시세 데이터 부족 stockCode={}", stockCode);
            return Optional.empty();
        }

        // rows[0] = 당일(최신), rows[1..] = 직전 영업일들
        DailyPrice today = rows.get(0);
        // 당일 거래일 데이터가 아니면(휴장/거래정지) 평가 안 함 — 동시호가 외에도 비정상 데이터 방어
        if (today.businessDate() == null
                || !today.businessDate().equals(ZonedDateTime.now(SEOUL).format(YYYYMMDD))) {
            return Optional.empty();
        }
        long todayVolume = parseLong(today.accumulatedVolume());
        double changeRate = parseDouble(today.dayChangeRate());
        long closePrice = parseLong(today.closePrice());

        int n = Math.min(properties.lookbackDays(), rows.size() - 1);
        long sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += parseLong(rows.get(i).accumulatedVolume());
        }
        long avgVolume = n > 0 ? sum / n : 0;

        // [E] 직전 breakoutLookback 거래일(오늘 rows[0] 제외) 최고가 — 신고가 돌파 판정용. 고가 우선, 없으면 종가. 이미 조회한 rows 재활용(추가 호출 0).
        int highN = Math.min(Math.max(1, properties.breakoutLookback()), rows.size() - 1);
        long priorHigh = 0;
        for (int i = 1; i <= highN; i++) {
            long h = parseLong(rows.get(i).highPrice());
            if (h <= 0) h = parseLong(rows.get(i).closePrice());
            if (h > priorHigh) priorHigh = h;
        }

        // [F] MA20 상향 돌파 이벤트 — 이미 조회한 rows 재활용(추가 호출 0). 직전 확정종가들의 MA vs 현재가.
        java.util.List<Long> priorCloses = new java.util.ArrayList<>();
        for (int i = 1; i < rows.size(); i++) priorCloses.add(parseLong(rows.get(i).closePrice()));
        boolean maCrossUp = computeMaCrossUp(priorCloses, closePrice, MA_TREND_PERIOD);
        // [F] MA20 대비 이격%((현재가−MA)/MA×100) — 돌파 강도(버퍼) 판정용. 같은 일봉 재활용(추가 호출 0).
        double ma20 = computeMa(priorCloses, MA_TREND_PERIOD);
        double maDistPct = (ma20 > 0 && closePrice > 0) ? (closePrice - ma20) / ma20 * 100 : 0;
        // [G] RSI(14) 과매도 상향 돌파 이벤트 — 같은 일봉 재활용(추가 호출 0).
        boolean rsiCrossUp = computeRsiCrossUp(priorCloses, closePrice, RSI_PERIOD, RSI_OVERSOLD);
        // [H] NR7 변동성 수축 돌파 — 같은 일봉 재활용. 전일 고가/저가로 일중변동폭 계산.
        java.util.List<Long> priorHighs = new java.util.ArrayList<>();
        java.util.List<Long> priorLows = new java.util.ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            priorHighs.add(parseLong(rows.get(i).highPrice()));
            priorLows.add(parseLong(rows.get(i).lowPrice()));
        }
        boolean squeezeBreakout = computeSqueezeBreakout(priorHighs, priorLows, closePrice, SQUEEZE_LOOKBACK);

        // 시간대 보정: 장중에는 '오늘 누적거래량'이 아직 진행 중이므로,
        // 20일 '하루' 평균에 경과한 거래시간 비율을 곱해 같은 시점끼리 비교한다.
        double sessionFraction = sessionElapsedFraction(today.businessDate());
        long effectiveAvg = Math.round(avgVolume * sessionFraction);

        double volumeRatio = effectiveAvg > 0 ? (double) todayVolume / effectiveAvg : 0;
        boolean volumeSpike = effectiveAvg > 0 && todayVolume >= effectiveAvg * properties.volumeMultiplier();
        boolean uptrend = changeRate >= properties.minChangeRate();
        boolean reboundCandidate = changeRate <= -properties.meanReversionMinDrop();   // 당일 하락(C 후보)

        // 분봉 신선도/활성도는 모멘텀(A)·역추세(C)·MA돌파(F)·스퀴즈돌파(H) 후보일 때만 1회 계산(분봉 호출 절감).
        // 횡보(B)는 분봉확인을 쓰지 않으므로 계산도 생략. F/H는 볼륨무관 트리거라 volumeSpike 없이도 돌파면 계산.
        boolean needFresh = (volumeSpike && (uptrend || reboundCandidate)) || maCrossUp || squeezeBreakout;
        boolean freshAndActive = needFresh && isFreshAndActive(stockCode, todayVolume, sessionFraction);
        boolean freshActive = freshAndActive && volumeSpike && uptrend;          // [A] 상승 + 분봉 신선·활발
        boolean reboundActive = freshAndActive && volumeSpike && reboundCandidate; // [C] 하락이어도 분봉은 반등 중
        boolean maBreakoutFresh = freshAndActive && maCrossUp;                    // [F] MA돌파 + 분봉 신선·활발(죽은 돌파 회피)
        boolean squeezeBreakoutFresh = freshAndActive && squeezeBreakout;         // [H] 스퀴즈돌파 + 분봉 신선·활발(페이드 돌파 회피)

        log.debug("신호지표 stockCode={} 거래량배수(보정)={} 등락률={}% 급증={} 신선활발(A)={} 반등(C)={}",
                stockCode, String.format("%.2f", volumeRatio), changeRate, volumeSpike, freshActive, reboundActive);

        // 셋업 feature(이미 조회한 rows 재활용 — 추가 KIS 0): ATR%(변동성)·직전고가 대비 거리%·최근5일 수익률%.
        List<DailyPrice> chrono = new java.util.ArrayList<>(rows);
        java.util.Collections.reverse(chrono);   // 오래된→최신
        Double atr = PositionSizer.computeAtr(chrono, ATR_PERIOD);
        double atrPct = (atr != null && closePrice > 0) ? atr / closePrice * 100 : 0;
        double distFromHighPct = priorHigh > 0 ? (double) (closePrice - priorHigh) / priorHigh * 100 : 0;
        long close5 = rows.size() > 5 ? parseLong(rows.get(5).closePrice()) : 0;   // 5거래일 전(오늘=rows[0])
        double ret5dPct = close5 > 0 ? (double) (closePrice - close5) / close5 * 100 : 0;

        // 개장갭%((오늘시가−전일종가)/전일종가). 오늘=rows[0].open, 전일종가=rows[1].close. 데이터 없으면 0.
        long todayOpen = parseLong(today.openPrice());
        long prevClose = rows.size() > 1 ? parseLong(rows.get(1).closePrice()) : 0;
        double gapPct = (todayOpen > 0 && prevClose > 0) ? (double) (todayOpen - prevClose) / prevClose * 100 : 0;
        // 전일 확정 종가 + 그 영업일 — UniverseSnapshotService가 전일 스냅샷의 종가 타깃을 정확히 채우는 데 쓴다(추가 KIS 0).
        String prevBusinessDate = rows.size() > 1 ? rows.get(1).businessDate() : null;

        return Optional.of(new SignalResult(volumeRatio, changeRate, closePrice, todayVolume,
                volumeSpike, freshActive, reboundActive, priorHigh, maCrossUp, rsiCrossUp, squeezeBreakout,
                atrPct, distFromHighPct, ret5dPct, gapPct, maBreakoutFresh, maDistPct, squeezeBreakoutFresh,
                prevClose, prevBusinessDate));
    }

    /**
     * [H] 변동성 수축 돌파 — 전일(priorHighs/Lows[0])의 일중변동폭이 최근 {@code lookback}일 중 최소(NR7)이고,
     * 현재가 &gt; 전일 고가면 true(수축 후 상방 팽창). priorHighs/Lows는 오늘 제외 최신순. 순수 함수.
     */
    static boolean computeSqueezeBreakout(java.util.List<Long> priorHighs, java.util.List<Long> priorLows,
                                          long currentPrice, int lookback) {
        if (priorHighs.size() < lookback || priorLows.size() < lookback) return false;
        long yHigh = priorHighs.get(0), yLow = priorLows.get(0);
        if (yHigh <= 0 || yLow <= 0) return false;
        long yRange = yHigh - yLow;
        if (yRange < 0) return false;
        for (int i = 0; i < lookback; i++) {
            long h = priorHighs.get(i), l = priorLows.get(i);
            if (h <= 0 || l <= 0) return false;
            if (h - l < yRange) return false;   // 전일보다 더 좁은 날이 있으면 NR7 아님
        }
        return currentPrice > yHigh;            // 수축(NR7) + 전일 고가 상향 돌파
    }

    /**
     * [G] RSI(14) 과매도 상향 돌파 — 어제까지 RSI ≤ oversold 이고 현재가 반영 RSI > oversold면 true(과매도서 회복 진입).
     * priorCloses는 오늘 제외 최근 확정종가(최신순, [0]=전일). 순수 함수.
     */
    static boolean computeRsiCrossUp(java.util.List<Long> priorCloses, long currentPrice, int period, double oversold) {
        Double rsiPrev = computeRsi(priorCloses, period);                 // 어제 종가까지의 RSI
        java.util.List<Long> nowSeries = new java.util.ArrayList<>();
        nowSeries.add(currentPrice);
        nowSeries.addAll(priorCloses);                                    // 현재가를 최신에 붙인 시리즈
        Double rsiNow = computeRsi(nowSeries, period);
        if (rsiPrev == null || rsiNow == null) return false;
        return rsiPrev <= oversold && rsiNow > oversold;
    }

    /** RSI(단순평균 방식). closesNewestFirst[0]=최신. period+1개 미만이면 null. 손실 0이면 100. */
    static Double computeRsi(java.util.List<Long> closesNewestFirst, int period) {
        if (closesNewestFirst.size() < period + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 0; i < period; i++) {
            long c0 = closesNewestFirst.get(i), c1 = closesNewestFirst.get(i + 1);
            if (c0 <= 0 || c1 <= 0) return null;
            long diff = c0 - c1;                          // 최신 - 이전(양수=상승)
            if (diff > 0) gain += diff; else loss += -diff;
        }
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100.0;
        double rs = (gain / period) / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    /**
     * [F] MA{@code period} 상향 돌파 이벤트 — 직전 확정종가 ≤ MA 이고 현재가 > MA면 true("오늘 처음 위로 뚫음").
     * priorCloses는 오늘 제외 최근 확정종가(최신순, [0]=전일). 데이터 부족/무효면 false. 순수 함수(단위테스트 용이).
     */
    /** 직전 종가들의 단순이동평균(SMA). 표본 부족·비정상값이면 0. */
    static double computeMa(java.util.List<Long> priorCloses, int period) {
        if (priorCloses.size() < period) return 0;
        long sum = 0;
        for (int i = 0; i < period; i++) {
            long c = priorCloses.get(i);
            if (c <= 0) return 0;
            sum += c;
        }
        return (double) sum / period;
    }

    static boolean computeMaCrossUp(java.util.List<Long> priorCloses, long currentPrice, int period) {
        if (currentPrice <= 0 || priorCloses.size() < period) return false;
        long sum = 0;
        for (int i = 0; i < period; i++) {
            long c = priorCloses.get(i);
            if (c <= 0) return false;
            sum += c;
        }
        double ma = (double) sum / period;
        long prevClose = priorCloses.get(0);
        return prevClose <= ma && currentPrice > ma;   // 전일은 MA 이하, 현재가는 MA 위 = 상향 돌파
    }

    /**
     * 분봉 기반 신선도/활성도 판정.
     * 최근 윈도우 모멘텀이 살아있고(지금도 상승) 분당 거래량이 여전히 평균보다 활발할 때만 true.
     * 분봉 데이터가 없거나 조회 실패 시에는 일봉 기준만으로 통과(기존 동작 유지).
     */
    private boolean isFreshAndActive(String stockCode, long todayVolume, double sessionFraction) {
        try {
            List<KisMinuteCandleResponse.Candle> candles =
                    kisApiClient.fetchMinuteCandles(stockCode).output2();
            if (candles == null || candles.isEmpty()) {
                log.debug("분봉 데이터 없음 → 일봉 기준만 적용 stockCode={}", stockCode);
                return true;
            }
            int k = Math.min(properties.intradayWindowMinutes(), candles.size());
            long nowPrice = parseLong(candles.get(0).close());     // 최신 분
            long thenPrice = parseLong(candles.get(k - 1).close()); // k분 전
            double momentum = thenPrice > 0 ? (double) (nowPrice - thenPrice) / thenPrice * 100 : 0;

            long recentVol = 0;
            for (int i = 0; i < k; i++) recentVol += parseLong(candles.get(i).volume());
            double recentPerMin = (double) recentVol / k;
            double minutesElapsed = Math.max(1.0, sessionFraction * SESSION_MINUTES);
            double dayPerMin = todayVolume / minutesElapsed;
            double activityRatio = dayPerMin > 0 ? recentPerMin / dayPerMin : 0;

            boolean fresh = momentum >= properties.intradayMinMomentum();
            boolean active = activityRatio >= properties.intradayMinActivityRatio();
            log.debug("분봉필터 stockCode={} 최근{}분 모멘텀={}% 활성도={}배 → fresh={} active={}",
                    stockCode, k, String.format("%.2f", momentum),
                    String.format("%.2f", activityRatio), fresh, active);
            return fresh && active;
        } catch (Exception ex) {
            log.warn("분봉 필터 조회 실패 → 일봉 기준만 적용 stockCode={}: {}", stockCode, ex.getMessage());
            return true;
        }
    }

    // KRX 정규장 09:00~15:30 (390분)
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    private static final double SESSION_MINUTES = 390.0;
    private static final double MIN_FRACTION = 0.05;   // 개장 직후 과민 반응 방지
    private static final int MA_TREND_PERIOD = 20;     // [F] 추세추종 MA 상향돌파 판정 기간(거래일)
    private static final int RSI_PERIOD = 14;          // [G] RSI 기간
    private static final int ATR_PERIOD = 14;          // 셋업 feature: ATR 기간(거래일)
    private static final double RSI_OVERSOLD = 30.0;   // [G] 과매도 임계 — 이 아래서 위로 돌파 시 진입
    private static final int SQUEEZE_LOOKBACK = 7;     // [H] NR7 변동성 수축 판정 기간(전일이 최근 N일 중 변동폭 최소)
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 현재가 연속매매 시간대(설정 session-start ~ session-end)인지. 동시호가/장외 제외. */
    private boolean isContinuousSession() {
        LocalTime now = ZonedDateTime.now(SEOUL).toLocalTime();
        LocalTime start = LocalTime.parse(properties.sessionStart());
        LocalTime end = LocalTime.parse(properties.sessionEnd());
        return !now.isBefore(start) && now.isBefore(end);
    }

    /**
     * 거래량 비교용 시간 보정 계수(0~1).
     * 데이터가 '오늘' 진행 중인 장이면 경과 거래시간 비율, 그 외(과거 거래일/마감 후)는 1.0.
     */
    private double sessionElapsedFraction(String businessDate) {
        ZonedDateTime now = ZonedDateTime.now(SEOUL);
        if (!now.format(YYYYMMDD).equals(businessDate)) {
            return 1.0;   // 과거 거래일 데이터 = 완결된 하루
        }
        LocalTime t = now.toLocalTime();
        if (!t.isAfter(SESSION_OPEN)) return MIN_FRACTION;   // 개장 전/직후
        if (!t.isBefore(SESSION_CLOSE)) return 1.0;          // 마감 후 = 완결
        double frac = Duration.between(SESSION_OPEN, t).toMinutes() / SESSION_MINUTES;
        return Math.max(MIN_FRACTION, Math.min(1.0, frac));
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
