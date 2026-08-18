package com.stockadvisor.service;

import com.stockadvisor.config.properties.StrategyPerformanceProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import com.stockadvisor.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 전략 성과 기반 LIVE 진입 게이트.
 *
 * <p>전략별로 최근 {@code lookbackDays}일 가상매수 결과의 <b>net 평균수익률</b>(왕복 매매비용 차감)을 계산해,
 * 기준({@code minNetAvgPct}) 미달이거나 표본({@code minSamples}) 부족이면 <b>실주문(LIVE)을 차단</b>한다.
 * 알림·가상매수는 {@link StrategyEvaluator}에서 게이트와 무관하게 먼저 기록되므로, 차단 중에도 그림자 성과가
 * 계속 쌓여 회복 시 자동으로 다시 열린다.</p>
 *
 * <p>net 계산식은 {@link OutcomeAnalysisService}와 동일: {@code (price-buyPrice)/buyPrice*100 - roundTripCostPct}.</p>
 *
 * <p><b>레이어 2 — 국면조건부</b>: {@code regimeConditional}이면 {@link MarketRegimeService}의 현재 전체 시장 국면과
 * <b>같은 국면에서 진입한 표본</b>만으로 net평균을 계산한다. → 강세장에선 강세장 성과로, 약세장에선 약세장 성과로
 * 게이팅되어, 강세장에 강한 전략(모멘텀)·약세에 강한 전략(역추세)이 자동으로 해당 국면에서만 켜진다.
 * 현재 국면 미산출(데이터 부족) 시 국면 무관 전체 표본으로 fallback.</p>
 */
@Service
public class StrategyPerformanceGate {

    private static final Logger log = LoggerFactory.getLogger(StrategyPerformanceGate.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int EXIT_MARK_TOLERANCE_MIN = 30;   // 권장 청산마크 ±이 범위 내 근접 마크를 대체 사용(표본 기근 보정)

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final StrategyPerformanceProperties props;
    private final MarketRegimeService marketRegimeService;
    private final ExecutionCostModel executionCostModel;
    private final StrategyHoldTimeProvider holdTimeProvider;       // horizon="exit"일 때 전략별 권장 청산 마크(분)
    private final OutcomeSampleRepository outcomeSampleRepository;  // 그 마크의 실제 청산가 조회
    private final double roundTripCostPct;
    private final java.util.Set<String> swingStrategies;   // 스윙 전략은 청산 horizon(swingHorizon)으로 게이트 검증
    private final String swingHorizon;                       // 스윙 전략 게이트 검증 horizon(기본 nextClose=D+1)
    private final List<String> strategyNames;                // 가시화용 전략명(등록된 TradingStrategy 빈에서 동적 — 새 전략 자동 포함)
    private final java.util.Set<String> priorDayRegimeStrategies;   // 전일 확정 국면으로 버킷팅할 전략(K 등)
    private static final String HYST_TAG = " ·히스테리시스(닫기바 유지)";
    private static final String REFILTER_TAG = " ·재필터";

    /**
     * 히스테리시스 키 — 판정에 실제로 쓰인 <b>표본 풀</b>(전략·시장·국면·흐름)을 그대로 식별한다(2026-08-13).
     * 국면 라벨이 장중에 바뀌면 표본도 net도 다른 버킷이 되므로 상태를 물려받으면 안 된다.
     * ⚠️ 키가 잘게 쪼개진 만큼 각 버킷의 상태는 더 드물게 갱신된다(진동 억제 효과 ↓, 정확도 ↑ 트레이드오프).
     */
    private static String hystKey(String strategy, String market, String regimeName, String flowTag) {
        return strategy + ":" + (market == null ? "_" : market)
                + ":" + (regimeName == null ? "_" : regimeName) + ":" + flowTag;
    }

    // 히스테리시스 상태(2026-08-06): (strategy:market:regime:flow) → 현재 오픈 여부. 열 땐 min-net, 열려있으면 close-net까지 유지.
    // 인메모리(재기동 시 리셋 → 기본 closed=fail-safe). 시뮬(forcedRegime≠null)만 갱신 제외.
    // '열림'은 엄격(흐름·국면) 버킷 판정 통과로만 획득하고, 그 판정에 도달 못 한 경로(클러스터 차단·표본부족·
    // fallback·부트스트랩)는 모두 닫힘으로 명시한다(2026-08-13). ⚠️ 이전엔 fallback/부트스트랩이 상태를 갱신하지
    // 않아 stale 유지 — 국면 라벨이 장중에 바뀌어 버킷이 교체되면 옛 버킷에서 얻은 '열림'이 남아, 표본도 net도
    // 다른 새 버킷에 완화된 닫기바(close-net)를 잘못 적용했다(K 실측: 오전 KOSDAQ 중립 표본부족 → 강세 복귀).
    private final java.util.Map<String, Boolean> openState = new java.util.concurrent.ConcurrentHashMap<>();

    public StrategyPerformanceGate(TradeOutcomeRepository tradeOutcomeRepository,
                                   StrategyPerformanceProperties props,
                                   MarketRegimeService marketRegimeService,
                                   ExecutionCostModel executionCostModel,
                                   StrategyHoldTimeProvider holdTimeProvider,
                                   OutcomeSampleRepository outcomeSampleRepository,
                                   List<TradingStrategy> strategies,
                                   @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                                   @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv,
                                   @Value("${stockadvisor.trading.swing-horizon:nextClose}") String swingHorizon,
                                   // 교차거래일 요건 — 버킷의 한 거래일 점유율이 이 %를 넘으면 단일일 클러스터로 보고 LIVE 졸업 차단. 0=비활성.
                                   @Value("${stockadvisor.trading.perf-gate.max-single-day-share-pct:80}") double maxSingleDaySharePct,
                                   // 전략별 net 재검증 시작일 — 로직/임계 변경 시 구표본 제외("STRATEGY:yyyyMMdd,..."). 변경일 이후 표본만 채점.
                                   @Value("${stockadvisor.trading.perf-gate.strategy-since:}") String strategySinceCsv,
                                   // 부트스트랩 허용 전략(csv) — since 리셋 등으로 표본 미달이어도 축소사이징 실주문(재검증 중 완전정지 방지). 기본 빈값=fail-closed.
                                   @Value("${stockadvisor.trading.perf-gate.bootstrap-strategies:}") String bootstrapStrategiesCsv,
                                   // 구표본 자동 재필터 — 조이는 필터 추가 시 태깅된 feature 임계로 구표본 중 새 필터 통과분만 채점(since 리셋의 정밀판).
                                   @Value("${stockadvisor.trading.perf-gate.refilter:}") String refilterCsv,
                                   // 전일 확정 국면으로 버킷팅할 전략(csv) — 정의상 '어제까지 국면'을 보는 K(개장갭) 등.
                                   // 장중에 흔들리는 라벨로 버킷을 잡으면 하루에도 여러 번 다른 표본 풀로 판정된다(2026-08-13 K 실측).
                                   @Value("${stockadvisor.trading.prior-day-regime-strategies:OPENING_GAP_K}")
                                   String priorDayRegimeCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.props = props;
        this.marketRegimeService = marketRegimeService;
        this.executionCostModel = executionCostModel;
        this.holdTimeProvider = holdTimeProvider;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.strategyNames = strategies.stream().map(TradingStrategy::name).distinct().sorted().toList();
        this.roundTripCostPct = roundTripCostPct;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
        this.swingHorizon = swingHorizon;
        this.maxSingleDaySharePct = maxSingleDaySharePct;
        this.strategySince = parseSince(strategySinceCsv);
        this.bootstrapStrategies = PolicyGate.parseCsv(bootstrapStrategiesCsv);
        this.refilters = GateRefilter.parse(refilterCsv);
        this.priorDayRegimeStrategies = PolicyGate.parseCsv(priorDayRegimeCsv);
    }

    /** 전략 → 구표본 재필터 술어(조이는 필터 임계). */
    private final java.util.Map<String, GateRefilter> refilters;

    private final double maxSingleDaySharePct;
    /** 전략 → net 재검증 시작일(yyyyMMdd). 로직 변경 시 구표본 제외용. */
    private final java.util.Map<String, String> strategySince;
    /** since 리셋 후 표본 미달이어도 축소사이징 실주문 허용할 전략(재검증 다리). */
    private final java.util.Set<String> bootstrapStrategies;

    /** "A:20260812,B:20260810" → {A:20260812, B:20260810}. */
    private static java.util.Map<String, String> parseSince(String csv) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (csv != null) {
            for (String pair : csv.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) m.put(kv[0].trim(), kv[1].trim());
            }
        }
        return m;
    }

    /**
     * @param allowed      LIVE 실주문 허용 여부, netAvgReturnPct 표본 없으면 null
     * @param regimeTrend  평가에 적용된 시장 국면(국면조건부일 때, 미적용/미산출이면 null)
     * @param market       국면 판정에 쓴 시장(KOSPI/KOSDAQ, overall이면 null)
     * @param fallback     국면무관 fallback 경로로 허용됐는지 — true면 {@code OrderService}가 축소사이징 적용(③)
     */
    public record GateDecision(String strategy, boolean allowed, String reason,
                               int samples, Double netAvgReturnPct, String regimeTrend, String market,
                               boolean fallback) {}

    /** ③ fallback 진입 시 적용할 사이징 배수(축소진입) — {@code OrderService.submitEntry}가 소비. */
    public double fallbackSizeMult() {
        return props.fallbackSizeMult();
    }

    /** INVERSE 부트스트랩 진입 사이징 배수 — {@code OrderService.submitEntry}가 INVERSE fallback에 적용. */
    public double inverseBootstrapSizeMult() {
        return props.inverseBootstrapSizeMult();
    }

    // 인버스 버킷 실현손익 채점(2026-07-15) — 인버스 실제 청산은 지수 기반(반등/회복, 수분 단위)이라 고정 보유마크
    // 시뮬과 어긋남("60분 마크로 채점 vs 4분 실청산"). 부트스트랩이 모든 인버스 진입을 실주문으로 만들므로
    // LIVE 청산완료 실현손익(net, 비용 반영)으로 직접 채점 — 채점과 실제 청산 규칙이 정의상 일치.
    // 필드주입(생성자 무churn) — 미주입(테스트)이면 기존 마크 채점 fallback.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.repository.OrderRepository orderRepository;
    void setOrderRepository(com.stockadvisor.repository.OrderRepository r) { this.orderRepository = r; }   // 테스트용

    // 채택 청산방식 조회(2026-08-18) — exit horizon(보유시간 마크)은 방식이 TIME일 때만 실제 청산과 일치하므로,
    // 비-TIME 방식이면 게이트 horizon을 close로 낮춘다. 필드주입(생성자 무churn, 미주입 테스트는 종전 동작).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExitMethodProvider exitMethodProvider;
    void setExitMethodProvider(ExitMethodProvider p) { this.exitMethodProvider = p; }   // 테스트용

    /** INVERSE 버킷 — 실주문 실현손익(net%)으로 채점. 임계는 기존과 동일(inverseMinSamples/부트스트랩/minNet). */
    private GateDecision evaluateInverseRealized(String strategy) {
        String lookbackCutoff = LocalDate.now(SEOUL).minusDays(props.lookbackDays()).format(YYYYMMDD);
        // 로직 세대 교체(2026-07-20): 인버스 청산/재진입 로직이 7/16 저녁 교체돼, 구로직이 만든 실현 표본으로
        // 신로직을 채점하지 않도록 시작일 이후 표본만 집계(빈값=제한없음). 시작일이 룩백보다 최신이면 cutoff 상향.
        String since = props.inverseRealizedSince();
        final String cutoff = (since != null && !since.isBlank() && since.compareTo(lookbackCutoff) > 0)
                ? since : lookbackCutoff;
        java.util.List<com.stockadvisor.domain.Order> rows = orderRepository
                .findByModeAndSideAndClosed(com.stockadvisor.domain.TradingMode.LIVE,
                        com.stockadvisor.domain.OrderSide.BUY, true).stream()
                .filter(o -> strategy.equals(o.getStrategy()))
                .filter(o -> "INVERSE".equals(o.getMarket()))
                .filter(o -> o.getOrderDate() != null && o.getOrderDate().compareTo(cutoff) >= 0)
                .filter(o -> o.getRealizedPnl() != null)
                .toList();
        int n = rows.size();
        Double avg = null;
        if (n > 0) {
            double sum = 0;
            for (com.stockadvisor.domain.Order o : rows) {
                long price = (o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0) ? o.getAvgFillPrice() : o.getRequestedPrice();
                long qty = (o.getFilledQty() != null && o.getFilledQty() > 0) ? o.getFilledQty() : o.getRequestedQty();
                if (price <= 0 || qty <= 0) { n--; continue; }
                sum += (double) o.getRealizedPnl() / (price * qty) * 100;   // realized_pnl은 이미 net(비용 차감) 기록
            }
            avg = n == 0 ? null : round2(sum / n);
        }
        int minSamples = props.inverseMinSamples();
        if (!props.enabled()) {
            return new GateDecision(strategy, true, "게이트 비활성", n, avg, null, "INVERSE", false);
        }
        if (n >= minSamples) {
            if (avg < props.inverseMinNetAvgPct()) {
                return new GateDecision(strategy, false,
                        String.format("[INVERSE·실현손익] 성과 미달(net %.2f%% < 기준 %.2f%%, n=%d)", avg, props.inverseMinNetAvgPct(), n),
                        n, avg, null, "INVERSE", false);
            }
            return new GateDecision(strategy, true,
                    String.format("[INVERSE·실현손익] 통과(net %.2f%% ≥ 기준 %.2f%%, n=%d)", avg, props.inverseMinNetAvgPct(), n),
                    n, avg, null, "INVERSE", false);
        }
        if (props.inverseBootstrapSizeMult() > 0) {
            return new GateDecision(strategy, true,
                    String.format("[INVERSE·실현손익] 인버스 부트스트랩(실현표본 %d/%d) — 축소진입 ×%.1f(검증 전 실표본 수집)",
                            n, minSamples, props.inverseBootstrapSizeMult()),
                    n, avg, null, "INVERSE", true);
        }
        return new GateDecision(strategy, false,
                String.format("[INVERSE·실현손익] 표본 부족(%d/%d) — 미검증 전략 실주문 차단", n, minSamples),
                n, avg, null, "INVERSE", false);
    }

    /** overall(시장 무관) 평가 — 편의 오버로드. */
    public GateDecision evaluate(String strategy) {
        return evaluate(strategy, null);
    }

    /**
     * 해당 전략의 최근 성과로 LIVE 진입 허용 여부를 판정(국면조건부면 현재 국면 표본만).
     * @param market 종목 시장(KOSPI/KOSDAQ) — 그 시장의 현재 국면으로 매칭. null이면 전체(overall).
     */
    public GateDecision evaluate(String strategy, String market) {
        return evaluate(strategy, market, null);
    }

    /**
     * 국면 override 평가 — {@code forcedRegime}가 있으면 현재 국면 대신 그 국면으로 판정("이 국면이면 대기인가").
     * null이면 현재 국면(라이브 판정과 동일). 국면별 매수대기 시뮬(strategy-gate-by-regime)에 사용.
     */
    public GateDecision evaluate(String strategy, String market, MarketTrend forcedRegime) {
        // INVERSE 버킷은 실현손익 채점(위 주석) — 미주입(테스트)이면 기존 마크 채점으로 fallback.
        // 재필터는 섀도우 표본(TradeOutcome) 대상이라 INVERSE(실현손익 채점)엔 적용되지 않으므로 태그도 붙이지 않는다.
        if ("INVERSE".equals(market) && orderRepository != null) {
            return evaluateInverseRealized(strategy);
        }
        GateDecision d = evaluateSamples(strategy, market, forcedRegime);
        // 🐞 2026-08-18: 재필터 태그를 각 반환점에서 문자열로 이어 붙이다 보니 반환점 9곳 중 2곳(흐름·국면 엄격
        // 경로)에만 붙어 있었다 — 클러스터 차단·fallback·부트스트랩·표본부족 경로는 표본이 재필터로 줄어든 채
        // 판정됐는데도 사유만 보면 알 수 없었다(D-KOSPI 실측: n이 92→87로 줄었는데 태그 없음).
        // → 반환점마다 붙이는 대신 여기 한 곳에서 붙인다. 새 반환점이 생겨도 자동으로 커버된다.
        if (!props.enabled() || GateRefilter.forStrategy(refilters, strategy) == null) return d;
        return new GateDecision(d.strategy(), d.allowed(), d.reason() + REFILTER_TAG,
                d.samples(), d.netAvgReturnPct(), d.regimeTrend(), d.market(), d.fallback());
    }

    /** 표본(TradeOutcome) 기반 게이트 판정 본체 — 재필터 태그는 호출측({@link #evaluate})이 일괄 부착. */
    private GateDecision evaluateSamples(String strategy, String market, MarketTrend forcedRegime) {
        // 국면조건부: 종목 시장의 현재(또는 가정) 국면과 같은 국면 진입분만 집계. 미산출이면 국면 무관(null).
        // 인버스 버킷("INVERSE")은 방향 베팅 자체라 진입 시 국면태그가 null → 국면조건부 건너뜀(전체 인버스 표본 집계).
        // 히스테리시스: 밴드 = [closeNet, minNet). 열려있으면 closeNet까지 유지(닫힘 지연) → 문턱 근처 여닫이 진동 억제.
        // 활성 조건 closeNet<minNet. 시뮬(forcedRegime!=null)은 상태 미변경(가시화·실판정 = null만 갱신).
        // ⚠️ 키는 아래에서 '판정에 실제로 쓰인 버킷'(국면·흐름 포함)이 정해진 뒤 만든다 — 여기서 만들 수 없다.
        final boolean hystActive = props.closeNetAvgPct() < props.minNetAvgPct();
        final boolean mutateHyst = hystActive && forcedRegime == null;
        // K(개장갭)처럼 정의상 '어제까지의 국면'을 보는 전략은 전일 확정 라벨로 버킷팅한다(2026-08-13).
        // 장중에 흔들리는 라벨로 버킷을 잡으면 같은 전략이 하루에도 여러 번 다른 표본 풀로 판정된다(K 실측).
        boolean priorDayRegime = priorDayRegimeStrategies.contains(strategy);
        MarketTrend regime = (props.regimeConditional() && !"INVERSE".equals(market))
                ? (forcedRegime != null ? forcedRegime
                    : (priorDayRegime ? marketRegimeService.priorDayTrendOf(market)
                                      : marketRegimeService.trendOf(market))) : null;
        String regimeName = regime == null ? null : regime.name();
        // (market,trend) 2차원: 국면조건부 + 시장 지정 + 토글 on 일 때 같은 시장 진입분만
        boolean marketSplit = props.regimeConditional() && props.regimeMarketSplit()
                && market != null && !market.isBlank();
        // 스윙 전략은 청산 시점이 D+1(익일종가)이라 그 horizon으로 검증. 인트라데이는 "exit"=실제 청산 마크로 검증.
        String horizon = swingStrategies.contains(strategy) ? swingHorizon : props.horizon();
        // ⚠️ exit horizon은 '보유시간 마크'라 채택 청산방식이 TIME일 때만 실제 청산 시점과 일치한다(2026-08-18).
        // TRAILING/VWAP/추세전환 등은 시간 상한이 없어 실제로는 트리거 or 장마감까지 간다 — D 실측: 게이트는
        // 65분 마크로 채점하는데 라이브 보유는 1~10분(트레일 조기 트리거)과 350~374분(장마감)의 양봉 분포였고,
        // 65분 부근은 사실상 비어 있었다. 즉 게이트가 '검증한 적 없는 청산'으로 실주문을 열어주고 있었다.
        // → 비-TIME 방식은 당일종가(close)로 검증한다. 장마감 보유가 지배적이라 실제에 가깝고, 동시에
        //   권장 마크의 max-pick 낙관 편향도 제거돼 보수적(fail-closed 방향)이다.
        String methodTag = "";
        if ("exit".equals(horizon) && exitMethodProvider != null) {
            try {
                com.stockadvisor.domain.ExitMethodType type = exitMethodProvider.methodFor(strategy).type();
                if (type != com.stockadvisor.domain.ExitMethodType.TIME) {
                    horizon = "close";
                    methodTag = "·" + type.korean() + "청산";
                }
            } catch (Exception ignored) {
                // 청산방식 조회 실패 → 종전(보유시간 마크)으로 degrade
            }
        }
        // horizon="exit": 전략별 권장 보유시간(PositionExitService가 실제 청산하는 그 마크)의 가격을 OutcomeSample에서
        // 조회해 net을 측정 → "실제로 팔 시점의 수익"으로 검증(당일종가 아님).
        boolean exitMode = "exit".equals(horizon);
        int exitMark = exitMode ? holdTimeProvider.holdMinutes(strategy) : -1;
        java.util.Map<Long, Long> exitPriceByOutcome = null;
        if (exitMode) {
            // 표본 기근 보정: 정확 마크(exitMark)뿐 아니라 ±허용범위 내 '근접 마크'도 사용해 outcome별 exitMark에
            // 가장 가까운 마크 가격을 채택. (예: 권장 80분이 5분-only 마크라 과거 코스마크 90분 표본이 누락되던 문제 해소.)
            exitPriceByOutcome = new java.util.HashMap<>();
            java.util.Map<Long, Integer> bestDist = new java.util.HashMap<>();
            for (OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                    strategy, exitMark - EXIT_MARK_TOLERANCE_MIN, exitMark + EXIT_MARK_TOLERANCE_MIN)) {
                int d = Math.abs(s.getMarkMinutes() - exitMark);
                Integer cur = bestDist.get(s.getOutcomeId());
                if (cur == null || d < cur) {
                    bestDist.put(s.getOutcomeId(), d);
                    exitPriceByOutcome.put(s.getOutcomeId(), s.getPrice());
                }
            }
        }

        // INVERSE 버킷은 표본이 폭락일에만 쌓여 일반 minSamples(30) 도달이 비현실적 — 전용 하향 임계 사용(net 기준은 동일).
        int minSamples = "INVERSE".equals(market) ? props.inverseMinSamples() : props.minSamples();

        String cutoff = LocalDate.now(SEOUL).minusDays(props.lookbackDays()).format(YYYYMMDD);
        // 전략별 net 재검증 시작일(로직/임계 변경) — 변경일이 룩백보다 최신이면 cutoff 상향 → 구로직 표본 제외(net 리셋).
        String since = strategySince.get(strategy);
        boolean sinceReset = since != null && since.compareTo(cutoff) > 0;
        if (sinceReset) cutoff = since;
        List<TradeOutcome> rows =
                tradeOutcomeRepository.findByStrategyAndAlertDateGreaterThanEqual(strategy, cutoff);

        // 흐름 조건부(3차원): 현재 시장의 장중 흐름(mom30) 부호 — 미산출(개장 ~30분/조회실패)이면 null → 흐름 레이어 생략.
        Boolean flowUp = null;
        if (props.flowConditional() && market != null && !market.isBlank() && regimeName != null) {
            try {
                MarketRegimeService.IntradayFlow flow = marketRegimeService.intradayFlow(market);
                Double mom = (flow != null && flow.available()) ? flow.mom30Pct() : null;
                if (mom != null) flowUp = mom >= 0;
            } catch (Exception ignored) {
                // 흐름 조회 실패 → 국면만으로 판정(degrade)
            }
        }

        // 세 개의 누산기를 한 번의 순회로: (nF) 국면+흐름 매칭[3차원 primary], (nR) 국면 매칭[2차원],
        // (nAll) 국면 무관 전체[fallback pool]. 시장 필터(marketSplit)는 전부 공통.
        double sumR = 0; int nR = 0;
        double sumAll = 0; int nAll = 0;
        double sumF = 0; int nF = 0;
        // 교차 거래일 요건: 버킷별 (alertDate → 표본수) — 단일일 지배(클러스터) 판정용.
        java.util.Map<String, Integer> daysR = new java.util.HashMap<>();
        java.util.Map<String, Integer> daysAll = new java.util.HashMap<>();
        java.util.Map<String, Integer> daysF = new java.util.HashMap<>();
        // 구표본 자동 재필터: 조이는 필터 추가 시 새 필터의 임계(태깅된 feature)를 구표본에도 적용 → 통과분만 채점.
        // 전역(*) 규칙 + 전략별 규칙을 AND로 — 전략 무관 진입 필터를 since 리셋 없이 구표본에 재적용하기 위함.
        GateRefilter refilter = GateRefilter.forStrategy(refilters, strategy);
        for (TradeOutcome o : rows) {
            if (o.isControl()) continue;   // 대조군(미진입) 제외 — 게이트는 '진입 성과'만 검증(스윙 nextClose에서 대조군 오염 방지)
            if (refilter != null && !refilter.test(o)) continue;   // 새 필터라면 걸렀을 구표본 제외(net 정밀 재검증)
            if (marketSplit && !market.equals(o.getEntryMarket())) continue;   // 2D: 다른 시장 제외(양쪽 공통)
            Long price = exitMode ? exitPriceByOutcome.get(o.getId()) : resultPrice(o, horizon);
            if (price == null || o.getBuyPrice() <= 0) continue;   // exit 마크 미수집 표본은 제외(fail-closed)
            double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()   // 진입시 실측 스프레드
                    : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());   // 없으면 tick 추정
            double cost = roundTripCostPct + slip;
            double net = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - cost;
            String d = o.getAlertDate();
            sumAll += net; nAll++; bump(daysAll, d);                            // 전국면 pool
            boolean regimeMatch = regimeName == null || regimeName.equals(o.getEntryMarketTrend());
            if (regimeMatch) { sumR += net; nR++; bump(daysR, d); }              // 국면 매칭
            if (regimeMatch && flowUp != null && o.getEntryIndexMom30() != null
                    && (o.getEntryIndexMom30() >= 0) == flowUp) { sumF += net; nF++; bump(daysF, d); }   // 국면+흐름 매칭
        }
        int n = nR;
        Double avg = n == 0 ? null : round2(sumR / nR);
        // 라벨: [시장·국면·horizon] — 스윙은 horizon(nextClose)을 명시해 "어떤 시점으로 검증했는지" 노출
        String horizonLabel = exitMode ? exitMark + "분"
                : (!horizon.equals(props.horizon()) ? horizon + methodTag : null);   // 스윙 nextClose·비TIME청산 등 명시
        String tagInner = (marketSplit ? market + "·" : "") + (regime == null ? "" : regime.korean());
        if (horizonLabel != null) tagInner = tagInner.isEmpty() ? horizonLabel : tagInner + "·" + horizonLabel;
        String regimeTag = tagInner.isEmpty() ? "" : "[" + tagInner + "] ";

        if (!props.enabled()) {
            return new GateDecision(strategy, true, "게이트 비활성", n, avg, regimeName, market, false);
        }
        // ⓪ 국면+흐름(3차원) — 흐름 버킷 표본이 충족되면 그 버킷만으로 판정(가장 정밀). 부족하면 아래 국면 버킷으로 자연 fallback.
        //    실측 근거: 흐름 엣지는 국면 내부에서 갈림(예: H 중립·흐름↑ +0.94 vs 중립·흐름↓ −0.37) — 충분한 곳만 반영.
        if (flowUp != null && nF >= props.flowMinSamples()) {
            Double avgF = round2(sumF / nF);
            String flowTag = regimeTag.isEmpty() ? "" : regimeTag.substring(0, regimeTag.length() - 2)
                    + "·흐름" + (flowUp ? "↑" : "↓") + "] ";
            String keyF = hystKey(strategy, market, regimeName, flowUp ? "up" : "down");
            boolean wasOpenF = hystActive && Boolean.TRUE.equals(openState.get(keyF));
            double effMinNetF = wasOpenF ? props.closeNetAvgPct() : props.minNetAvgPct();
            double shareF = singleDaySharePct(daysF, nF);
            if (clustered(shareF)) {   // 교차거래일 미충족 — 단일일 클러스터는 net이 좋아도 LIVE 졸업 차단(fail-closed)
                if (mutateHyst) openState.put(keyF, false);
                return clusterBlock(strategy, flowTag, shareF, nF, daysF.size(), avgF, regimeName, market);
            }
            boolean allow = avgF >= effMinNetF;
            if (mutateHyst) openState.put(keyF, allow);
            return new GateDecision(strategy, allow,
                    String.format("%s흐름버킷 %s(net %.2f%% %s 기준 %.2f%%, n=%d)%s",
                            flowTag, allow ? "통과" : "성과 미달", avgF, allow ? "≥" : "<", effMinNetF, nF,
                            wasOpenF ? HYST_TAG : ""),
                    nF, avgF, regimeName, market, false);
        }
        // ① 현재 국면 표본 충분 → 엄격(국면조건부) 경로. 표본이 minSamples 도달하면 여기로 자동 졸업(④).
        if (n >= minSamples) {
            String keyR = hystKey(strategy, market, regimeName, "_");
            boolean wasOpenR = hystActive && Boolean.TRUE.equals(openState.get(keyR));
            double effMinNetR = wasOpenR ? props.closeNetAvgPct() : props.minNetAvgPct();
            double shareR = singleDaySharePct(daysR, n);
            if (clustered(shareR)) {   // 교차거래일 미충족 — 단일일 클러스터는 net이 좋아도 LIVE 졸업 차단(fail-closed)
                if (mutateHyst) openState.put(keyR, false);
                return clusterBlock(strategy, regimeTag, shareR, n, daysR.size(), avg, regimeName, market);
            }
            boolean allow = avg >= effMinNetR;
            if (mutateHyst) openState.put(keyR, allow);
            return new GateDecision(strategy, allow,
                    String.format("%s%s(net %.2f%% %s 기준 %.2f%%, n=%d)%s",
                            regimeTag, allow ? "통과" : "성과 미달", avg, allow ? "≥" : "<", effMinNetR, n,
                            wasOpenR ? HYST_TAG : ""),
                    n, avg, regimeName, market, false);
        }
        // 여기 도달 = 엄격(흐름·국면) 버킷 판정에 실패(표본부족) → 아래는 전부 비엄격 경로(fallback·부트스트랩·fail-closed).
        // 이 '버킷'의 열림을 정당화하던 엄격 판정이 더는 없으므로 닫힘으로 명시한다(fail-safe, 클러스터 차단과 동일 사상).
        // 키가 버킷 단위라 정리 범위도 현재 컨텍스트로 한정된다 — 다른 국면·흐름 버킷의 상태는 보존된다(그게 #2의 요점).
        if (mutateHyst) {
            openState.put(hystKey(strategy, market, regimeName, "_"), false);
            if (flowUp != null) openState.put(hystKey(strategy, market, regimeName, flowUp ? "up" : "down"), false);
        }
        // ② 국면 표본 부족 + fallback 활성 → 국면무관 전국면 pool로 재평가(더 엄격한 바). regimeName==null(미산출/off)이면
        // primary가 이미 전국면이라 fallback 무의미 → 건너뜀.
        if (props.fallbackEnabled() && regimeName != null) {
            Double avgAll = nAll == 0 ? null : round2(sumAll / nAll);
            // 교차거래일 미충족 — 전국면 fallback pool도 단일일 클러스터면 축소진입 졸업 차단(fail-closed)
            if (nAll >= props.fallbackMinSamples() && avgAll != null && clustered(singleDaySharePct(daysAll, nAll))) {
                return clusterBlock(strategy, regimeTag + "전국면 ", singleDaySharePct(daysAll, nAll),
                        nAll, daysAll.size(), avgAll, regimeName, market);
            }
            if (nAll >= props.fallbackMinSamples() && avgAll != null && avgAll >= props.fallbackMinNetAvgPct()) {
                // ③ 통과 → fallback=true(OrderService가 축소사이징 적용)
                return new GateDecision(strategy, true,
                        String.format("%s국면표본부족(%d/%d)→전국면 fallback통과(net %.2f%% ≥ %.2f%%, n=%d) — 축소진입",
                                regimeTag, n, minSamples, avgAll, props.fallbackMinNetAvgPct(), nAll),
                        nAll, avgAll, regimeName, market, true);
            }
            return new GateDecision(strategy, false,
                    String.format("%s국면표본부족(%d/%d)+fallback미달(전국면 net %s, n=%d/%d)",
                            regimeTag, n, minSamples,
                            avgAll == null ? "N/A" : String.format("%.2f%%", avgAll), nAll, props.fallbackMinSamples()),
                    n, avg, regimeName, market, false);
        }
        // INVERSE 부트스트랩: 표본 미달이어도 축소사이징(inverseBootstrapSizeMult)으로 실주문 허용 — 적은 비용으로
        // 실표본을 수집(폭락일에만 쌓이는 인버스 특성 보완). 표본이 inverseMinSamples에 차면 이 분기에 안 오고
        // 위 엄격 경로로 자동 졸업: net ≥ 기준이면 정상 사이징(제한 해제), 미달이면 차단(성과 미달을 부트스트랩으로 우회 불가).
        if ("INVERSE".equals(market) && props.inverseBootstrapSizeMult() > 0) {
            return new GateDecision(strategy, true,
                    String.format("%s인버스 부트스트랩(표본 %d/%d) — 축소진입 ×%.1f(검증 전 실표본 수집)",
                            regimeTag, n, minSamples, props.inverseBootstrapSizeMult()),
                    n, avg, regimeName, market, true);
        }
        // 일반 부트스트랩(재검증 다리): since 리셋 등으로 표본 미달이어도 지정 전략은 축소사이징(fallbackSizeMult)으로 실주문 —
        // 로직 변경 후 완전정지 없이 실표본 수집. 표본이 minSamples에 차면 위 엄격 경로로 자동 졸업(미달이면 차단, 우회 불가).
        if (bootstrapStrategies.contains(strategy)) {
            return new GateDecision(strategy, true,
                    String.format("%s부트스트랩(표본 %d/%d%s) — 축소진입(재검증 중 실표본 수집)",
                            regimeTag, n, minSamples, sinceReset ? ", since " + since : ""),
                    n, avg, regimeName, market, true);
        }
        // fallback 비활성 → 기존 fail-closed
        return new GateDecision(strategy, false,
                String.format("%s표본 부족(%d/%d%s) — 미검증 전략 실주문 차단",
                        regimeTag, n, minSamples, sinceReset ? ", since " + since : ""), n, avg, regimeName, market, false);
    }

    /** 전략×시장(KOSPI/KOSDAQ) 게이트 상태(가시화/관리 API용) — 시장별 국면 매칭 반영. */
    public List<GateDecision> evaluateAll() {
        List<GateDecision> out = new java.util.ArrayList<>();
        for (String strategy : strategyNames) {
            for (String market : List.of("KOSPI", "KOSDAQ", "INVERSE")) {   // INVERSE=인버스 ETF 검증 버킷(하락장 수익)
                out.add(evaluate(strategy, market));
            }
        }
        return out;
    }

    /** 국면 가정 게이트(전략×시장×국면) — "이 국면이면 매수대기인가"를 강세/중립/약세 각각으로 시뮬. INVERSE는 국면무관 1회. */
    public List<GateDecision> evaluateByRegime() {
        List<GateDecision> out = new java.util.ArrayList<>();
        for (String strategy : strategyNames) {
            for (String market : List.of("KOSPI", "KOSDAQ")) {
                for (MarketTrend regime : List.of(MarketTrend.BULL, MarketTrend.NEUTRAL, MarketTrend.BEAR)) {
                    out.add(evaluate(strategy, market, regime));
                }
            }
            out.add(evaluate(strategy, "INVERSE"));   // 인버스는 국면 무관(1회)
        }
        return out;
    }

    private Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            case "p10" -> o.getPrice10min();
            case "p30" -> o.getPrice30min();
            default -> o.getPriceClose();
        };
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── 교차 거래일 요건(단일일 클러스터 방지, 2026-08-12) ──
    private static void bump(java.util.Map<String, Integer> m, String d) {
        if (d != null) m.merge(d, 1, Integer::sum);
    }

    /** 버킷의 최대 단일 거래일 점유율(%). 표본 없으면 0. */
    private static double singleDaySharePct(java.util.Map<String, Integer> days, int n) {
        if (n <= 0 || days.isEmpty()) return 0;
        int max = 0;
        for (int c : days.values()) if (c > max) max = c;
        return 100.0 * max / n;
    }

    /** 한 거래일 점유율이 문턱(maxSingleDaySharePct)을 초과하면 단일일 클러스터로 판정(0=비활성). */
    private boolean clustered(double sharePct) {
        return maxSingleDaySharePct > 0 && sharePct > maxSingleDaySharePct;
    }

    /** 단일일 클러스터 버킷 — net 무관 LIVE 차단(fail-closed). netAvg는 참고용으로 실어 가시화. */
    private GateDecision clusterBlock(String strategy, String tag, double sharePct, int n, int distinctDays,
                                      Double avg, String regimeName, String market) {
        return new GateDecision(strategy, false,
                String.format("%s단일일 클러스터(최대 %.0f%% > %.0f%%, n=%d/%d거래일) — 교차거래일 미충족(미검증)",
                        tag, sharePct, maxSingleDaySharePct, n, distinctDays),
                n, avg, regimeName, market, false);
    }
}
