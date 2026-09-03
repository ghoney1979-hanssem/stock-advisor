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
                                   // 최대기여일 제외 net(LOO) 요건 — 버킷 net이 단 하루로 설명되면 LIVE 차단. false=종전 동작.
                                   @Value("${stockadvisor.trading.perf-gate.loo-top-day:true}") boolean looTopDay,
                                   // INVERSE 버킷을 전략 무관 단일 풀로 채점(아래 evaluateInverseRealized 참조). false=전략별.
                                   @Value("${stockadvisor.trading.perf-gate.inverse-pooled:true}") boolean inversePooled,
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
        this.looTopDay = looTopDay;
        this.inversePooled = inversePooled;
        this.strategySince = parseSince(strategySinceCsv);
        this.bootstrapStrategies = PolicyGate.parseCsv(bootstrapStrategiesCsv);
        this.refilters = GateRefilter.parse(refilterCsv);
        this.priorDayRegimeStrategies = PolicyGate.parseCsv(priorDayRegimeCsv);
    }

    /** 전략 → 구표본 재필터 술어(조이는 필터 임계). */
    private final java.util.Map<String, GateRefilter> refilters;

    // ── 시장폭 조건부(4차원, 2026-08-29) ────────────────────────────────────────────────
    // walk-forward(섀도우 6/25~8/28, 33일 평가)에서 상태조건부 선택의 단위당 net: 상태 무관 −0.84 → 국면 −0.29 →
    // 국면×흐름 +0.07~+0.17 → **국면×시장폭 +0.40~+0.50(적중 73~77%)**. 시장폭은 진입 시 태깅만 하고
    // 판정엔 안 쓰던 차원이었다(8/24: BEAR 라벨인데 종목 58%가 오른 날 게이트가 닫혀 있었다).
    // 체인: 국면×흐름×폭(표본 충족 시) → 국면×흐름 → 국면 → fallback. 흐름 레이어와 같은 자연 fallback.
    // ⚠️ 코드 기본 off(종전 동작). 표본이 33일이라 '검증'이 아니라 포워드로 넘길 가치가 있는 수준 — prod에서 켜서 관찰.
    @Value("${stockadvisor.trading.perf-gate.breadth-conditional:false}")
    private boolean breadthConditional = false;
    @Value("${stockadvisor.trading.perf-gate.breadth-min-samples:20}")
    private int breadthMinSamples = 20;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketBreadthService breadthService;   // 필드주입(생성자 무churn) — 미주입(테스트)이면 폭 레이어 생략
    private static final long BREADTH_FRESH_MINUTES = 40;   // MarketBreadthService.isFresh 와 같은 기준(마감 후·전일분 오발동 방지)

    /** 테스트용 — 폭 레이어 구성. */
    void configureBreadth(MarketBreadthService service, boolean enabled, int minSamples) {
        this.breadthService = service;
        this.breadthConditional = enabled;
        this.breadthMinSamples = minSamples;
    }

    // ── net 추세 조건부(2026-09-02, 사용자 결정) ──────────────────────────────────────────
    // 게이트를 <b>절대 net의 수준</b>이 아니라 <b>net의 방향</b>으로 판정한다:
    // 총 net이 음수여도 <b>상승곡선</b>이면 열고, 양수여도 <b>하락곡선</b>이면 닫는다.
    //
    // 왜: 수준 판정은 구조적으로 후행한다 — 회복 중인 전략은 룩백 평균이 문턱을 넘을 때까지 닫혀 있어
    // 회복 구간을 통째로 놓치고, 식어가는 전략은 평균이 문턱 아래로 내려올 때까지 열려 있어 하강분을 다 맞는다.
    // (실측 배경: 8/14에 히스테리시스 밴드를 −0.2→−0.05로 좁힌 것도 "회복한 적 없는 버킷이 열린 채 유지"된
    //  같은 유형의 문제였다. 밴드 폭 조정은 증상 완화였고, 방향을 직접 재는 게 원인 처방이다.)
    //
    // ⚠️ <b>비대칭</b>이 이 설계의 핵심 — 이 시스템의 다른 모든 가드와 같은 원칙(리스크 축소는 빠르게, 확대는 느리게):
    //   · <b>닫기</b>(하락곡선)는 기울기 하나로 즉시 — LOO 요구 없음.
    //   · <b>열기</b>(상승곡선)는 기울기 + <b>어느 하루를 빼도 기울기 부호가 유지</b>될 것(전수 LOO)
    //     + <b>마지막 판정근거일 net이 흑자</b>일 것.
    //     앞의 둘은 "하루가 만든 반등으로 열리는 것"을 막고(클러스터·LOO 가드가 반복해 잡아온 실패 유형),
    //     마지막 하나는 <b>"덜 지는 중"과 "이기는 중"을 가른다</b> — 기울기만 보면 −4%→−2% 개선도 상승곡선이라
    //     열리는데 그건 여전히 손실이다.
    //   · 데드밴드 안(평탄)이면 <b>종전 절대 net 판정으로 fallback</b> — 방향이 없을 땐 수준이 답이다.
    //
    // ⚠️ 표본 수 요건(minSamples/flowMinSamples/breadthMinSamples)·클러스터 가드는 <b>그대로</b>다 —
    //    추세는 "얼마나 검증됐나"가 아니라 "어느 방향인가"만 바꾼다. 표본이 부족하면 추세 판정 자체를 하지 않는다.
    // ⚠️ 코드 기본 off(종전 동작). prod에서 env로 켠다.
    @Value("${stockadvisor.trading.perf-gate.net-trend-conditional:false}")
    private boolean netTrendConditional = false;
    /** 추세 판정 최소 거래일 — 전수 LOO가 의미를 가지려면 하나 빼고도 3점이 남아야 한다. */
    @Value("${stockadvisor.trading.perf-gate.net-trend-min-days:5}")
    private int netTrendMinDays = 5;
    /** 상승 판정 문턱(%p/거래일). 데드밴드 상한 — 이 미만이면 '평탄'으로 보고 절대 net으로 판정. */
    @Value("${stockadvisor.trading.perf-gate.net-trend-up-pct:0.05}")
    private double netTrendUpPct = 0.05;
    /** 하락 판정 문턱(%p/거래일, 양수로 지정). 데드밴드 하한. */
    @Value("${stockadvisor.trading.perf-gate.net-trend-down-pct:0.05}")
    private double netTrendDownPct = 0.05;
    /**
     * 상승 판정의 추가 요건 — <b>마지막 판정근거일 net이 이 값을 넘어야</b> 연다(기본 0 = 흑자여야 함).
     *
     * <p>기울기만 보면 "덜 지는 중"과 "이기는 중"이 구분되지 않는다. 이 조건이 그 둘을 가른다.
     * 하락 판정엔 적용하지 않는다(닫는 방향은 빠르게 — 비대칭 유지).</p>
     */
    @Value("${stockadvisor.trading.perf-gate.net-trend-last-day-min-pct:0}")
    private double netTrendLastDayMinPct = 0;
    /**
     * 하락추세면 <b>부트스트랩 경로도 닫는다</b>(기본 false = 종전 동작).
     *
     * <p>부트스트랩은 성과 판정을 건너뛰는 경로라, 이 플래그 없이는 추세 조건부가 <b>정작 지금 열려 있는
     * 전략들에는 안 걸린다</b>(2026-09-02 실측: 열려 있던 4개 버킷이 전부 부트스트랩이었다).</p>
     *
     * <p>⚠️ 닫기만 한다 — 상승추세로 부트스트랩을 졸업시키지는 않는다(표본 수 요건 우회 방지).</p>
     */
    @Value("${stockadvisor.trading.perf-gate.net-trend-closes-bootstrap:false}")
    private boolean netTrendClosesBootstrap = false;

    /** 테스트용 — 추세 레이어 구성. */
    void configureNetTrend(boolean enabled, int minDays, double upPct, double downPct, double lastDayMinPct) {
        configureNetTrend(enabled, minDays, upPct, downPct, lastDayMinPct, false);
    }

    /** 테스트용 — 추세 레이어 구성(부트스트랩 차단 포함). */
    void configureNetTrend(boolean enabled, int minDays, double upPct, double downPct, double lastDayMinPct,
                           boolean closesBootstrap) {
        this.netTrendConditional = enabled;
        this.netTrendMinDays = minDays;
        this.netTrendUpPct = upPct;
        this.netTrendDownPct = downPct;
        this.netTrendLastDayMinPct = lastDayMinPct;
        this.netTrendClosesBootstrap = closesBootstrap;
    }

    /** 버킷의 일별 net 맵 → 추세(비활성이거나 거래일 부족이면 null=판정 생략). */
    private NetTrend trendOf(java.util.Map<String, double[]> days) {
        return netTrendConditional
                ? netTrend(days, netTrendMinDays, netTrendUpPct, netTrendDownPct, netTrendLastDayMinPct)
                : null;
    }

    /**
     * 최종 허용 판정 — 추세가 있으면 추세가 절대 net 판정을 <b>대체</b>하고, 평탄하거나 추세 미판정이면 종전 그대로.
     *
     * @param netOk 절대 net 판정 결과(net 기준 통과 AND LOO 통과) — 종전 동작
     * @param tr    추세(null이면 미판정)
     */
    static boolean decide(boolean netOk, NetTrend tr) {
        if (tr == null) return netOk;
        if (tr.falling()) return false;   // + 여도 하락곡선이면 닫는다
        if (tr.rising()) return true;     // − 여도 상승곡선이면 연다
        return netOk;                      // 평탄 → 수준으로 판정(종전)
    }

    /** 시장폭(상승비율%) → 3구간 라벨. 미상이면 null. walk-forward가 쓴 경계(40/60)와 동일. */
    static String breadthBin(Double pct) {
        if (pct == null) return null;
        return pct < 40 ? "<40" : pct < 60 ? "40~60" : "≥60";
    }

    private final double maxSingleDaySharePct;
    private final boolean looTopDay;
    private final boolean inversePooled;
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

    /**
     * 전략별 fallback/부트스트랩 사이징 배수(2026-09-03) — 미지정 전략은 전역 {@code fallback-size-mult} = 종전 동작.
     *
     * <p><b>왜 전역값으로는 안 되는가</b>: 부트스트랩은 지금 J·G·L·P가 함께 쓰는데, 전역 배수를 올리면
     * <b>넷이 같이 올라간다</b>. "이 전략만 표본 충족 없이 정상 사이징으로 간다"는 결정은 전략별이라야 표현된다
     * (전략별 보유시간 캡·손절 하한과 같은 사상).</p>
     *
     * <p>⚠️ <b>1.0을 주는 건 "축소 없이 미검증 진입"</b>이라는 뜻이다 — 부트스트랩의 원래 취지(적은 비용으로
     * 실표본 수집)를 포기하는 선택이므로, 그 전략의 근거가 그만한지 따로 판단해야 한다.</p>
     *
     * <p>⚠️ 오타·0·음수는 조용히 무시하고 전역값으로 degrade(설정 실수로 수량이 0이 되는 것보다 낫다).</p>
     */
    public double fallbackSizeMult(String strategy) {
        Double m = bootstrapSizeMultPerStrategy.get(strategy);
        return m != null ? m : props.fallbackSizeMult();
    }

    @Value("${stockadvisor.trading.perf-gate.bootstrap-size-mult-per-strategy:}")
    private String bootstrapSizeMultCsv = "";
    private java.util.Map<String, Double> bootstrapSizeMultPerStrategy = java.util.Map.of();
    @jakarta.annotation.PostConstruct
    void initBootstrapSizeMult() {
        this.bootstrapSizeMultPerStrategy = parseSizeMults(bootstrapSizeMultCsv);
    }

    /** 테스트용. */
    void setBootstrapSizeMultPerStrategy(String csv) {
        this.bootstrapSizeMultPerStrategy = parseSizeMults(csv);
    }

    /** "A:1.0,B:0.3" → {A:1.0, B:0.3}. 오타·0·음수는 무시(전역값으로 degrade). */
    static java.util.Map<String, Double> parseSizeMults(String csv) {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        if (csv == null) return m;
        for (String part : csv.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2 || kv[0].trim().isEmpty()) continue;
            try {
                double v = Double.parseDouble(kv[1].trim());
                if (v > 0) m.put(kv[0].trim(), v);
            } catch (NumberFormatException ignored) {
                // degrade — 전역 fallback-size-mult
            }
        }
        return m;
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
                // 전략 무관 단일 풀(2026-08-20): 인버스 매매의 손익은 어느 전략이 신호를 냈느냐가 아니라
                // 공통 청산 로직(trading.inverse-exit)이 결정하는데, 버킷만 전략별로 쪼개져 있어 I가 실패로
                // 닫혀도(8/20 net −0.52%, n=10) 나머지 10개 전략은 각자 "실현표본 0/10"이라 부트스트랩으로
                // 열린 채 같은 매매를 계속 낼 수 있었다. 풀링하면 한 번 쌓인 인버스 실적이 전 경로에 반영된다.
                .filter(o -> inversePooled || strategy.equals(o.getStrategy()))
                .filter(o -> "INVERSE".equals(o.getMarket()))
                .filter(o -> o.getOrderDate() != null && o.getOrderDate().compareTo(cutoff) >= 0)
                .filter(o -> o.getRealizedPnl() != null)
                .toList();
        int n = rows.size();
        Double avg = null;
        // 추세 판정용 일별 net(주문일 기준) — 섀도우 경로의 daysR/daysF와 같은 {건수, net합} 구조.
        java.util.Map<String, double[]> daysInv = new java.util.HashMap<>();
        if (n > 0) {
            double sum = 0;
            for (com.stockadvisor.domain.Order o : rows) {
                long price = (o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0) ? o.getAvgFillPrice() : o.getRequestedPrice();
                long qty = (o.getFilledQty() != null && o.getFilledQty() > 0) ? o.getFilledQty() : o.getRequestedQty();
                if (price <= 0 || qty <= 0) { n--; continue; }
                double net = (double) o.getRealizedPnl() / (price * qty) * 100;   // realized_pnl은 이미 net(비용 차감) 기록
                sum += net;
                bump(daysInv, o.getOrderDate(), net);
            }
            avg = n == 0 ? null : round2(sum / n);
        }
        int minSamples = props.inverseMinSamples();
        if (!props.enabled()) {
            return new GateDecision(strategy, true, "게이트 비활성", n, avg, null, "INVERSE", false);
        }
        String poolTag = inversePooled ? "·통합" : "";
        if (n >= minSamples) {
            NetTrend trInv = trendOf(daysInv);
            boolean allow = decide(avg >= props.inverseMinNetAvgPct(), trInv);
            String trTag = trInv == null ? "" : trInv.tag();
            if (!allow) {
                return new GateDecision(strategy, false,
                        String.format("[INVERSE·실현손익%s] %s(net %.2f%% %s 기준 %.2f%%, n=%d)%s", poolTag,
                                (trInv != null && trInv.falling()) ? "net 하락추세 차단" : "성과 미달",
                                avg, avg >= props.inverseMinNetAvgPct() ? "≥" : "<", props.inverseMinNetAvgPct(), n, trTag),
                        n, avg, null, "INVERSE", false);
            }
            return new GateDecision(strategy, true,
                    String.format("[INVERSE·실현손익%s] %s(net %.2f%% %s 기준 %.2f%%, n=%d)%s", poolTag,
                            (trInv != null && trInv.rising() && avg < props.inverseMinNetAvgPct()) ? "net 상승추세 통과" : "통과",
                            avg, avg >= props.inverseMinNetAvgPct() ? "≥" : "<", props.inverseMinNetAvgPct(), n, trTag),
                    n, avg, null, "INVERSE", false);
        }
        if (props.inverseBootstrapSizeMult() > 0) {
            // 하락추세는 부트스트랩도 닫는다(섀도우 경로와 동일 규칙) — 인버스는 표본이 폭락일에만 쌓여
            // minSamples 도달이 느린 만큼, 부트스트랩 구간이 길고 그동안 성과 판정이 통째로 비어 있었다.
            NetTrend trBoot = netTrendClosesBootstrap ? trendOf(daysInv) : null;
            if (trBoot != null && trBoot.falling()) {
                return new GateDecision(strategy, false,
                        String.format("[INVERSE·실현손익%s] 부트스트랩 net 하락추세 차단(실현표본 %d/%d)%s",
                                poolTag, n, minSamples, trBoot.tag()),
                        n, avg, null, "INVERSE", false);
            }
            return new GateDecision(strategy, true,
                    String.format("[INVERSE·실현손익%s] 인버스 부트스트랩(실현표본 %d/%d) — 축소진입 ×%.1f(검증 전 실표본 수집)%s",
                            poolTag, n, minSamples, props.inverseBootstrapSizeMult(),
                            trBoot == null ? (netTrendClosesBootstrap ? " ·net추세(거래일 부족 — 판정 생략)" : "")
                                    : trBoot.tag()),
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

        // 시장폭 조건부(4차원): 현재 시장의 상승비율 구간 — 스냅샷 미상/신선도 만료(마감 후·전일분)면 null → 폭 레이어 생략.
        String breadthNow = null;
        if (breadthConditional && breadthService != null && market != null && !market.isBlank() && regimeName != null) {
            try {
                if (breadthService.isFresh(BREADTH_FRESH_MINUTES)) breadthNow = breadthBin(breadthService.breadthPct(market));
            } catch (Exception ignored) {
                // 폭 조회 실패 → 흐름/국면으로 판정(degrade)
            }
        }

        // 네 개의 누산기를 한 번의 순회로: (nB) 국면+흐름+폭 매칭[4차원], (nF) 국면+흐름 매칭[3차원 primary],
        // (nR) 국면 매칭[2차원], (nAll) 국면 무관 전체[fallback pool]. 시장 필터(marketSplit)는 전부 공통.
        double sumR = 0; int nR = 0;
        double sumAll = 0; int nAll = 0;
        double sumF = 0; int nF = 0;
        double sumB = 0; int nB = 0;
        java.util.Map<String, double[]> daysB = new java.util.HashMap<>();
        // 교차 거래일 요건: 버킷별 (alertDate → 표본수) — 단일일 지배(클러스터) 판정용.
        // 값은 {표본수, net합} — 점유율(수)과 최대기여일 제외 net(합) 둘 다 필요하다.
        java.util.Map<String, double[]> daysR = new java.util.HashMap<>();
        java.util.Map<String, double[]> daysAll = new java.util.HashMap<>();
        java.util.Map<String, double[]> daysF = new java.util.HashMap<>();
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
            sumAll += net; nAll++; bump(daysAll, d, net);                       // 전국면 pool
            boolean regimeMatch = regimeName == null || regimeName.equals(o.getEntryMarketTrend());
            if (regimeMatch) { sumR += net; nR++; bump(daysR, d, net); }         // 국면 매칭
            boolean flowMatch = flowUp != null && o.getEntryIndexMom30() != null && (o.getEntryIndexMom30() >= 0) == flowUp;
            if (regimeMatch && flowMatch) { sumF += net; nF++; bump(daysF, d, net); }   // 국면+흐름 매칭
            // 국면+흐름+폭 매칭 — 흐름 미산출(개장 ~30분)이면 흐름 조건은 생략하고 국면+폭으로만 맞춘다(폭 레이어가 통째로 죽지 않게).
            if (breadthNow != null && regimeMatch && (flowUp == null || flowMatch)
                    && breadthNow.equals(breadthBin(o.getEntryMarketBreadthPct()))) { sumB += net; nB++; bump(daysB, d, net); }
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
        // ⓪-a 국면+흐름+시장폭(4차원) — 폭 버킷 표본이 충족되면 그 버킷만으로 판정(walk-forward 최우위 조합). 부족하면 아래 흐름/국면으로 자연 fallback.
        if (breadthNow != null && nB >= breadthMinSamples) {
            Double avgB = round2(sumB / nB);
            String inner = regimeTag.isEmpty() ? "" : regimeTag.substring(0, regimeTag.length() - 2);
            String breadthTag = inner + (flowUp == null ? "" : "·흐름" + (flowUp ? "↑" : "↓")) + "·폭" + breadthNow + "] ";
            String keyB = hystKey(strategy, market, regimeName, (flowUp == null ? "_" : flowUp ? "up" : "down") + "|b" + breadthNow);
            boolean wasOpenB = hystActive && Boolean.TRUE.equals(openState.get(keyB));
            double effMinNetB = wasOpenB ? props.closeNetAvgPct() : props.minNetAvgPct();
            double shareB = singleDaySharePct(daysB, nB);
            if (clustered(shareB)) {
                if (mutateHyst) openState.put(keyB, false);
                return clusterBlock(strategy, breadthTag, shareB, nB, daysB.size(), avgB, regimeName, market);
            }
            LooNet looB = looTopDay ? looExcludingTopDay(daysB, sumB, nB) : null;
            NetTrend trB = trendOf(daysB);
            boolean allow = decide(avgB >= effMinNetB && (looB == null || looB.net() >= effMinNetB), trB);
            if (mutateHyst) openState.put(keyB, allow);
            return new GateDecision(strategy, allow,
                    String.format("%s폭버킷 %s(net %.2f%% %s 기준 %.2f%%, n=%d)%s%s%s",
                            breadthTag, verdictLabel(allow, avgB >= effMinNetB, trB), avgB, avgB >= effMinNetB ? "≥" : "<", effMinNetB, nB,
                            looB == null ? "" : looB.tag(), trB == null ? "" : trB.tag(), wasOpenB ? HYST_TAG : ""),
                    nB, avgB, regimeName, market, false);
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
            LooNet looF = looTopDay ? looExcludingTopDay(daysF, sumF, nF) : null;
            NetTrend trF = trendOf(daysF);
            boolean allow = decide(avgF >= effMinNetF && (looF == null || looF.net() >= effMinNetF), trF);
            if (mutateHyst) openState.put(keyF, allow);
            return new GateDecision(strategy, allow,
                    String.format("%s흐름버킷 %s(net %.2f%% %s 기준 %.2f%%, n=%d)%s%s%s",
                            flowTag, verdictLabel(allow, avgF >= effMinNetF, trF), avgF, avgF >= effMinNetF ? "≥" : "<", effMinNetF, nF,
                            looF == null ? "" : looF.tag(), trF == null ? "" : trF.tag(), wasOpenF ? HYST_TAG : ""),
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
            LooNet looR = looTopDay ? looExcludingTopDay(daysR, sumR, n) : null;
            NetTrend trR = trendOf(daysR);
            boolean allow = decide(avg >= effMinNetR && (looR == null || looR.net() >= effMinNetR), trR);
            if (mutateHyst) openState.put(keyR, allow);
            return new GateDecision(strategy, allow,
                    String.format("%s%s(net %.2f%% %s 기준 %.2f%%, n=%d)%s%s%s",
                            regimeTag, verdictLabel(allow, avg >= effMinNetR, trR), avg, avg >= effMinNetR ? "≥" : "<", effMinNetR, n,
                            looR == null ? "" : looR.tag(), trR == null ? "" : trR.tag(), wasOpenR ? HYST_TAG : ""),
                    n, avg, regimeName, market, false);
        }
        // 여기 도달 = 엄격(흐름·국면) 버킷 판정에 실패(표본부족) → 아래는 전부 비엄격 경로(fallback·부트스트랩·fail-closed).
        // 이 '버킷'의 열림을 정당화하던 엄격 판정이 더는 없으므로 닫힘으로 명시한다(fail-safe, 클러스터 차단과 동일 사상).
        // 키가 버킷 단위라 정리 범위도 현재 컨텍스트로 한정된다 — 다른 국면·흐름 버킷의 상태는 보존된다(그게 #2의 요점).
        if (mutateHyst) {
            openState.put(hystKey(strategy, market, regimeName, "_"), false);
            if (flowUp != null) openState.put(hystKey(strategy, market, regimeName, flowUp ? "up" : "down"), false);
            if (breadthNow != null) openState.put(hystKey(strategy, market, regimeName,
                    (flowUp == null ? "_" : flowUp ? "up" : "down") + "|b" + breadthNow), false);
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
            LooNet looAll = looTopDay ? looExcludingTopDay(daysAll, sumAll, nAll) : null;
            NetTrend trAll = trendOf(daysAll);
            // ⚠️ 표본 수 요건은 추세와 무관하게 유지 — 추세는 '어느 방향인가'만 바꾸고 '검증됐나'는 못 바꾼다.
            boolean sampleOkAllPass = nAll >= props.fallbackMinSamples() && avgAll != null;
            boolean netOkAllPass = sampleOkAllPass && avgAll >= props.fallbackMinNetAvgPct()
                    && (looAll == null || looAll.net() >= props.fallbackMinNetAvgPct());
            if (sampleOkAllPass && decide(netOkAllPass, trAll)) {
                // ③ 통과 → fallback=true(OrderService가 축소사이징 적용)
                return new GateDecision(strategy, true,
                        // ⚠️ 부등호를 %s로 둔 게 요점 — 추세가 열어준 경우 net은 기준 <b>아래</b>이므로
                        //    "≥"를 하드코딩하면 8/26에 고친 자기모순 문장이 재발한다. 왜 열렸는지는 뒤의 추세 태그가 말한다.
                        String.format("%s국면표본부족(%d/%d)→전국면 fallback통과(net %.2f%% %s %.2f%%, n=%d)%s%s — 축소진입",
                                regimeTag, n, minSamples,
                                avgAll, avgAll >= props.fallbackMinNetAvgPct() ? "≥" : "<",
                                props.fallbackMinNetAvgPct(), nAll,
                                looAll == null ? "" : looAll.tag(), trAll == null ? "" : trAll.tag()),
                        nAll, avgAll, regimeName, market, true);
            }
            // 🐞 2026-08-21: 여기서 무조건 return하는 바람에 아래 '일반 부트스트랩(재검증 다리)' 분기가
            //    prod 설정(fallbackEnabled=true + 국면 산출됨)에서 영영 도달하지 못하는 죽은 코드였다.
            //    bootstrap-strategies에 전략을 넣어도 아무 일이 없었고(J 추가 시도로 발견), CLAUDE.md가 안내하는
            //    "since 리셋 fail-closed 공백은 bootstrap-strategies로 완화" 처방 자체가 작동하지 않았다.
            //    → 부트스트랩 지정 전략은 여기서 끝내지 말고 아래 재검증 다리로 흘려보낸다.
            //    (fallback '통과'는 전국면 pool로 검증된 더 강한 근거라 위에서 먼저 잡히므로 우선순위는 그대로.)
            if (!bootstrapStrategies.contains(strategy)) {
                // 이 분기의 차단 사유는 셋이다: ① 표본부족 ② net 미달 ③ LOO 미달.
                // ③만 걸린 경우를 "fallback미달"로 뭉뚱그리면 원인을 오독하게 된다 — net 미달은 전략 문제지만
                // LOO 미달은 "표본이 사실상 하루"라 시간이 처방이다. ①②는 종전 문구를 그대로 둔다.
                boolean sampleOkAll = nAll >= props.fallbackMinSamples() && avgAll != null;
                boolean netOkAll = sampleOkAll && avgAll >= props.fallbackMinNetAvgPct();
                // ④ 추세 조건부(2026-09-02): net이 기준 위인데 하락곡선이라 닫힌 경우를 "fallback미달"로 찍으면
                //    또 자기모순 문장이 된다(8/26 수정과 같은 유형) — 하락추세 차단을 별도 사유로 분리한다.
                String reasonAll = (trAll != null && trAll.falling() && netOkAll) ? "전국면 net 하락추세 차단"
                        : netOkAll ? "전국면 단일일 편중(LOO 미달)" : "fallback미달";
                return new GateDecision(strategy, false,
                        String.format("%s국면표본부족(%d/%d)+%s(전국면 net %s, n=%d/%d)%s%s",
                                regimeTag, n, minSamples, reasonAll,
                                avgAll == null ? "N/A" : String.format("%.2f%%", avgAll), nAll, props.fallbackMinSamples(),
                                looAll == null ? "" : looAll.tag(), trAll == null ? "" : trAll.tag()),
                        n, avg, regimeName, market, false);
            }
        }
        // 하락추세는 부트스트랩도 닫는다(2026-09-02, 사용자 결정 — `net-trend-closes-bootstrap`, 기본 false).
        //
        // ⚠️ 이게 없으면 "+여도 하락곡선이면 닫는다"가 <b>정작 지금 열려 있는 전략들에는 안 걸린다</b>: 부트스트랩은
        // 성과 판정 자체를 건너뛰는 경로라, 위 fallback에서 하락으로 막혀도 지정 전략이면 여기로 흘러와 열린다
        // (실측 2026-09-02: 실제로 열려 있던 4개 버킷 L KOSPI·G 양시장·J가 전부 이 경로였다).
        //
        // 논리는 "닫기는 빠르게" 원칙 그대로 — 추세가 계산될 만큼(min-days) 거래일이 쌓였다면 그 버킷은 이미
        // "아직 모르는 상태"가 아니다. 판정 풀은 <b>전국면 pool(daysAll)</b>을 쓴다: 부트스트랩에 온 것 자체가
        // 국면·흐름 버킷 표본이 모자랐다는 뜻이라, 그 얇은 버킷으로 추세를 재면 대부분 null이 된다.
        //
        // ⚠️ 상승추세로 부트스트랩을 <b>졸업</b>시키지는 않는다 — 표본 수 요건을 우회하는 셈이 되고,
        // 부트스트랩은 이미 열려 있으므로 열 이유도 없다. 조이는 방향으로만 작용한다.
        NetTrend trBoot = netTrendClosesBootstrap ? trendOf(daysAll) : null;
        if (trBoot != null && trBoot.falling()) {
            return new GateDecision(strategy, false,
                    String.format("%s부트스트랩 net 하락추세 차단(표본 %d/%d, 전국면 n=%d)%s",
                            regimeTag, n, minSamples, nAll, trBoot.tag()),
                    n, avg, regimeName, market, false);
        }
        // ⚠️ 차단될 때만 태그를 붙이면 <b>"왜 안 닫혔나"를 알 수 없다</b> — 거래일이 부족해 판정 자체를 못 한 것인지,
        // 평탄이라 통과한 것인지가 사유만 봐선 구분되지 않는다(실측 2026-09-02: G KOSPI가 열린 채 남았는데 이유 미상).
        // LOO 태그가 "통과/차단 무관 항상 노출"인 것과 같은 사상 — 아래 부트스트랩·fail-closed 경로에도 붙인다.
        String bootTrendTag = trBoot == null
                ? (netTrendClosesBootstrap ? " ·net추세(거래일 부족 — 판정 생략)" : "")
                : trBoot.tag();
        // INVERSE 부트스트랩: 표본 미달이어도 축소사이징(inverseBootstrapSizeMult)으로 실주문 허용 — 적은 비용으로
        // 실표본을 수집(폭락일에만 쌓이는 인버스 특성 보완). 표본이 inverseMinSamples에 차면 이 분기에 안 오고
        // 위 엄격 경로로 자동 졸업: net ≥ 기준이면 정상 사이징(제한 해제), 미달이면 차단(성과 미달을 부트스트랩으로 우회 불가).
        if ("INVERSE".equals(market) && props.inverseBootstrapSizeMult() > 0) {
            return new GateDecision(strategy, true,
                    String.format("%s인버스 부트스트랩(표본 %d/%d) — 축소진입 ×%.1f(검증 전 실표본 수집)%s",
                            regimeTag, n, minSamples, props.inverseBootstrapSizeMult(), bootTrendTag),
                    n, avg, regimeName, market, true);
        }
        // 일반 부트스트랩(재검증 다리): since 리셋 등으로 표본 미달이어도 지정 전략은 축소사이징(fallbackSizeMult)으로 실주문 —
        // 로직 변경 후 완전정지 없이 실표본 수집. 표본이 minSamples에 차면 위 엄격 경로로 자동 졸업(미달이면 차단, 우회 불가).
        if (bootstrapStrategies.contains(strategy)) {
            return new GateDecision(strategy, true,
                    String.format("%s부트스트랩(표본 %d/%d%s) — 축소진입(재검증 중 실표본 수집)%s",
                            regimeTag, n, minSamples, sinceReset ? ", since " + since : "", bootTrendTag),
                    n, avg, regimeName, market, true);
        }
        // fallback 비활성 → 기존 fail-closed
        return new GateDecision(strategy, false,
                String.format("%s표본 부족(%d/%d%s) — 미검증 전략 실주문 차단%s",
                        regimeTag, n, minSamples, sinceReset ? ", since " + since : "", bootTrendTag),
                n, avg, regimeName, market, false);
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
    private static void bump(java.util.Map<String, double[]> m, String d, double net) {
        if (d == null) return;
        double[] cur = m.computeIfAbsent(d, k -> new double[2]);
        cur[0] += 1;
        cur[1] += net;
    }

    /** 버킷의 최대 단일 거래일 점유율(%). 표본 없으면 0. */
    private static double singleDaySharePct(java.util.Map<String, double[]> days, int n) {
        if (n <= 0 || days.isEmpty()) return 0;
        double max = 0;
        for (double[] v : days.values()) if (v[0] > max) max = v[0];
        return 100.0 * max / n;
    }

    /**
     * 최대기여일 제외 net(LOO, 2026-08-20) — "이 버킷의 net이 단 하루로 설명되는가".
     *
     * <p>점유율 가드(80%)만으로는 부족했다: D-KOSPI는 74/100(74%)로 문턱을 간발로 통과했는데 그 74건이
     * 7/31 하루(+3.82%)라 버킷 net +2.52%가 사실상 그 하루였다. 게다가 점유율은 <b>새 진입/섀도우가 분모에
     * 쌓이기만 해도 희석</b>돼(8/20 장중 90% → 밤 74%) 그 하루에 대한 새 증거 없이 게이트가 다시 열린다.</p>
     *
     * <p><b>net 합이 가장 큰 하루</b>를 빼고 남은 표본의 net을 돌려준다. 호출측은 전체 net과 이 값이
     * <b>둘 다</b> 기준을 넘을 때만 연다.</p>
     *
     * <p>⚠️ <b>남은 net이 전체 net 이하라는 보장은 없다</b> — 표본 수가 많고 평균이 낮은 날이 'net 합 최대'로
     * 뽑히면, 그날을 뺀 나머지 평균이 오히려 올라간다(실측 2026-08-20 H-KOSPI: 전체 −0.32% → 제외 −0.26%).
     * 그래도 <b>게이트 동작은 단조적으로 엄격</b>하다 — 기존 조건에 AND로 얹기만 하므로 닫히는 방향으로만
     * 작용하고, 이 요건 때문에 기존에 닫혀 있던 버킷이 열리는 일은 없다.</p>
     *
     * <p>거래일이 하나뿐이거나 남는 표본이 없으면 null(판정 생략) — 그 경우는 점유율 가드가 처리한다.</p>
     */
    /**
     * 차단 사유 라벨 — <b>net 미달</b>과 <b>LOO(단일일 편중) 미달</b>을 구분한다.
     *
     * <p>🐞 2026-08-26 수정: 종전엔 둘 다 {@code "성과 미달"}로 찍으면서 바로 뒤 부등호는 net 조건만 반영해,
     * LOO만 걸린 경우 <b>"성과 미달(net 0.61% ≥ 기준 0.30%)"</b>라는 자기모순 문장이 나왔다(실측 2026-08-26
     * REVERSAL_L — 그날 L 후보 8건이 이 문장으로 차단됐고, 읽는 사람은 게이트 오작동으로 오해하게 된다).
     * 차단 자체는 정상이었고 진짜 원인은 뒤에 붙는 {@code ·최대기여일(...) 제외 net} 태그에만 있었다.</p>
     *
     * <p>원인 구분이 중요한 이유: <b>net 미달은 전략 문제</b>(임계·필터를 손봐야 한다)지만
     * <b>LOO 미달은 표본 문제</b>(다른 날 표본이 쌓이면 저절로 풀린다)라 처방이 정반대다.</p>
     *
     * @param allow 최종 허용 여부(net AND LOO)
     * @param netOk net 조건만 봤을 때의 통과 여부 — allow=false인데 netOk=true면 LOO가 단독 차단 사유
     */
    static String verdictLabel(boolean allow, boolean netOk) {
        if (allow) return "통과";
        return netOk ? "단일일 편중(LOO 미달)" : "성과 미달";
    }

    /**
     * 추세 조건부까지 반영한 사유 라벨(2026-09-02).
     *
     * <p>추세가 판정자면 <b>수준 라벨을 쓰면 안 된다</b> — net이 기준 위인데 하락추세로 닫힌 것을 "성과 미달"로
     * 찍으면 8/26에 고친 자기모순 문장이 그대로 재발한다(그때도 라벨은 AND 결과인데 부등호는 한쪽만 반영했다).</p>
     */
    static String verdictLabel(boolean allow, boolean netOk, NetTrend tr) {
        if (tr != null && tr.falling()) return "net 하락추세 차단";
        if (tr != null && tr.rising()) return "net 상승추세 통과";
        return verdictLabel(allow, netOk);
    }

    private static LooNet looExcludingTopDay(java.util.Map<String, double[]> days, double sum, int n) {
        if (days.size() < 2 || n <= 0) return null;
        String top = null;
        double topSum = Double.NEGATIVE_INFINITY;
        for (var e : days.entrySet()) {
            if (e.getValue()[1] > topSum) { topSum = e.getValue()[1]; top = e.getKey(); }
        }
        int topCount = (int) days.get(top)[0];
        int rest = n - topCount;
        if (rest <= 0) return null;
        return new LooNet(top, topCount, Math.round((sum - topSum) / rest * 100.0) / 100.0);
    }

    /** 최대기여일 제외 결과 — 사유문에 "어느 날을 뺐고 남은 net이 얼마인지"까지 실어 가시화한다. */
    private record LooNet(String day, int count, double net) {
        String tag() {
            return String.format(" ·최대기여일(%s, n=%d) 제외 net %.2f%%", day, count, net);
        }
    }

    /**
     * 버킷 net의 <b>방향</b>(2026-09-02) — 일별 평균 net을 거래일 순서에 대해 <b>표본가중 최소제곱</b>으로 회귀한 기울기.
     *
     * <p>단위는 <b>%p/거래일</b>. x는 달력일이 아니라 <b>정렬된 거래일의 인덱스</b>다 — 주말·휴장이 x를 늘리면
     * 연휴를 낀 버킷의 기울기만 구조적으로 완만해진다(신호가 아니라 달력이 만든 차이).</p>
     *
     * <p>가중치는 그날 표본 수다. 1건짜리 날과 20건짜리 날을 같은 무게로 두면 <b>기울기가 표본 1건에 끌려간다</b>
     * — 이 시스템이 반복해 당한 단일일 아티팩트의 회귀판이다.</p>
     *
     * <p><b>전수 LOO</b>: 하루씩 빼며 기울기를 다시 구해 최소·최대를 함께 돌려준다. 상승 판정은 <b>최소값까지
     * 양수</b>일 때만 성립한다(= 어느 하루를 빼도 상승) — 반등 하루가 게이트를 여는 것을 막는다. 하락 판정에는
     * LOO를 요구하지 않는다(닫는 방향은 빠르게).</p>
     *
     * <p><b>마지막 판정근거일 net &gt; {@code lastDayMinPct}</b>(2026-09-02 추가, 상승 판정 전용): 기울기만 보면
     * <b>"덜 지는 중"과 "이기는 중"이 구분되지 않는다</b> — −4%에서 −2%로 개선되는 전략도 상승곡선이라
     * 열려버리고, 그건 여전히 손실이다. 마지막으로 판정 근거가 된 거래일의 net이 실제로 <b>흑자</b>여야
     * 연다(net은 이미 비용 차감분이라 &gt;0 = 진짜 흑자). 하락 판정엔 요구하지 않는다(비대칭 유지).</p>
     *
     * <p>⚠️ 마지막 날 표본이 1건일 수도 있다. 그래도 안전한 이유는 이 조건이 <b>조이는 방향으로만</b>
     * 작용하기 때문이다 — 잘못 닫을 수는 있어도 잘못 열 수는 없다(fail-closed).</p>
     *
     * <p>⚠️ 하루를 뺄 때 <b>남은 날의 x는 원래 인덱스를 유지</b>한다. 다시 0..k-2로 매기면 가운데 하루를 뺄 때
     * 시간축이 압축돼 기울기가 부풀려진다.</p>
     *
     * @return 거래일이 {@code minDays} 미만이거나 기울기가 정의되지 않으면 null(판정 생략)
     */
    static NetTrend netTrend(java.util.Map<String, double[]> days, int minDays, double upPct, double downPct,
                             double lastDayMinPct) {
        if (days == null || days.size() < Math.max(3, minDays)) return null;
        java.util.List<String> keys = new java.util.ArrayList<>(days.keySet());
        java.util.Collections.sort(keys);   // yyyyMMdd 고정폭 → 사전식 정렬이 곧 시간순
        int k = keys.size();
        double[] w = new double[k];
        double[] y = new double[k];
        for (int i = 0; i < k; i++) {
            double[] v = days.get(keys.get(i));
            w[i] = v[0];                              // 그날 표본 수 = 가중치
            y[i] = v[0] > 0 ? v[1] / v[0] : 0;        // 그날 평균 net(%)
        }
        Double slope = wlsSlope(w, y, -1);
        if (slope == null) return null;
        double looMin = Double.POSITIVE_INFINITY;
        double looMax = Double.NEGATIVE_INFINITY;
        for (int drop = 0; drop < k; drop++) {
            Double sd = wlsSlope(w, y, drop);
            if (sd == null) continue;
            looMin = Math.min(looMin, sd);
            looMax = Math.max(looMax, sd);
        }
        boolean looComputed = looMin != Double.POSITIVE_INFINITY;
        // 마지막 판정근거일 = 이 버킷에 표본이 있는 가장 최근 거래일(달력상 어제가 아니라 '마지막으로 판정 근거가 된 날').
        double lastNet = y[k - 1];
        boolean rising = slope > 0 && slope >= upPct && looComputed && looMin > 0 && lastNet > lastDayMinPct;
        boolean falling = slope < 0 && slope <= -downPct;
        return new NetTrend(slope, k, rising, falling,
                looComputed ? looMin : null, looComputed ? looMax : null,
                keys.get(k - 1), lastNet, (int) w[k - 1]);
    }

    /** 표본가중 최소제곱 기울기. {@code skip}(음수면 없음) 인덱스는 제외하되 남은 점의 x는 원래 인덱스 유지. */
    private static Double wlsSlope(double[] w, double[] y, int skip) {
        double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        int used = 0;
        for (int i = 0; i < w.length; i++) {
            if (i == skip || w[i] <= 0) continue;
            sw += w[i];
            sx += w[i] * i;
            sy += w[i] * y[i];
            sxx += w[i] * i * i;
            sxy += w[i] * i * y[i];
            used++;
        }
        if (used < 2) return null;
        double denom = sw * sxx - sx * sx;
        if (Math.abs(denom) < 1e-9) return null;   // 모든 x가 같음(불가) 또는 수치적 퇴화
        return (sw * sxy - sx * sy) / denom;
    }

    /** 버킷 net의 방향 — 사유문에 기울기·거래일·LOO 범위·마지막 판정근거일까지 실어 "왜 열렸나/닫혔나"를 드러낸다. */
    record NetTrend(double slope, int days, boolean rising, boolean falling, Double looMin, Double looMax,
                    String lastDay, double lastDayNet, int lastDayN) {
        String tag() {
            String s = String.format(" ·net추세 %+.3f%%p/일(%d거래일", slope, days);
            if (looMin != null) s += String.format(", LOO %+.3f~%+.3f", looMin, looMax);
            s += String.format(", 마지막 %s net %+.2f%%(n=%d)", lastDay, lastDayNet, lastDayN);
            return s + (rising ? ", 상승" : falling ? ", 하락" : ", 평탄") + ")";
        }
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
