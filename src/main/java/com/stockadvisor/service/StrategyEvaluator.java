package com.stockadvisor.service;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.Recommendation;
import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisQuoteResponse;
import com.stockadvisor.notification.DiscordNotifier;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import com.stockadvisor.strategy.StrategyContext;
import com.stockadvisor.strategy.StrategyScope;
import com.stockadvisor.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 한 종목을 지정 scope 의 전략들로 평가해 가상매수를 기록하는 공통 평가기(종목당 독립 트랜잭션).
 *
 * <p>공시 경로(DISCLOSURE)와 워치리스트 스캔(MARKET_SCAN)이 동일 로직을 공유한다.
 * 공통 전제는 거래량 급증이며, 매도 의견은 전 전략 제외. dedup 은 (전략·종목·일자) 1회.</p>
 */
@Component
public class StrategyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StrategyEvaluator.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MarketSignalService marketSignalService;
    private final RecommendationService recommendationService;
    private final DiscordNotifier discordNotifier;
    private final CompanyRepository companyRepository;
    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final KisApiClient kisApiClient;
    private final SignalProperties properties;
    private final List<TradingStrategy> strategies;
    private final OrderService orderService;
    private final MarketRegimeService marketRegimeService;
    private final ExecutionCostModel executionCostModel;
    private final SectorValuationService sectorValuationService;
    private final StrategyPerformanceGate performanceGate;
    private final MarketBreadthService breadthService;   // 시장폭 집계(스캔 중) + 진입 태깅
    // 유니버스 스냅샷(전 종목 feature 수집, 연구용) — 필드주입(생성자 무churn). 미주입(테스트)이면 수집 생략.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UniverseSnapshotService universeSnapshotService;
    private final HotWatchService hotWatchService;        // 티어드 스캔 핫셋 도출(전수 스캔 중 volumeRatio 수집)

    // 대조군 집중: 검증된 승자는 대조군 수집 중단(부하↓), 손실·미검증은 수집(진단). 성과 저하 시 자동 재개.
    private final boolean controlFocusEnabled;
    private static final java.time.Duration CONTROL_FOCUS_TTL = java.time.Duration.ofMinutes(30);
    private final java.util.Map<String, Boolean> controlNeededCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.time.Instant controlFocusRefreshedAt;

    // 인버스 ETF 코드(하락장 수익용) — PER/PBR 무의미라 점수게이트 통과 + 독립 국면버킷("INVERSE")으로 태깅.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.inverse-codes:114800,251340}")
    private String inverseCsv;
    private volatile java.util.Set<String> inverseSet;

    private boolean isInverse(String code) {
        java.util.Set<String> s = inverseSet;
        if (s == null) {
            s = java.util.Arrays.stream(inverseCsv == null ? new String[0] : inverseCsv.split(","))
                    .map(String::trim).filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toSet());
            inverseSet = s;
        }
        return s.contains(code);
    }

    // 신선 악재뉴스 가드(2026-07-15 SK디앤디 사건): 반등 계열(C/D/J)은 "이유 없는 낙폭"이 전제 —
    // 최근 N분 내 악재 키워드 뉴스가 있는 하락은 "이유 있는 하락"이라 진입 보류(FRESH_BAD_NEWS).
    // 호재/중립 뉴스는 키워드 미매치로 통과(하락 중 호재 반등 시나리오 보존). 차단분은 대조군으로 추적돼 필터 자체가 검증됨.
    // 반등일 순추세 보류 가드(2026-07-22): 폭락 후 V자 초입 급반등일(당일 지수 ≥ surge% AND 기저 라벨 비강세)엔
    // 순추세 계열의 갭 되돌림 전멸이 2사이클 실측(7/15 12건 1승 −34,639 / 7/22 8건 0승 −81,672) → 당일 신규진입 보류.
    // 차단분은 reject=REBOUND_DAY 로 대조군 추적 → 가드 자체를 forward 검증. 역추세(C/D)·B·인버스는 무관.
    private static final java.util.Set<String> TREND_FAMILY =
            java.util.Set.of("SQUEEZE_BREAKOUT_H", "MA_TREND_F", "BREAKOUT_E", "OPENING_GAP_K");
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.rebound-day-guard-enabled:true}")
    private boolean reboundDayGuardEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.rebound-day-min-surge-pct:2.0}")
    private double reboundDayMinSurgePct = 2.0;

    // 전일 확정 국면으로 판단·태깅할 전략(2026-08-13) — 게이트의 동일 설정과 반드시 같은 값이어야 한다
    // (태깅 라벨 ≠ 버킷 라벨이면 게이트가 엉뚱한 표본 풀과 비교한다). 기본 K만.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.prior-day-regime-strategies:OPENING_GAP_K}")
    private String priorDayRegimeCsv = "OPENING_GAP_K";
    private java.util.Set<String> priorDayRegimeStrategies = java.util.Set.of("OPENING_GAP_K");

    @jakarta.annotation.PostConstruct
    void initPriorDayRegimeStrategies() {
        priorDayRegimeStrategies = PolicyGate.parseCsv(priorDayRegimeCsv);
    }

    // 과열 추격 필터(2026-07-24, HLB 계열 -75,214원 계기): 반등 계열(H/G/J)은 승자 체결강도가 패자보다
    // 일관되게 낮음(H 133vs165, G 133vs146, J 132vs196 — 7/16~20 3거래일) + 200+ 구간 전체 net 음수.
    // 진입 시 체결강도 ≥ 임계면 보류(EXEC_OVERHEAT) — "이미 과열된 반등을 추격하지 않는다". 차단분 대조군 추적.
    private static final java.util.Set<String> OVERHEAT_FAMILY =
            java.util.Set.of("SQUEEZE_BREAKOUT_H", "RSI_REVERSAL_G", "VALUE_REVERSAL_J");
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.exec-overheat-min-strength:200.0}")
    private double execOverheatMinStrength = 200.0;   // 0=비활성
    // 다일 과열 가드(2026-07-29): 흐름↑ 진입 분석(n1,419)에서 ret5d 단조 관계 실측 — 5일 −5% 이하 +0.17%/48승률 vs
    // +10% 초과 −1.21%. 반등 계열만 깨끗하게 갈림(H +0.17 vs −1.23, G +0.46 vs −0.87) → "5일 급등 후 반등 매수" 보류.
    // 체결강도(당일 과열)의 다일 버전 — 추격 금지가 시간축 양쪽에서 완성. K/B는 방향 반대라 미적용(전략 조건부 원칙).
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.ret5d-overheat-max-pct:10.0}")
    private double ret5dOverheatMaxPct = 10.0;   // 0=비활성

    private static final java.util.Set<String> REBOUND_FAMILY =
            java.util.Set.of("MEAN_REVERSION_C", "INDEX_RELATIVE_D", "VALUE_REVERSAL_J");
    @org.springframework.beans.factory.annotation.Value(
            "${stockadvisor.signal.bad-news-keywords:유상증자,감자,구조조정,관리종목,상장폐지,거래정지,소송,압수수색,횡령,배임,어닝쇼크,적자전환,리콜,화재}")
    private String badNewsKeywordsCsv = "유상증자,감자,구조조정,관리종목,상장폐지,거래정지,소송,압수수색,횡령,배임,어닝쇼크,적자전환,리콜,화재";
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.fresh-news-window-minutes:60}")
    private int freshNewsWindowMinutes = 60;
    private volatile java.util.List<String> badNewsKeywords;
    private java.util.List<String> badNewsKeywords() {
        java.util.List<String> k = badNewsKeywords;
        if (k == null) {
            k = badNewsKeywordsCsv == null ? java.util.List.of()
                    : java.util.Arrays.stream(badNewsKeywordsCsv.split(",")).map(String::trim)
                        .filter(x -> !x.isEmpty()).toList();
            badNewsKeywords = k;
        }
        return k;
    }

    // 인버스 재진입(2026-07-14): 오전 진입→청산 후 오후 재붕괴를 재포착. 쿨다운(분) 경과 + 실포지션 0이면
    // 같은 날 재평가 허용(새 표본 행·새 멱등키 사이클). 0=비활성(기존 하루 1회). 실측 배경: 7/14 10:14 인버스
    // 청산 직후 코스닥 -6% 재붕괴를 dedup(하루 1회) 때문에 놓침.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.inverse-reentry-minutes:20}")
    private int inverseReentryMinutes = 20;

    /** 인버스 재진입 자격(순수 판정, 테스트용 정적) — 쿨다운 경과 AND 활성 실포지션 없음. */
    static boolean inverseReentryEligible(List<TradeOutcome> priorRows, int cooldownMinutes,
                                          java.time.Instant now, boolean hasActivePosition) {
        if (cooldownMinutes <= 0 || hasActivePosition) return false;
        java.time.Instant lastEntry = priorRows.stream()
                .filter(o -> !o.isControl())
                .map(TradeOutcome::getAlertTime)
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).orElse(null);
        return lastEntry == null
                || java.time.Duration.between(lastEntry, now).toMinutes() >= cooldownMinutes;
    }

    public StrategyEvaluator(MarketSignalService marketSignalService,
                             RecommendationService recommendationService,
                             DiscordNotifier discordNotifier,
                             CompanyRepository companyRepository,
                             TradeOutcomeRepository tradeOutcomeRepository,
                             KisApiClient kisApiClient,
                             SignalProperties properties,
                             List<TradingStrategy> strategies,
                             OrderService orderService,
                             MarketRegimeService marketRegimeService,
                             ExecutionCostModel executionCostModel,
                             SectorValuationService sectorValuationService,
                             StrategyPerformanceGate performanceGate,
                             MarketBreadthService breadthService,
                             HotWatchService hotWatchService,
                             @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.control-focus-enabled:true}") boolean controlFocusEnabled) {
        this.marketSignalService = marketSignalService;
        this.recommendationService = recommendationService;
        this.discordNotifier = discordNotifier;
        this.companyRepository = companyRepository;
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.kisApiClient = kisApiClient;
        this.properties = properties;
        this.strategies = strategies;
        this.orderService = orderService;
        this.marketRegimeService = marketRegimeService;
        this.executionCostModel = executionCostModel;
        this.sectorValuationService = sectorValuationService;
        this.performanceGate = performanceGate;
        this.breadthService = breadthService;
        this.hotWatchService = hotWatchService;
        this.controlFocusEnabled = controlFocusEnabled;
    }

    /**
     * 이 전략에 대조군을 수집할지 — 검증된 승자(perf-gate 통과)면 불필요(false), 손실·미검증이면 수집(true).
     * 성과게이트 결과를 TTL 캐시(전략당 종목 스캔마다 DB조회 방지). 성과 저하로 게이트가 막히면 자동 재개.
     */
    private boolean needsControl(String strategy) {
        if (!controlFocusEnabled) return true;   // 집중 비활성 → 기존대로 전부 수집
        java.time.Instant now = java.time.Instant.now();
        if (controlFocusRefreshedAt == null
                || java.time.Duration.between(controlFocusRefreshedAt, now).compareTo(CONTROL_FOCUS_TTL) >= 0) {
            for (TradingStrategy s : strategies) {
                boolean validated;
                try {
                    validated = performanceGate.evaluate(s.name()).allowed();   // overall 검증(진입분 net)
                } catch (Exception e) {
                    validated = false;   // 판정 실패 → 수집 유지(안전)
                }
                controlNeededCache.put(s.name(), !validated);   // 검증 승자면 수집 불필요
            }
            controlFocusRefreshedAt = now;
        }
        return controlNeededCache.getOrDefault(strategy, true);
    }

    /**
     * 테스트/수동: 새 진입 알림 포맷(현재가 + 주문 상태/미매수 사유)을 예시 데이터로 Discord 발송. 실제 신호 아님.
     */
    public String sendTestAlert() {
        String msg = String.format("""
                📢 **[테스트] 추천 신호 감지: 삼성전자 (005930)**
                • 🧭 알고리즘: **거래량 선도 (B)**
                • 트리거: 장중 스캔(거래량 급증)
                • 💰 현재가: **70,000원**
                • 거래량(시간보정): 평균의 **2.9배** (당일 1,234,567주)
                • 등락률: **+0.76%%**
                • 투자의견: **매수**
                • 📊 추천점수: **62.0 / 100**
                • 🛒 주문: %s
                • 근거: 테스트 메시지 — 실제 신호가 아닙니다.

                _%s_""",
                "⛔ 미매수: [KOSPI·중립·60분] 국면표본부족(4/30)+fallback미달 (예시 사유)",
                Disclaimer.SHORT);
        discordNotifier.send(msg);
        return msg;
    }

    /**
     * @param stockCode    종목코드
     * @param disclosureId 근거 공시 id (스캔이면 null)
     * @param catalyst     메시지에 표시할 촉매(공시명 또는 "장중 스캔")
     * @param scope        평가할 전략 범위
     * @return 실시간 알림 발송 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int evaluateStock(String stockCode, Long disclosureId, String catalyst, StrategyScope scope) {
        List<TradingStrategy> scoped = strategies.stream().filter(s -> s.scope() == scope).toList();
        if (scoped.isEmpty()) {
            return 0;
        }
        String alertDate = ZonedDateTime.now(SEOUL).format(YYYYMMDD);

        boolean inverse = isInverse(stockCode);   // 인버스: 하락장에 급등 → 모멘텀 전략이 롱으로 잡음
        // 아직 오늘 미기록 전략만 (전략·종목·일자 1회). 단 인버스는 "진입(entry) 행"이 이미 있을 때만 스킵 —
        // control만 있으면 재평가해 오후 방향전환(오전 하락→오후 급등) 시 진입으로 승격(control→entry). 일반주는 기존 strict.
        List<TradingStrategy> pending = scoped.stream()
                .filter(s -> {
                    List<TradeOutcome> ex = tradeOutcomeRepository
                            .findByStrategyAndStockCodeAndAlertDate(s.name(), stockCode, alertDate);
                    if (ex.isEmpty()) return true;
                    if (!inverse) return false;                          // 일반주: 이미 기록 → 스킵
                    if (ex.stream().allMatch(TradeOutcome::isControl)) return true; // 인버스: control만 → 재평가(승격 후보)
                    // 인버스 재진입: 진입 행이 있어도 쿨다운 경과 + 실포지션 0이면 새 사이클 허용(오후 재붕괴 재포착)
                    return inverseReentryEligible(ex, inverseReentryMinutes,
                            java.time.Instant.now(), orderService.hasActivePosition(stockCode));
                })
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }

        // 동시호가/휴장이면 항상 종료(그 시간대엔 진입 없음).
        Optional<SignalResult> signalOpt = marketSignalService.evaluate(stockCode);
        if (signalOpt.isEmpty()) {
            return 0;
        }
        SignalResult signal = signalOpt.get();
        // 시장폭 집계 — 반드시 볼륨 게이트 이전에 전 종목 등락률 기록(급증 종목만 세면 편향). 스캔 중에만 누산(record가 판정).
        breadthService.record(stockCode, signal.changeRate());
        // 티어드 스캔 핫셋 — 전수 스캔 중 거래량배수 + 볼륨무관 이벤트(MA돌파/RSI반등/수축돌파) 수집(볼륨게이트 전, active일 때만).
        boolean eventTriggered = signal.maCrossUp() || signal.rsiCrossUp() || signal.squeezeBreakout();
        hotWatchService.record(stockCode, signal.volumeRatio(), eventTriggered);
        // 유니버스 스냅샷 — 전 종목 feature + 사후 타깃 수집(볼륨 게이트 전이어야 '분모'가 성립).
        // ⚠️ 여긴 quote 조회 전이라 PER/PBR/시총/업종은 못 담는다(전 종목 quote는 스냅샷당 ~1,500콜) → 일봉 기반만.
        // 실패는 격리 — 연구용 수집이 매매 경로를 절대 안 깨뜨린다.
        if (universeSnapshotService != null) {
            try { universeSnapshotService.record(stockCode, signal); } catch (Exception ignore) { /* 수집 실패 무시 */ }
        }
        // 공통 전제: 거래량 급증. 단 '볼륨 무관' 전략이 자기 preScreen(값싼 1차필터)로 이 종목을 원하면 미급증이어도 진행.
        // preScreen이 대상을 좁히므로(인버스=인버스코드만, MA=상향돌파만) 전 종목 비싼-평가 폭증이 안 남.
        boolean noVolumeBypass = pending.stream()
                .anyMatch(s -> !s.requiresVolumeSpike() && s.preScreen(stockCode, signal));
        if (!signal.volumeSpike() && !noVolumeBypass) {
            return 0;
        }

        // 저가주(동전주/정리매매 다수) 제외
        if (signal.closePrice() < properties.minPrice()) {
            return 0;
        }
        // 관리종목·투자경고·정리매매 제외 (KIS 현재가 캐시 활용)
        KisQuoteResponse.Output quote = kisApiClient.fetchCurrentQuote(stockCode).output();
        if (quote == null || !quote.isHealthy()) {
            log.debug("건전성 미달(관리/경고/정리매매) 제외 stockCode={}", stockCode);
            return 0;
        }
        // 유동성/슬리피지 필터(레이어 4): 거래대금 하한 미달 또는 스프레드 과대 종목은 거래 유니버스에서 제외.
        // 슬리피지는 실측 호가 스프레드 우선, 호가 미가용 시 tick 추정으로 degrade.
        // ⚠️ 거래량은 quote(1h 캐시) 말고 일봉의 당일 누적거래량(signal, 비캐시) 사용 — 08:40 섹터배치가
        //    개장 전 시세(누적거래량 0)를 전 종목 캐싱해, 09:00~09:40 전 종목이 거래대금 0으로 걸러지던 버그.
        long turnoverKrw = executionCostModel.turnoverKrw(signal.todayVolume(), signal.closePrice());
        Double entrySlippagePct = null;
        try {
            KisApiClient.Spread sp = kisApiClient.fetchAskingPrice(stockCode);
            if (sp != null) entrySlippagePct = executionCostModel.roundTripSlippagePctFromSpread(sp.bestAsk(), sp.bestBid());
        } catch (Exception ignore) { /* 호가 조회 실패 → tick fallback */ }
        if (entrySlippagePct == null) {
            entrySlippagePct = executionCostModel.estimateRoundTripSlippagePct(signal.closePrice());
        }
        if (!executionCostModel.tradable(turnoverKrw, entrySlippagePct)) {
            log.debug("유동성/슬리피지 제외 stockCode={} 거래대금={} 슬리피지={}%", stockCode, turnoverKrw, entrySlippagePct);
            return 0;
        }

        Recommendation rec = recommendationService.computeRecommendation(stockCode);
        // 매도 제외 — 단, 인버스 ETF는 PER/PBR 기반 추천이 무의미하므로 SELL 판정을 적용하지 않음
        if (!inverse && rec.getRecommendationType() == RecommendationType.SELL) {
            return 0;
        }

        // 진입 시점 feature (분석용)
        double per = parseDouble(quote.per());
        double pbr = parseDouble(quote.pbr());
        long marketCap = parseLong(quote.marketCap());
        // 인버스는 독립 버킷("INVERSE")으로 태깅 — 일반주와 섞여 수익이 희석되지 않게(perf-gate 격리). 국면조건부 미적용(전략 자체가 방향 베팅).
        String market = inverse ? "INVERSE" : classifyMarket(quote.marketName());
        String sector = quote.sectorName();
        // 진입 시점 해당 시장 지수 등락률(국면) — 인버스는 지수상대(D) 대상 아님 → null
        Double marketChange = null;
        if (!inverse) {
            try {
                if ("KOSPI".equals(market)) marketChange = kisApiClient.fetchIndexChangeRate("0001");
                else if ("KOSDAQ".equals(market)) marketChange = kisApiClient.fetchIndexChangeRate("1001");
            } catch (Exception ignore) { /* 지수 조회 실패 시 null */ }
        }
        // 진입 시점 시장 국면(MA기반). 인버스는 국면 태그 없음(null) → 게이트 비국면분리(전체 인버스 표본 풀링).
        String entryTrend = null;
        // 전일 확정 국면(2026-08-13) — K(개장갭)처럼 '어제까지 국면 + 오늘 시초가 갭'이 정의인 전략용.
        // 태깅과 게이트 버킷팅이 같은 라벨을 써야 하므로 여기서도 별도로 산출한다(불일치 시 엉뚱한 표본과 비교됨).
        String priorDayTrend = null;
        if (!inverse) {
            try {
                var t = marketRegimeService.trendOf(market);
                entryTrend = t == null ? null : t.name();
            } catch (Exception ignore) { /* 국면 산출 실패 시 null */ }
            try {
                var p = marketRegimeService.priorDayTrendOf(market);
                priorDayTrend = p == null ? null : p.name();
            } catch (Exception ignore) { /* 전일 국면 산출 실패 시 null → 아래에서 entryTrend로 degrade */ }
            if (priorDayTrend == null) priorDayTrend = entryTrend;
        }
        // 진입 시점 지수 장중 흐름(프록시 분봉 최근 10/60분 모멘텀) — "지금 오르는 중/빠지는 중" 측정용 태깅.
        // 시장별 캐시(TTL)라 스캔당 시장 1콜. 인버스는 국면 태그 없음과 동일하게 제외(null).
        MarketRegimeService.IntradayFlow flow = null;
        if (!inverse && market != null) {
            try { flow = marketRegimeService.intradayFlow(market); } catch (Exception ignore) { /* 실패 시 null */ }
        }
        // 인버스는 점수게이트(≥40) 통과 위해 점수 강제 통과 + 매수의견(모멘텀 전략이 순수 급등만으로 판단하도록)
        double recScore = inverse ? 100.0 : rec.getScore();
        RecommendationType recType = inverse ? RecommendationType.BUY : rec.getRecommendationType();
        // 업종 대비 저평가(전략J용) — PER 또는 PBR이 업종 중앙값 미만. 캐시 조회(추가 KIS 0). 판정불가면 false.
        boolean undervalued = false;
        if (!inverse) {
            SectorValuationService.SectorStat stat = sectorValuationService.statOf(sector);
            if (stat != null) {
                boolean perCheap = per > 0 && stat.medianPer() > 0 && per < stat.medianPer();
                boolean pbrCheap = pbr > 0 && stat.medianPbr() > 0 && pbr < stat.medianPbr();
                undervalued = perCheap || pbrCheap;
            }
        }

        // 전략 판단 컨텍스트 (지수 등락률 포함 — 전략D 지수상대 잔차 계산용)
        // 지수 장중 흐름(mom30)도 실어 D "흐름↓ 스킵" 필터에 사용(위에서 이미 조회한 flow 재사용, 추가 KIS 0).
        Double indexMom30 = (flow != null && flow.available()) ? flow.mom30Pct() : null;
        // 지수 당일 갭%(K "지수 통째 갭업일 제외"용) — 시장별 당일 1회 캐시라 스캔 부하 무시할 수준(1콜/시장/일).
        Double indexGapPct = null;
        if (!inverse && market != null) {
            try { indexGapPct = marketRegimeService.indexGapPct(market); } catch (Exception ignore) { /* 실패 시 null */ }
        }
        StrategyContext ctx = new StrategyContext(stockCode, signal, recScore, recType, marketChange, inverse, undervalued, entryTrend, indexMom30, indexGapPct);
        // 전일 확정 국면을 쓰는 전략(K)용 컨텍스트 — entryTrend만 다르고 나머지는 동일.
        StrategyContext ctxPrior = java.util.Objects.equals(entryTrend, priorDayTrend) ? ctx
                : new StrategyContext(stockCode, signal, recScore, recType, marketChange, inverse, undervalued, priorDayTrend, indexMom30, indexGapPct);

        int alerts = 0;
        // 뉴스·체결강도 feature(진입 시 태깅) — 종목당 각 1콜 lazy(첫 진입 때만 조회, 대조군은 부하상 미태깅)
        Integer newsCnt1h = null, newsAgeMin = null;
        Double execStrength = null;
        boolean newsFetched = false;
        boolean strengthFetched = false;   // 체결강도 lazy 1콜 — 과열 가드와 진입 태깅이 공유
        java.util.List<com.stockadvisor.market.dto.KisNewsResponse.NewsItem> newsItems = null;

        for (TradingStrategy strategy : pending) {
            // 볼륨 필요 전략은 거래량 미급증이면 평가/기록 안 함(볼륨무관 전략만 우회 경로로 진입).
            if (strategy.requiresVolumeSpike() && !signal.volumeSpike()) continue;
            // K 등은 전일 확정 국면 컨텍스트로 판단·태깅 — 게이트 버킷팅과 같은 라벨을 써야 표본 비교가 정합.
            StrategyContext sctx = priorDayRegimeStrategies.contains(strategy.name()) ? ctxPrior : ctx;
            String reject = strategy.rejectReason(sctx);   // null이면 진입
            // 반등일 순추세 보류 — 시장별 판정(60s 캐시 재사용, 추가 KIS 0). 인버스는 시장태그 없음 → 미적용.
            if (reject == null && reboundDayGuardEnabled && !inverse && market != null
                    && TREND_FAMILY.contains(strategy.name())
                    && marketRegimeService.isReboundDay(market, reboundDayMinSurgePct)) {
                reject = "REBOUND_DAY";
                log.info("[{}] 반등일 순추세 보류 [{}] — 당일 {} 급등 + 기저 비강세(V자 초입)", strategy.name(), stockCode, market);
            }
            // 과열 추격 가드 — 반등 계열(H/G/J) 진입 직전 후보만 체결강도 1콜(진입 태깅과 공유). 실패 시 생략(degrade open).
            if (reject == null && execOverheatMinStrength > 0 && OVERHEAT_FAMILY.contains(strategy.name())) {
                if (!strengthFetched) {
                    strengthFetched = true;
                    try {
                        execStrength = kisApiClient.fetchCcnl(stockCode).latestStrength();
                    } catch (Exception ex) {
                        log.debug("체결강도 조회 실패(가드 생략) stockCode={}: {}", stockCode, ex.getMessage());
                    }
                }
                if (execStrength != null && execStrength >= execOverheatMinStrength) {
                    reject = "EXEC_OVERHEAT";
                    log.info("[{}] 과열 추격 보류 [{}] — 체결강도 {}", strategy.name(), stockCode, String.format("%.0f", execStrength));
                }
            }
            // 다일 과열 가드 — 반등 계열이 5일 급등 종목을 사려 할 때 보류(이미 계산된 ret5d 재사용, KIS 0)
            if (reject == null && ret5dOverheatMaxPct > 0 && OVERHEAT_FAMILY.contains(strategy.name())
                    && signal.ret5dPct() > ret5dOverheatMaxPct) {
                reject = "RET5D_OVERHEAT";
                log.info("[{}] 다일 과열 보류 [{}] — 최근 5일 {}%", strategy.name(), stockCode, String.format("%.1f", signal.ret5dPct()));
            }
            // 신선 악재뉴스 가드 — 반등 계열이 하락 중 진입하려 할 때만(진입 직전 후보 한정 → 뉴스 1콜, 진입 태깅과 공유)
            if (reject == null && REBOUND_FAMILY.contains(strategy.name()) && signal.changeRate() < 0) {
                if (!newsFetched) {
                    newsFetched = true;
                    try {
                        newsItems = kisApiClient.fetchNewsTitles(stockCode).output();
                        var nf = com.stockadvisor.market.dto.KisNewsResponse.features(newsItems, ZonedDateTime.now(SEOUL), 60);
                        newsCnt1h = nf.recentCount();
                        newsAgeMin = nf.latestAgeMin();
                    } catch (Exception ex) {
                        log.debug("뉴스 조회 실패(가드 생략) stockCode={}: {}", stockCode, ex.getMessage());
                    }
                }
                String badKw = com.stockadvisor.market.dto.KisNewsResponse.freshBadNews(
                        newsItems, ZonedDateTime.now(SEOUL), freshNewsWindowMinutes, badNewsKeywords());
                if (badKw != null) {
                    reject = "FRESH_BAD_NEWS";
                    log.info("[{}] 신선 악재뉴스 가드 — 진입 보류 [{}] 키워드='{}'", strategy.name(), stockCode, badKw);
                }
            }
            // 대조군 기록: 전역 controlTracking + 전략별 tracksControl + 집중(검증승자 제외) 모두 충족해야.
            // REBOUND_DAY 차단분은 전략의 대조군 설정(tracksControl/집중)과 무관하게 기록(2026-07-23) —
            // H/F 등 control-off 전략이 가드에 막히면 흔적이 없어 "가드가 막은 게 옳았나"를 검증할 수 없다.
            // 반등일 하루 × TREND_FAMILY 한정이라 추적 부담 제한적. 가드 유지/완화 판단의 유일한 데이터 소스.
            // NOT_FRESH/WEAK_BREAKOUT(F 보완 필터)·NOT_CONFIRMED(H 돌파확인 필터)도 tracksControl 무관 강제 기록 —
            // F/H는 control-off라 흔적이 없으면 "필터가 막은 게 옳았나"(ENTERED vs 필터탈락 net)를 forward 검증할 수 없다(REBOUND_DAY와 동일 취지).
            // INDEX_GAP_DAY(K 지수갭업일 제외, 2026-08-14)도 동일 — K는 control-off인데다 지수 갭업일 자체가 드물어
            // 강제 기록하지 않으면 "차단이 옳았나"를 판정할 표본이 영영 안 쌓인다(지수갭일 × K 한정이라 부담 작음).
            boolean trackControl = properties.controlTracking()
                    && ((strategy.tracksControl() && needsControl(strategy.name()))
                        || "REBOUND_DAY".equals(reject) || "EXEC_OVERHEAT".equals(reject)
                        || "RET5D_OVERHEAT".equals(reject)
                        || "NOT_FRESH".equals(reject) || "WEAK_BREAKOUT".equals(reject)
                        || "NOT_CONFIRMED".equals(reject) || "INDEX_GAP_DAY".equals(reject));
            if (reject != null && !trackControl) {
                continue;   // 미진입 + 대조군 미추적 → 아무것도 기록 안 함
            }
            // 인버스 승격: 기존 (control) 행이 있으면 재사용해 갱신(행 id 유지). 일반주는 항상 신규(pending 필터가 기존 배제).
            List<TradeOutcome> priorRows = inverse
                    ? tradeOutcomeRepository.findByStrategyAndStockCodeAndAlertDate(strategy.name(), stockCode, alertDate)
                    : List.of();
            // 인버스가 아직 진입조건 미충족(reject) + 이미 기록 존재 → 중복 control 기록 없이 그대로 둠.
            if (reject != null && !priorRows.isEmpty()) {
                continue;
            }
            TradeOutcome controlRow = priorRows.stream().filter(TradeOutcome::isControl).findFirst().orElse(null);
            long entryCount = priorRows.stream().filter(o -> !o.isControl()).count();
            boolean promote = controlRow != null && entryCount == 0 && reject == null;   // control→entry 승격(첫 진입)
            boolean reentry = entryCount > 0 && reject == null;   // 재진입 — 승격이 아니라 새 표본 행(기존 사이클 보존)
            int cycle = (int) entryCount + 1;                      // 주문 멱등키 사이클(1=기본, 2~=재진입)
            TradeOutcome outcome = promote ? controlRow
                    : new TradeOutcome(strategy.name(), disclosureId, stockCode, alertDate, signal.closePrice());
            if (promote) {
                outcome.promoteFromControl(signal.closePrice());   // 낡은 control → 현재가 진입으로 전환
            }
            outcome.recordEntryFeatures(signal.changeRate(), signal.volumeRatio(), rec.getScore(),
                    per, pbr, market, marketCap, sector, marketChange);
            outcome.setEntryMarketTrend(sctx.entryTrend());   // 전략별 국면 기준(K=전일 확정)으로 태깅 — 게이트 버킷과 일치
            outcome.setEntrySlippagePct(entrySlippagePct);
            if (flow != null && flow.available()) outcome.setEntryIntradayFlow(flow.mom10Pct(), flow.mom30Pct(), flow.mom60Pct());
            outcome.setEntrySetupFeatures(signal.atrPct(), signal.distFromHighPct(), signal.ret5dPct());   // 셋업(종목 상태) feature
            outcome.setEntryGapFeatures(signal.gapPct(), indexGapPct);   // 개장 갭 축(K 갭 상한 튜닝의 전제) — 추가 KIS 0
            outcome.setEntryBreadth(breadthService.overallBreadthPct(), breadthService.breadthPct(market));   // 진입 시점 시장폭(직전 스캔)

            if (reject != null) {
                // 대조군(미진입): 알림·주문 없이 수익률 horizon만 추적 → 필터 검증/개선용
                outcome.markControl(reject);
                tradeOutcomeRepository.save(outcome);
                log.debug("[{}] 대조군 기록 [{}] 사유={}", strategy.name(), stockCode, reject);
                continue;
            }

            // 진입 — 뉴스·체결강도 feature 태깅(측정 먼저): 종목당 각 1콜(같은 종목 다전략 진입은 재사용), 실패해도 진입을 안 깨뜨림.
            if (!newsFetched) {
                newsFetched = true;
                try {
                    newsItems = kisApiClient.fetchNewsTitles(stockCode).output();
                    var nf = com.stockadvisor.market.dto.KisNewsResponse.features(newsItems, ZonedDateTime.now(SEOUL), 60);
                    newsCnt1h = nf.recentCount();
                    newsAgeMin = nf.latestAgeMin();
                } catch (Exception ex) {
                    log.debug("뉴스 조회 실패(태깅 생략) stockCode={}: {}", stockCode, ex.getMessage());
                }
            }
            if (!strengthFetched) {
                strengthFetched = true;
                try {
                    execStrength = kisApiClient.fetchCcnl(stockCode).latestStrength();
                } catch (Exception ex) {
                    log.debug("체결강도 조회 실패(태깅 생략) stockCode={}: {}", stockCode, ex.getMessage());
                }
            }
            outcome.setEntryNews(newsCnt1h, newsAgeMin);
            outcome.setEntryExecStrength(execStrength);
            tradeOutcomeRepository.save(outcome);
            log.info("[{}] {} [{}] 매수가={} 등락률={}% 거래량배수={}",
                    strategy.name(), promote ? "인버스 control→entry 승격" : (reentry ? "인버스 재진입 " + cycle + "차" : "가상매수"), stockCode, signal.closePrice(),
                    String.format("%.2f", signal.changeRate()), String.format("%.2f", signal.volumeRatio()));
            // 주문을 먼저 제출해 결과(매수/스킵 사유)를 알림에 담는다.
            // 주문 연결: PolicyGate가 enabled/mode/한도 검증 (dry-run이면 기록만, 미설정이면 차단).
            // 주문 실패는 가상매수 기록/알림과 독립 — 절대 신호 처리를 깨지 않게 격리.
            OrderService.OrderResult orderResult =
                    submitOrderSafely(strategy.name(), stockCode, sector, market, signal.closePrice(), alertDate, cycle);
            // 신호 알림: 전략 alerts 플래그 OR 실주문 접수(2026-07-24 — H가 게이트 승격 후 "접수 알림만 오고
            // 신호 알림이 없는" 불일치 실측). 섀도우/DRY_RUN 진입은 종전대로 조용(알림 폭주 방지).
            boolean liveSubmitted = orderResult != null
                    && (orderResult.status() == OrderService.ResultStatus.SUBMITTED
                        || orderResult.status() == OrderService.ResultStatus.PENDING_APPROVAL);
            if (strategy.alerts() || liveSubmitted) {
                discordNotifier.send(buildMessage(stockCode, catalyst, signal, rec, strategy, orderResult));
                alerts++;
            }
        }
        return alerts;
    }

    /** 진입 주문 제출(격리). 주문 실패가 신호 처리/가상매수를 깨지 않도록 예외를 삼킨다. 결과는 알림에 담는다(오류 시 null). */
    private OrderService.OrderResult submitOrderSafely(String strategy, String stockCode, String sector, String market, long price, String alertDate, int cycle) {
        try {
            // 재진입(cycle≥2)은 멱등키에 사이클 접미사 — 같은 날 첫 사이클(FILLED)과 충돌하지 않게(부분 유니크 인덱스 정합).
            String idem = strategy + ":" + stockCode + ":" + alertDate + (cycle > 1 ? "#" + cycle : "");
            OrderService.OrderResult r = orderService.submitEntry(
                    strategy, stockCode, sector, market, price, idem);
            if (r.isAccepted()) {
                log.info("[주문] [{}] {} → {} (id={})", strategy, stockCode, r.status(), r.orderId());
            } else {
                log.debug("[주문] [{}] {} 미실행: {}", strategy, stockCode, r.message());
            }
            return r;
        } catch (Exception ex) {
            log.warn("[주문] [{}] {} 시도 중 오류(무시): {}", strategy, stockCode, ex.getMessage());
            return null;
        }
    }

    /** 알림에 넣을 주문 상태 한 줄 — 매수됐으면 상태, 아니면 스킵 사유를 명시. */
    private String orderStatusLine(OrderService.OrderResult r) {
        if (r == null) return "⚠️ 주문 상태 미상(오류)";
        return switch (r.status()) {
            case SUBMITTED -> "✅ 실주문 접수";
            case DRY_RUN -> "🧪 DRY_RUN(모의) — 실주문 없음";
            case PENDING_APPROVAL -> "⏳ 수동승인 대기";
            case REJECTED -> "⛔ 미매수: " + r.message();
            case FAILED -> "⚠️ 주문 실패: " + r.message();
        };
    }

    private String classifyMarket(String marketName) {
        if (marketName == null) return null;
        if (marketName.contains("KOSPI")) return "KOSPI";
        if (marketName.contains("KSQ") || marketName.contains("KOSDAQ")) return "KOSDAQ";
        return marketName;
    }

    private double parseDouble(String v) {
        if (v == null || v.isBlank()) return 0;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try { return Long.parseLong(v.replace(",", "").trim()); } catch (NumberFormatException e) { return 0; }
    }

    private String buildMessage(String stockCode, String catalyst, SignalResult s,
                                Recommendation rec, TradingStrategy strategy, OrderService.OrderResult order) {
        String name = companyRepository.findById(stockCode).map(Company::getName).orElse(stockCode);
        return String.format("""
                📢 **추천 신호 감지: %s (%s)**
                • 🧭 알고리즘: **%s**
                • 트리거: %s
                • 💰 현재가: **%,d원**
                • 거래량(시간보정): 평균의 **%.1f배** (당일 %,d주)
                • 등락률: **%+.2f%%**
                • 투자의견: **%s**
                • 📊 추천점수: **%.1f / 100**
                • 🛒 주문: %s
                • 근거: %s

                _%s_""",
                name, stockCode,
                strategy.label(),
                catalyst,
                s.closePrice(),
                s.volumeRatio(), s.todayVolume(),
                s.changeRate(),
                rec.getRecommendationType().korean(), rec.getScore(),
                orderStatusLine(order),
                rec.getReason(),
                Disclaimer.SHORT);
    }
}
