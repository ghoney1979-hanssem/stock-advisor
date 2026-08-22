package com.stockadvisor.market;

import com.stockadvisor.common.ExternalApiException;
import com.stockadvisor.config.RedisCacheConfig;
import com.stockadvisor.config.properties.KisProperties;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.market.dto.KisAskingPriceResponse;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.market.dto.KisCcldResponse;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.market.dto.KisCcnlResponse;
import com.stockadvisor.market.dto.KisNewsResponse;
import com.stockadvisor.market.dto.KisHashResponse;
import com.stockadvisor.market.dto.KisIndexResponse;
import com.stockadvisor.market.dto.KisMinuteCandleResponse;
import com.stockadvisor.market.dto.KisOrderResponse;
import com.stockadvisor.market.dto.KisQuoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 한국투자증권(KIS) 국내주식 시세 조회 클라이언트.
 * 현재가 시세는 Redis 에 1시간 캐싱되며, 일별 시세는 실시간성을 위해 캐싱하지 않는다.
 *
 * <p>KIS 는 초당 거래건수(20 TPS)를 초과하면 EGW00201 오류를 반환한다.
 * 이 경우 짧은 백오프 후 재시도한다.</p>
 */
@Component
public class KisApiClient {

    private static final Logger log = LoggerFactory.getLogger(KisApiClient.class);

    // 국내주식 현재가 시세 조회 거래ID
    private static final String TR_ID_INQUIRE_PRICE = "FHKST01010100";
    // 국내주식 기간별(일별) 시세 조회 거래ID
    private static final String TR_ID_DAILY_PRICE = "FHKST01010400";
    // 국내주식 당일 분봉 조회 거래ID
    private static final String TR_ID_MINUTE_CANDLE = "FHKST03010200";
    // 국내업종 현재지수 조회 거래ID (코스피=0001, 코스닥=1001)
    private static final String TR_ID_INDEX = "FHPUP02100000";
    // 국내주식 호가/예상체결 조회 거래ID (실측 스프레드)
    private static final String TR_ID_ASKING_PRICE = "FHKST01010200";
    // 국내주식 주식잔고조회 거래ID (실전 TTTC8434R / 모의 VTTC8434R)
    private static final String TR_ID_BALANCE = "TTTC8434R";
    // 국내주식 현금주문 거래ID (실전: 매수 TTTC0802U / 매도 TTTC0801U, 모의: VTTC0802U/VTTC0801U)
    private static final String TR_ID_ORDER_BUY = "TTTC0802U";
    private static final String TR_ID_ORDER_SELL = "TTTC0801U";
    // 주식일별주문체결조회 거래ID (실전 TTTC8001R, 최근 3개월)
    private static final String TR_ID_CCLD = "TTTC8001R";
    // 주식주문(정정취소) 거래ID (실전 TTTC0803U)
    private static final String TR_ID_CANCEL = "TTTC0803U";
    private static final java.time.format.DateTimeFormatter YYYYMMDD =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final java.time.format.DateTimeFormatter HHMMSS =
            java.time.format.DateTimeFormatter.ofPattern("HHmmss");
    private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

    // 초당 거래건수 초과 오류코드 + 재시도 정책
    // EGW00201: 앱(시세) 전역 TPS / EGW00215: 계좌 원장 TPS(잔고·주문 등) — 둘 다 일시초과라 재시도 대상.
    private static final java.util.List<String> RATE_LIMIT_CODES = java.util.List.of("EGW00201", "EGW00215");
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_BACKOFF_MS = 250;

    // 전역 호출 속도 제한: 모든 KIS 호출(스캔·후속추적·신호평가 등) 합산 ≤ ~14건/초로 묶어
    // 스레드 간 경합으로 인한 초당제한 초과(EGW00201)를 예방한다. (KIS 실전 한도 20 TPS)
    private static final long MIN_INTERVAL_MS = 120;
    private final Object rateLock = new Object();
    private long lastCallAt = 0;

    private final RestClient restClient;
    private final KisProperties properties;
    private final KisTokenManager tokenManager;
    // 주문 본문 직렬화용. Map을 그대로 넘기면 Spring이 chunked 전송 → KIS GW가 EGW00202(GW라우팅 오류).
    // byte[]로 직렬화해 넘기면 Content-Length가 붙어 회피된다.
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public KisApiClient(RestClient.Builder builder,
                        KisProperties properties,
                        KisTokenManager tokenManager) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    /**
     * 국내주식 현재가 조회.
     *
     * @param stockCode 종목코드 6자리 (예: "005930" 삼성전자)
     */
    @Cacheable(cacheNames = RedisCacheConfig.KIS_QUOTE, key = "#stockCode")
    public KisQuoteResponse fetchCurrentQuote(String stockCode) {
        KisQuoteResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J") // J: 주식(KRX)
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build(),
                TR_ID_INQUIRE_PRICE, KisQuoteResponse.class, stockCode);

        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "시세 조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    /**
     * 주식현재가 체결 조회 (FHKST01010300) — 당일 체결강도(tday_rltv) 태깅용. 진입 건당 1콜(비캐시). 전역 rateGate 공유.
     */
    public KisCcnlResponse fetchCcnl(String stockCode) {
        KisCcnlResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-ccnl")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build(),
                "FHKST01010300", KisCcnlResponse.class, stockCode);
        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "체결 조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    /**
     * 종목 뉴스/공시 제목 조회 (종합 시황·공시 FHKST01011800, 최신순).
     * 진입 시점 뉴스 feature 태깅용 — 진입 건당 1콜(비캐시, 신선도가 목적). 전역 rateGate 공유.
     */
    public KisNewsResponse fetchNewsTitles(String stockCode) {
        KisNewsResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/news-title")
                        .queryParam("FID_NEWS_OFER_ENTP_CODE", "")
                        .queryParam("FID_COND_MRKT_CLS_CODE", "")
                        .queryParam("FID_INPUT_ISCD", stockCode)   // 해당 종목이 등록된 뉴스만
                        .queryParam("FID_TITL_CNTT", "")
                        .queryParam("FID_INPUT_DATE_1", "")        // 공백=현재 기준
                        .queryParam("FID_INPUT_HOUR_1", "")
                        .queryParam("FID_RANK_SORT_CLS_CODE", "")
                        .queryParam("FID_INPUT_SRNO", "")
                        .build(),
                "FHKST01011800", KisNewsResponse.class, stockCode);
        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "뉴스 조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    /**
     * 국내주식 일별 시세 조회 (최근 약 30 영업일, 최신일 우선).
     * 장중 거래량이 실시간 반영되므로 캐싱하지 않는다.
     *
     * @param stockCode 종목코드 6자리
     */
    public KisDailyPriceResponse fetchDailyPrices(String stockCode) {
        KisDailyPriceResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-daily-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .queryParam("FID_PERIOD_DIV_CODE", "D")  // D: 일별
                        .queryParam("FID_ORG_ADJ_PRC", "0")       // 0: 수정주가 반영
                        .build(),
                TR_ID_DAILY_PRICE, KisDailyPriceResponse.class, stockCode);

        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "일별 시세 조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    public record VwapVol(Double vwap, long volume) {}

    /**
     * 현재 VWAP(가중평균주가) + 누적거래량 조회 — 캐시 미사용(매번 신선). 신호 청산 판정용.
     */
    public VwapVol fetchVwapVolume(String stockCode) {
        KisQuoteResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build(),
                TR_ID_INQUIRE_PRICE, KisQuoteResponse.class, stockCode);
        if (response == null || response.output() == null) return new VwapVol(null, 0);
        KisQuoteResponse.Output o = response.output();
        Double vwap = parseNullableDouble(o.vwap());
        long vol = parseLongSafe(o.accumulatedVolume());
        return new VwapVol(vwap, vol);
    }

    private Double parseNullableDouble(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private long parseLongSafe(String v) {
        if (v == null || v.isBlank()) return 0;
        try { return Long.parseLong(v.replace(",", "").trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 당일 분봉 조회 (최신분 우선, 최대 30개). 장중 신선도/활성도 판정용. 캐시 미사용.
     */
    public KisMinuteCandleResponse fetchMinuteCandles(String stockCode) {
        String hour = java.time.ZonedDateTime.now(SEOUL).format(HHMMSS);
        KisMinuteCandleResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                        .queryParam("FID_ETC_CLS_CODE", "")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .queryParam("FID_INPUT_HOUR_1", hour)
                        .queryParam("FID_PW_DATA_INCU_YN", "N")
                        .build(),
                TR_ID_MINUTE_CANDLE, KisMinuteCandleResponse.class, stockCode);

        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "분봉 조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    /**
     * 현재가(당일 종가) 조회 — 캐시 미사용(매번 신선한 값). 가격 추적용.
     */
    public long fetchLatestClose(String stockCode) {
        var rows = fetchDailyPrices(stockCode).output();
        if (rows == null || rows.isEmpty()) {
            throw new ExternalApiException("KIS", "현재가 조회 결과 없음: " + stockCode);
        }
        String close = rows.get(0).closePrice();
        if (close == null || close.isBlank()) return 0;
        try {
            return Long.parseLong(close.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 당일 전일대비 등락률(%) — 일봉 rows[0].prdy_ctrt. 상한가(+30% 근접) 감지용. 실패/없으면 0. */
    public double fetchDayChangeRate(String stockCode) {
        try {
            var rows = fetchDailyPrices(stockCode).output();
            if (rows == null || rows.isEmpty()) return 0;
            String cr = rows.get(0).dayChangeRate();
            return (cr == null || cr.isBlank()) ? 0 : Double.parseDouble(cr.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // 지수 등락률 단기 캐시 (스캔 버스트 중 동일 지수 반복조회 방지)
    private static final long INDEX_CACHE_MS = 60_000;
    private final java.util.Map<String, double[]> indexCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 국내 지수 등락률(%) 조회. indexCode: 0001=코스피, 1001=코스닥. 실패 시 null. 60초 캐시.
     */
    public Double fetchIndexChangeRate(String indexCode) {
        double[] cached = indexCache.get(indexCode);
        if (cached != null && (System.currentTimeMillis() - cached[1]) < INDEX_CACHE_MS) {
            return cached[0];
        }
        KisIndexResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                        .queryParam("FID_INPUT_ISCD", indexCode)
                        .build(),
                TR_ID_INDEX, KisIndexResponse.class, indexCode);
        if (response == null || !response.isSuccess() || response.output() == null) {
            return null;
        }
        String v = response.output().changeRate();
        if (v == null || v.isBlank()) return null;
        try {
            double rate = Double.parseDouble(v.trim());
            indexCache.put(indexCode, new double[]{rate, System.currentTimeMillis()});
            return rate;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Spread(long bestAsk, long bestBid) {}

    /** 호가 한 단계(가격·잔량). */
    public record Level(long price, long qty) {}

    /**
     * 매도 10호가(가격↑ 순) + 매수 10호가(가격↓ 순), 각 잔량 포함. bestBid는 매수 최우선호가(bids 비었을 때도 유효).
     * ⚠️ 2인자 생성자는 호환용(bids 미수집) — 기존 호출/테스트 무churn.
     */
    public record OrderBook(long bestBid, java.util.List<Level> asks, java.util.List<Level> bids) {
        public OrderBook(long bestBid, java.util.List<Level> asks) { this(bestBid, asks, java.util.List.of()); }

        public long bestAsk() { return asks.isEmpty() ? 0 : asks.get(0).price(); }

        /** 실측 스프레드(1호가). 호가가 무효(교차·결측)면 null → 호출측 tick 추정 fallback. */
        public Spread spread() {
            long ask = bestAsk();
            if (ask <= 0 || bestBid <= 0 || ask < bestBid) return null;
            return new Spread(ask, bestBid);
        }

        /**
         * 호가 불균형(order book imbalance) — 상위 {@code levels}단계 잔량 기준
         * <b>(매수잔량 − 매도잔량) / (매수잔량 + 매도잔량) × 100</b>. 범위 −100(매도 일방) ~ +100(매수 일방).
         *
         * <p>순수 계산이라 KIS 없이 테스트된다. 한쪽 사다리가 아예 비었으면(장전·거래정지) null —
         * 0(균형)으로 오해되지 않게 결측을 분명히 구분한다. 요청 단계가 수집분보다 많으면 있는 만큼만 합산한다.</p>
         *
         * <p>⚠️ 스냅샷 지표다 — 체결 직전 취소가 잦은 종목은 실제 압력과 다를 수 있다(측정 단계 수용).</p>
         */
        public Double imbalancePct(int levels) {
            if (levels <= 0 || asks.isEmpty() || bids.isEmpty()) return null;
            long askQty = sumQty(asks, levels);
            long bidQty = sumQty(bids, levels);
            long total = askQty + bidQty;
            if (total <= 0) return null;
            return (double) (bidQty - askQty) / total * 100;
        }

        private static long sumQty(java.util.List<Level> levels, int n) {
            long sum = 0;
            for (int i = 0; i < Math.min(n, levels.size()); i++) sum += levels.get(i).qty();
            return sum;
        }
    }

    /**
     * 매도·매수 각 10호가(가격+잔량) 조회. 캐시 미사용(호가는 실시간). 실패/무효면 null.
     */
    public OrderBook fetchOrderBook(String stockCode) {
        KisAskingPriceResponse r = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build(),
                TR_ID_ASKING_PRICE, KisAskingPriceResponse.class, stockCode);
        if (r == null || !r.isSuccess() || r.output() == null) return null;
        long bid = parseLongSafe(r.output().bidp1());
        java.util.List<Level> asks = toLevels(r.output().askPrices(), r.output().askQtys());   // 가격↑(askp1 최우선) 순 그대로
        java.util.List<Level> bids = toLevels(r.output().bidPrices(), r.output().bidQtys());   // 가격↓(bidp1 최우선) 순 그대로
        if (asks.isEmpty() || bid <= 0) return null;
        return new OrderBook(bid, asks, bids);
    }

    /** 호가 사다리(가격·잔량 문자열 배열) → Level 목록. 결측·0은 건너뛴다. */
    private java.util.List<Level> toLevels(String[] prices, String[] qtys) {
        java.util.List<Level> levels = new java.util.ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            long p = parseLongSafe(prices[i]);
            long q = parseLongSafe(qtys[i]);
            if (p > 0 && q > 0) levels.add(new Level(p, q));
        }
        return levels;
    }

    /**
     * 최우선 매도/매수호가(실측 스프레드용). 실패/무효면 null → 호출측 tick 추정 fallback.
     */
    public Spread fetchAskingPrice(String stockCode) {
        OrderBook ob = fetchOrderBook(stockCode);
        return ob == null ? null : ob.spread();
    }

    /**
     * 주식 잔고·예수금 조회. 시세와 달리 계좌번호(CANO/ACNT_PRDT_CD)가 필수다.
     * 전역 rateGate/토큰을 공유한다(시세 호출과 합산 TPS 유지). 주문 권한 없으면 여기서 rt_cd≠0 로 드러남.
     */
    public KisBalanceResponse fetchBalance() {
        String cano = properties.accountNumber();
        String acntPrdt = properties.accountProductCode();
        if (cano == null || cano.isBlank()) {
            throw new ExternalApiException("KIS", "계좌번호(KIS_ACCOUNT_NO) 미설정 — 잔고조회 불가");
        }
        KisBalanceResponse response = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/trading/inquire-balance")
                        .queryParam("CANO", cano)
                        .queryParam("ACNT_PRDT_CD", acntPrdt == null || acntPrdt.isBlank() ? "01" : acntPrdt)
                        .queryParam("AFHR_FLPR_YN", "N")          // 시간외단일가여부
                        .queryParam("OFL_YN", "")                 // 오프라인여부
                        .queryParam("INQR_DVSN", "02")            // 조회구분 02=종목별
                        .queryParam("UNPR_DVSN", "01")            // 단가구분
                        .queryParam("FUND_STTL_ICLD_YN", "N")     // 펀드결제분포함여부
                        .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N") // 융자금액자동상환여부
                        .queryParam("PRCS_DVSN", "00")            // 처리구분 00=전일매매포함
                        .queryParam("CTX_AREA_FK100", "")         // 연속조회검색조건
                        .queryParam("CTX_AREA_NK100", "")         // 연속조회키
                        .build(),
                TR_ID_BALANCE, KisBalanceResponse.class, "balance");

        if (response == null || !response.isSuccess()) {
            throw new ExternalApiException("KIS", "잔고조회 실패: "
                    + (response == null ? "응답 없음" : response.message()));
        }
        return response;
    }

    /** 공통 GET 호출. KIS 초당 거래건수 초과(EGW00201) 시 백오프 후 재시도. */
    private <T> T get(Function<org.springframework.web.util.UriBuilder, URI> uriFn,
                      String trId, Class<T> type, String stockCode) {
        Supplier<T> call = () -> restClient.get()
                .uri(uriFn)
                .header("authorization", "Bearer " + tokenManager.getAccessToken())
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", trId)
                .header("custtype", "P")   // 개인
                .retrieve()
                .body(type);
        return execute(call, stockCode);
    }

    /**
     * 현금 주문(매수/매도). 지정가면 ordDvsn="00"+단가, 시장가면 "01"+단가0.
     * POST 본문은 hashkey 발급 후 헤더에 실어 보낸다. 전역 rateGate/토큰 공유.
     * rt_cd≠0(거부)는 응답으로 반환(호출측 판정), 네트워크 오류는 예외.
     */
    public KisOrderResponse orderCash(String stockCode, OrderSide side, long qty, long price, String ordDvsn) {
        String cano = properties.accountNumber();
        if (cano == null || cano.isBlank()) {
            throw new ExternalApiException("KIS", "계좌번호(KIS_ACCOUNT_NO) 미설정 — 주문 불가");
        }
        String acntPrdt = properties.accountProductCode();
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("CANO", cano);
        body.put("ACNT_PRDT_CD", acntPrdt == null || acntPrdt.isBlank() ? "01" : acntPrdt);
        body.put("PDNO", stockCode);
        body.put("ORD_DVSN", ordDvsn);                                    // 00 지정가 / 01 시장가
        body.put("ORD_QTY", String.valueOf(qty));
        body.put("ORD_UNPR", "01".equals(ordDvsn) ? "0" : String.valueOf(price));
        String trId = side == OrderSide.BUY ? TR_ID_ORDER_BUY : TR_ID_ORDER_SELL;
        return postOrder("/uapi/domestic-stock/v1/trading/order-cash", body, trId,
                KisOrderResponse.class, side + ":" + stockCode);
    }

    public record FillInfo(String orderNo, long orderQty, long filledQty, long remainingQty, long avgFillPrice) {}

    /**
     * 당일 주문별 체결 현황 조회(주문번호→체결). LIVE 주문 상태 보정용.
     * 전역 rateGate/토큰 공유. 평균체결가 = 총체결금액/총체결수량.
     */
    public java.util.List<FillInfo> fetchTodayFills() {
        String cano = properties.accountNumber();
        if (cano == null || cano.isBlank()) {
            throw new ExternalApiException("KIS", "계좌번호(KIS_ACCOUNT_NO) 미설정 — 체결조회 불가");
        }
        String acntPrdt = properties.accountProductCode();
        String today = java.time.ZonedDateTime.now(SEOUL).format(YYYYMMDD);
        KisCcldResponse resp = get(
                uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
                        .queryParam("CANO", cano)
                        .queryParam("ACNT_PRDT_CD", acntPrdt == null || acntPrdt.isBlank() ? "01" : acntPrdt)
                        .queryParam("INQR_STRT_DT", today)
                        .queryParam("INQR_END_DT", today)
                        .queryParam("SLL_BUY_DVSN_CD", "00")   // 전체
                        .queryParam("INQR_DVSN", "00")          // 역순
                        .queryParam("PDNO", "")
                        .queryParam("CCLD_DVSN", "00")          // 전체(체결+미체결)
                        .queryParam("ORD_GNO_BRNO", "")
                        .queryParam("ODNO", "")
                        .queryParam("INQR_DVSN_3", "00")
                        .queryParam("INQR_DVSN_1", "")
                        .queryParam("CTX_AREA_FK100", "")
                        .queryParam("CTX_AREA_NK100", "")
                        .build(),
                TR_ID_CCLD, KisCcldResponse.class, "ccld");
        if (resp == null || !resp.isSuccess()) {
            throw new ExternalApiException("KIS", "체결조회 실패: "
                    + (resp == null ? "응답 없음" : resp.message()));
        }
        java.util.List<FillInfo> out = new java.util.ArrayList<>();
        for (KisCcldResponse.Ccld c : resp.orders() == null ? java.util.List.<KisCcldResponse.Ccld>of() : resp.orders()) {
            if (c.orderNo() == null || c.orderNo().isBlank()) continue;
            long filled = parseLongSafe(c.filledQty());
            long amt = parseLongSafe(c.filledAmt());
            long avg = filled > 0 ? Math.round((double) amt / filled) : 0;
            out.add(new FillInfo(c.orderNo(), parseLongSafe(c.orderQty()), filled,
                    parseLongSafe(c.remainingQty()), avg));
        }
        return out;
    }

    /**
     * 미체결 주문 전량 취소(정정취소 API). 원주문의 거래소조직번호+주문번호 필요.
     * rt_cd≠0(이미 체결/취소불가 등)은 응답 반환(호출측 판정).
     */
    public KisOrderResponse cancelOrder(String orgNo, String orderNo) {
        String cano = properties.accountNumber();
        if (cano == null || cano.isBlank()) {
            throw new ExternalApiException("KIS", "계좌번호 미설정 — 취소 불가");
        }
        String acntPrdt = properties.accountProductCode();
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("CANO", cano);
        body.put("ACNT_PRDT_CD", acntPrdt == null || acntPrdt.isBlank() ? "01" : acntPrdt);
        body.put("KRX_FWDG_ORD_ORGNO", orgNo == null ? "" : orgNo);
        body.put("ORGN_ODNO", orderNo);
        body.put("ORD_DVSN", "00");
        body.put("RVSE_CNCL_DVSN_CD", "02");   // 02 취소
        body.put("ORD_QTY", "0");              // 잔량전부 취소면 0
        body.put("ORD_UNPR", "0");
        body.put("QTY_ALL_ORD_YN", "Y");       // 잔량 전부
        return postOrder("/uapi/domestic-stock/v1/trading/order-rvsecncl", body, TR_ID_CANCEL,
                KisOrderResponse.class, "cancel:" + orderNo);
    }

    /**
     * POST 주문 공통: hashkey 발급 → 헤더 포함 전송. rateGate/재시도 공유.
     * ⚠️ 본문은 반드시 byte[]로 직렬화해 전송한다 — Map을 그대로 넘기면 Spring이 Transfer-Encoding: chunked로
     * 보내는데, KIS 게이트웨이는 라우팅 단계에서 Content-Length가 없으면 EGW00202(GW라우팅 오류)를 낸다.
     * (GET 조회는 본문이 없어 무관 → 조회는 되고 주문만 실패하던 원인.) hashkey도 동일 바이트로 계산해 정합 보장.
     */
    private <T> T postOrder(String path, Object body, String trId, Class<T> type, String label) {
        byte[] bodyBytes = serializeBody(body);
        String hash = hashkey(bodyBytes);
        Supplier<T> call = () -> restClient.post()
                .uri(path)
                .header("authorization", "Bearer " + tokenManager.getAccessToken())
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .header("hashkey", hash)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(bodyBytes)
                .retrieve()
                .body(type);
        return execute(call, "order:" + label);
    }

    /** 주문 본문을 JSON byte[]로 직렬화(Content-Length 명시용). */
    private byte[] serializeBody(Object body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ExternalApiException("KIS", "주문 본문 직렬화 실패: " + e.getMessage());
        }
    }

    /** 주문 본문 hashkey 발급(/uapi/hashkey). 위변조 방지용. 주문과 동일한 byte[]로 계산. */
    private String hashkey(byte[] bodyBytes) {
        Supplier<KisHashResponse> call = () -> restClient.post()
                .uri("/uapi/hashkey")
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(bodyBytes)
                .retrieve()
                .body(KisHashResponse.class);
        KisHashResponse r = execute(call, "hashkey");
        if (r == null || r.hash() == null || r.hash().isBlank()) {
            throw new ExternalApiException("KIS", "hashkey 발급 실패");
        }
        return r.hash();
    }

    /** KIS 호출 실행: 전역 rateGate + EGW00201(초당제한) 선형 백오프 재시도. */
    private <T> T execute(Supplier<T> call, String label) {
        for (int attempt = 1; ; attempt++) {
            try {
                rateGate();          // 전역 속도 제한 (스레드 간 경합 방지)
                return call.get();
            } catch (HttpStatusCodeException ex) {
                if (isRateLimited(ex) && attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_BACKOFF_MS * attempt);   // 선형 백오프
                    log.debug("KIS 초당 제한 재시도 label={} attempt={}", label, attempt);
                    continue;
                }
                log.error("KIS API 호출 오류 label={} (attempt={})", label, attempt, ex);
                throw new ExternalApiException("KIS", "KIS API 호출 중 오류가 발생했습니다.", ex);
            } catch (RestClientException ex) {
                log.error("KIS API 호출 오류 label={}", label, ex);
                throw new ExternalApiException("KIS", "KIS API 호출 중 오류가 발생했습니다.", ex);
            }
        }
    }

    private boolean isRateLimited(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null) return false;
        for (String code : RATE_LIMIT_CODES) {
            if (body.contains(code)) return true;
        }
        return false;
    }

    /** 전역 최소 호출 간격 보장 — 모든 KIS 호출을 직렬화해 합산 속도를 한도 아래로 유지. */
    private void rateGate() {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long wait = MIN_INTERVAL_MS - (now - lastCallAt);
            if (wait > 0) {
                sleep(wait);
                now = System.currentTimeMillis();
            }
            lastCallAt = now;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException("KIS", "재시도 대기 중 인터럽트", e);
        }
    }
}
