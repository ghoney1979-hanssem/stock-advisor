package com.stockadvisor.controller;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.service.DisclosurePollingService;
import com.stockadvisor.service.HoldScoreService;
import com.stockadvisor.service.DailyReportService;
import com.stockadvisor.service.FillSyncService;
import com.stockadvisor.service.StrategyPerformanceGate;
import com.stockadvisor.service.StrategyHoldTimeProvider;
import com.stockadvisor.service.StrategyStopProvider;
import com.stockadvisor.service.MarketBreadthService;
import com.stockadvisor.service.SwingTrailAnalysisService;
import com.stockadvisor.service.SwingExitProvider;
import com.stockadvisor.service.GateChangeNotifier;
import com.stockadvisor.service.MarketOpenNotifier;
import com.stockadvisor.service.StrategyEvaluator;
import com.stockadvisor.service.MarketRegimeService;
import com.stockadvisor.service.MarketRiskGuard;
import com.stockadvisor.service.PositionSizer;
import com.stockadvisor.service.ExecutionCostModel;
import com.stockadvisor.service.ExitMethodProvider;
import com.stockadvisor.service.SectorValuationService;
import com.stockadvisor.service.ControlAnalysisService;
import com.stockadvisor.service.ExecutionQualityService;
import com.stockadvisor.service.OrderChaseAnalysisService;
import com.stockadvisor.service.BacktestService;
import com.stockadvisor.service.RegimeBacktagService;
import com.stockadvisor.service.FlowBacktagService;
import com.stockadvisor.service.InvestorFlowBacktagService;
import com.stockadvisor.service.DailyHistoryBackfillService;
import com.stockadvisor.service.FinancialFactBackfillService;
import com.stockadvisor.service.FinancialSpreadAnalysisService;
import com.stockadvisor.service.MultidayBacktestService;
import com.stockadvisor.service.SelectionSweepService;
import com.stockadvisor.service.SleeveService;
import com.stockadvisor.service.ShareInfoBackfillService;
import com.stockadvisor.service.ValueSweepService;
import com.stockadvisor.service.NewsBacktagService;
import com.stockadvisor.service.FlowAnalysisService;
import com.stockadvisor.service.MaeAnalysisService;
import com.stockadvisor.service.OrderCancelService;
import com.stockadvisor.service.OrderService;
import com.stockadvisor.service.PositionExitService;
import com.stockadvisor.service.PositionReconcileService;
import com.stockadvisor.service.ExitStrategyService;
import com.stockadvisor.service.ExitTimingService;
import com.stockadvisor.service.MarketSignalService;
import com.stockadvisor.service.OutcomeAnalysisService;
import com.stockadvisor.service.SignalAlertService;
import com.stockadvisor.service.SignalResult;
import com.stockadvisor.service.StrategyReportService;
import com.stockadvisor.service.TradeFollowUpService;
import com.stockadvisor.service.WatchlistScanService;
import com.stockadvisor.service.WatchlistSyncService;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 스케줄을 기다리지 않고 파이프라인을 수동 실행하기 위한 관리/테스트용 엔드포인트.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class SignalAdminController {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HoldScoreService holdScoreService;   // 필드주입 — 생성자 무churn

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.repository.OutcomeDailyMarkRepository dailyMarkRepository;   // 멀티데이 일봉마크(Phase 1 측정) — 필드주입

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.service.MultidayExitAnalysisService multidayExitAnalysisService;   // 멀티데이 청산 시뮬(Phase 2) — 필드주입

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.service.FeatureMiningService featureMiningService;   // feature-space 마이닝(생성적 분석) — 필드주입

    private final DisclosurePollingService pollingService;
    private final SignalAlertService signalAlertService;
    private final MarketSignalService marketSignalService;
    private final WatchlistSyncService watchlistSyncService;
    private final TradeFollowUpService tradeFollowUpService;
    private final StrategyReportService strategyReportService;
    private final WatchlistScanService watchlistScanService;
    private final OutcomeAnalysisService outcomeAnalysisService;
    private final ExitTimingService exitTimingService;
    private final DailyReportService dailyReportService;
    private final ExitStrategyService exitStrategyService;
    private final KisApiClient kisApiClient;
    private final OrderService orderService;
    private final PositionExitService positionExitService;
    private final PositionReconcileService positionReconcileService;
    private final FillSyncService fillSyncService;
    private final OrderCancelService orderCancelService;
    private final StrategyPerformanceGate strategyPerformanceGate;
    private final StrategyHoldTimeProvider strategyHoldTimeProvider;
    private final StrategyStopProvider strategyStopProvider;
    private final MarketBreadthService marketBreadthService;
    // 유니버스 스냅샷 수집 현황 조회 — 필드주입(생성자 무churn).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.service.UniverseSnapshotService universeSnapshotService;
    // 유니버스 횡단면 분석 — 동일 패턴(생성자 무churn, 미주입 테스트 보호).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.service.UniverseAnalysisService universeAnalysisService;
    private final SwingTrailAnalysisService swingTrailAnalysisService;
    private final SwingExitProvider swingExitProvider;
    private final MarketRegimeService marketRegimeService;
    private final MarketRiskGuard marketRiskGuard;
    private final PositionSizer positionSizer;
    private final ExecutionCostModel executionCostModel;
    private final ExitMethodProvider exitMethodProvider;
    private final SectorValuationService sectorValuationService;
    private final ControlAnalysisService controlAnalysisService;
    private final ExecutionQualityService executionQualityService;
    private final OrderChaseAnalysisService orderChaseAnalysisService;
    private final BacktestService backtestService;
    private final RegimeBacktagService regimeBacktagService;
    private final FlowBacktagService flowBacktagService;
    private final InvestorFlowBacktagService investorFlowBacktagService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NewsBacktagService newsBacktagService;   // 뉴스 소급 — 필드주입(생성자 무churn)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyHistoryBackfillService dailyHistoryBackfillService;   // 일봉 히스토리 적재 — 필드주입(생성자 무churn)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinancialFactBackfillService financialFactBackfillService;       // DART 재무 소급 — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FinancialSpreadAnalysisService financialSpreadAnalysisService;   // 선정력(랭킹 스프레드) — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MultidayBacktestService multidayBacktestService;                 // 멀티데이 백테스트 — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SelectionSweepService selectionSweepService;                     // 선정 축 탐색 — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SleeveService sleeveService;                                     // 멀티데이 섀도우 슬리브 — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShareInfoBackfillService shareInfoBackfillService;               // 액면가·주식수 백필 — 필드주입
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ValueSweepService valueSweepService;                             // 가치 축 백테스트 — 필드주입
    private final FlowAnalysisService flowAnalysisService;
    private final MaeAnalysisService maeAnalysisService;
    private final MarketOpenNotifier marketOpenNotifier;
    private final GateChangeNotifier gateChangeNotifier;
    private final StrategyEvaluator strategyEvaluator;

    public SignalAdminController(DisclosurePollingService pollingService,
                                 SignalAlertService signalAlertService,
                                 MarketSignalService marketSignalService,
                                 WatchlistSyncService watchlistSyncService,
                                 TradeFollowUpService tradeFollowUpService,
                                 StrategyReportService strategyReportService,
                                 WatchlistScanService watchlistScanService,
                                 OutcomeAnalysisService outcomeAnalysisService,
                                 ExitTimingService exitTimingService,
                                 DailyReportService dailyReportService,
                                 ExitStrategyService exitStrategyService,
                                 KisApiClient kisApiClient,
                                 OrderService orderService,
                                 PositionExitService positionExitService,
                                 PositionReconcileService positionReconcileService,
                                 FillSyncService fillSyncService,
                                 OrderCancelService orderCancelService,
                                 StrategyPerformanceGate strategyPerformanceGate,
                                 StrategyHoldTimeProvider strategyHoldTimeProvider,
                                 StrategyStopProvider strategyStopProvider,
                                 MarketBreadthService marketBreadthService,
                                 SwingTrailAnalysisService swingTrailAnalysisService,
                                 SwingExitProvider swingExitProvider,
                                 MarketRegimeService marketRegimeService,
                                 MarketRiskGuard marketRiskGuard,
                                 PositionSizer positionSizer,
                                 ExecutionCostModel executionCostModel,
                                 ExitMethodProvider exitMethodProvider,
                                 SectorValuationService sectorValuationService,
                                 ControlAnalysisService controlAnalysisService,
                                 ExecutionQualityService executionQualityService,
                                 OrderChaseAnalysisService orderChaseAnalysisService,
                                 BacktestService backtestService,
                                 RegimeBacktagService regimeBacktagService,
                                 FlowBacktagService flowBacktagService,
                                 InvestorFlowBacktagService investorFlowBacktagService,
                                 FlowAnalysisService flowAnalysisService,
                                 MaeAnalysisService maeAnalysisService,
                                 MarketOpenNotifier marketOpenNotifier,
                                 GateChangeNotifier gateChangeNotifier,
                                 StrategyEvaluator strategyEvaluator) {
        this.pollingService = pollingService;
        this.signalAlertService = signalAlertService;
        this.marketSignalService = marketSignalService;
        this.watchlistSyncService = watchlistSyncService;
        this.tradeFollowUpService = tradeFollowUpService;
        this.strategyReportService = strategyReportService;
        this.watchlistScanService = watchlistScanService;
        this.outcomeAnalysisService = outcomeAnalysisService;
        this.exitTimingService = exitTimingService;
        this.dailyReportService = dailyReportService;
        this.exitStrategyService = exitStrategyService;
        this.kisApiClient = kisApiClient;
        this.orderService = orderService;
        this.positionExitService = positionExitService;
        this.positionReconcileService = positionReconcileService;
        this.fillSyncService = fillSyncService;
        this.orderCancelService = orderCancelService;
        this.strategyPerformanceGate = strategyPerformanceGate;
        this.strategyHoldTimeProvider = strategyHoldTimeProvider;
        this.strategyStopProvider = strategyStopProvider;
        this.marketBreadthService = marketBreadthService;
        this.swingTrailAnalysisService = swingTrailAnalysisService;
        this.swingExitProvider = swingExitProvider;
        this.marketRegimeService = marketRegimeService;
        this.marketRiskGuard = marketRiskGuard;
        this.positionSizer = positionSizer;
        this.executionCostModel = executionCostModel;
        this.exitMethodProvider = exitMethodProvider;
        this.sectorValuationService = sectorValuationService;
        this.controlAnalysisService = controlAnalysisService;
        this.executionQualityService = executionQualityService;
        this.orderChaseAnalysisService = orderChaseAnalysisService;
        this.backtestService = backtestService;
        this.regimeBacktagService = regimeBacktagService;
        this.flowBacktagService = flowBacktagService;
        this.investorFlowBacktagService = investorFlowBacktagService;
        this.flowAnalysisService = flowAnalysisService;
        this.maeAnalysisService = maeAnalysisService;
        this.marketOpenNotifier = marketOpenNotifier;
        this.gateChangeNotifier = gateChangeNotifier;
        this.strategyEvaluator = strategyEvaluator;
    }

    /** 국면 태그가 비어있는 과거 섀도우 표본에 시장 국면 소급 태깅(null만, 확정종가 기준, 재실행 안전). */
    @PostMapping("/backfill-regime-tags")
    public RegimeBacktagService.BacktagResult backfillRegimeTags() {
        return regimeBacktagService.backfill();
    }

    /**
     * 외국인·기관 수급(순매수 비중) 소급 태깅 — 종목당 1콜로 그 종목의 모든 과거 진입일(대조군 포함)을 채운다.
     *
     * <p>⚠️ KIS 이력이 ~30거래일이라 <b>창이 매일 뒤로 밀린다</b> — 오래된 표본을 원하면 미루지 말 것.
     * 재실행 안전(이미 태깅된 종목은 조회조차 하지 않는다).</p>
     */
    @PostMapping("/backfill-investor-flow")
    public InvestorFlowBacktagService.BacktagReport backfillInvestorFlow(
            @RequestParam(required = false) Integer lookbackDays) {
        return investorFlowBacktagService.backfill(lookbackDays);
    }

    /**
     * 일봉 히스토리 대량 적재 — <b>멀티데이 전략 백테스트의 전제 데이터</b>.
     *
     * <p>이 시스템이 지금까지 백테스트를 못 한 건 과거 <b>분봉</b>이 없어서였다. 멀티데이 전략은 일봉만으로
     * 재현되므로 장기 일봉만 확보하면 파라미터 탐색·꼬리 손실 측정이 열린다. 소스가 1콜에 10년치를 줘서
     * 종목당 1콜(워치리스트 1,500콜)로 끝난다.</p>
     *
     * <p>⚠️ 결과는 <b>낙관 상한</b>이다(생존편향 — 대상이 오늘 기준 시총 상위 1,500).
     * ⚠️ 장중 실행은 권하지 않는다 — 라이브 매매와 CPU·네트워크를 경합한다.</p>
     */
    @PostMapping("/backfill-daily-history")
    public DailyHistoryBackfillService.BackfillReport backfillDailyHistory(
            @RequestParam(required = false) Integer years,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String startDate,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) String source) {
        return dailyHistoryBackfillService.backfill(years, limit, startDate, force, source);
    }

    /** 일봉 히스토리 적재 현황(종목 수·행 수·구간). */
    @GetMapping("/daily-history-status")
    public Map<String, Object> dailyHistoryStatus() {
        return dailyHistoryBackfillService.status();
    }

    /**
     * DART 연간 재무 소급 수집 — 종목 <b>선정력</b> 측정(F-Score 랭킹 스프레드)의 전제 데이터.
     *
     * <p>⚠️ DART 일일 한도(~2만)를 쓰므로 {@code maxCalls}로 상한을 둔다(초과분은 다음 실행이 이어받는다 —
     * 이미 가진 (종목,연도)는 조회조차 하지 않는다). 10년×1,500종목 ≈ 15,000콜.</p>
     */
    @PostMapping("/backfill-financial-facts")
    public FinancialFactBackfillService.BackfillReport backfillFinancialFacts(
            @RequestParam(required = false) Integer years,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer maxCalls) {
        return financialFactBackfillService.backfill(years, limit, maxCalls);
    }

    /** 재무 소급 수집 현황(연도별 행 수). */
    @GetMapping("/financial-facts-status")
    public Map<String, Object> financialFactsStatus() {
        return financialFactBackfillService.status();
    }

    /**
     * <b>랭킹 스프레드</b> — "재무 스코어에 종목 선정력이 있는가"를 전략 구현 전에 답한다.
     *
     * <p>주지표는 절대 수익률이 아니라 <b>버킷 간 차이</b>(lift·스프레드)다 — 생존편향이 상·하위에 공통으로
     * 걸리므로 차이만 유효하다. <b>연도별 부호 유지</b>를 반드시 함께 볼 것.</p>
     */
    @GetMapping("/financial-spread")
    public FinancialSpreadAnalysisService.SpreadReport financialSpread(
            @RequestParam(required = false) Integer horizonMonths,
            @RequestParam(required = false) Integer minEvaluated,
            @RequestParam(required = false) Integer highMin,
            @RequestParam(required = false) Integer lowMax,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer scoreMin) {
        return financialSpreadAnalysisService.analyze(horizonMonths, minEvaluated, highMin, lowMax, topN, scoreMin);
    }

    /**
     * 멀티데이 백테스트 — <b>월별 진입 + 트레일링 청산</b> 경로 시뮬.
     *
     * <p>앞선 {@code /financial-spread}는 12개월 고정 보유라 <b>전략이 아니라 재무 갱신 주기</b>를 쟀고,
     * 그 탓에 독립 관측이 연 단위 9개뿐이라 한 해가 결과를 지배했다. 여기선 진입을 매달로 늘려 관측을 ~110개로 만든다.</p>
     *
     * <p>판정은 응답의 {@code verdict}(사전 등록 기준)를 볼 것 — 수치를 보고 기준을 정하지 않기 위해 코드에 박아뒀다.</p>
     */
    @GetMapping("/multiday-backtest")
    public MultidayBacktestService.BacktestReport multidayBacktest(
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer scoreMin,
            @RequestParam(required = false) Double armPct,
            @RequestParam(required = false) Double dropPct,
            @RequestParam(required = false) Integer maxHoldMonths,
            @RequestParam(required = false) Integer lookbackMonths,
            @RequestParam(required = false) Integer minEvaluated) {
        return multidayBacktestService.run(topN, scoreMin, armPct, dropPct,
                maxHoldMonths, lookbackMonths, minEvaluated);
    }

    /**
     * <b>종목 선정 축 탐색</b> — "무엇을 사야 하나"의 후보를 같은 규칙으로 한 번에 잰다.
     *
     * <p>⚠️ 축 8개 × 방향 2개 = 16개를 돌리므로 <b>하나는 우연히 통과한다.</b>
     * {@code until}로 탐색 구간을 자르고, 거기서 통과한 축만 {@code since}로 holdout에서 <b>딱 한 번</b> 확인할 것.
     * 탐색 구간 결과 자체를 채택 근거로 쓰면 2026-08-21 발굴 세션(통과 pocket 50개가 전부 허수)을 반복한다.</p>
     */
    /** 액면가·상장주식수 백필(KIS 종목당 1콜, 캐시 우회) — 가치 축 백테스트의 과거 시총 복원 전제. */
    @PostMapping("/backfill-share-info")
    public ShareInfoBackfillService.Report backfillShareInfo(@RequestParam(defaultValue = "false") boolean force,
                                                            @RequestParam(required = false) Integer limit) {
        return shareInfoBackfillService.backfill(force, limit);
    }

    /** 가치 축(PBR·이익수익률 ± F-Score 결합) 월별 코호트 백테스트 — 탐색/holdout은 since/until로. */
    @GetMapping("/value-sweep")
    public ValueSweepService.Report valueSweep(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer maxHoldMonths,
            @RequestParam(required = false) Integer scoreMin,
            @RequestParam(required = false) Integer minEvaluated,
            @RequestParam(required = false) Long minPriceKrw,
            @RequestParam(required = false) Long minTurnoverKrw) {
        return valueSweepService.sweep(since, until, topN, maxHoldMonths, scoreMin, minEvaluated, minPriceKrw, minTurnoverKrw);
    }

    @GetMapping("/selection-sweep")
    public SelectionSweepService.SweepReport selectionSweep(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer maxHoldMonths,
            @RequestParam(required = false) Long minPriceKrw,
            @RequestParam(required = false) Long minTurnoverKrw) {
        return selectionSweepService.sweep(since, until, topN, maxHoldMonths, minPriceKrw, minTurnoverKrw);
    }

    /**
     * 멀티데이 섀도우 슬리브 리밸런싱 — 96조합 백테스트의 <b>유일 생존자</b>(`HIGH_52W_HIGH`)를 실시간 기록한다.
     *
     * <p>⚠️ 실주문 없음. holdout을 3회 소진해 백테스트로는 더 검증할 수 없어, 남은 수단이 포워드뿐이다.
     * {@code dryRun=true}면 선정만 보고 저장하지 않는다.</p>
     */
    @PostMapping("/sleeve-rebalance")
    public SleeveService.RebalanceReport sleeveRebalance(
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return sleeveService.rebalance(dryRun);
    }

    /** 슬리브 사이클별 성과 — <b>절대 수익이 아니라 excessPct(유니버스 대비)로 판정할 것</b>. */
    @GetMapping("/sleeve-report")
    public SleeveService.SleeveReport sleeveReport() {
        return sleeveService.report();
    }

    /** 특정 (종목, 사업연도)의 F-Score 기준별 충족 내역(진단). */
    @GetMapping("/financial-score")
    public Map<String, Object> financialScore(@RequestParam String stockCode,
                                              @RequestParam String businessYear) {
        return financialSpreadAnalysisService.explain(stockCode, businessYear);
    }

    /**
     * 뉴스 feature 소급 태깅 — 대조군 커버리지 0%(=8/21 "뉴스가 나쁜 건지 뉴스 나는 종목이 나쁜 건지" 미판정)를 해소한다.
     *
     * <p>{@code force=true}(기본)면 기존 라이브 태깅분도 다시 덮는다 — 진입군 77.4% / 대조군 0%인
     * <b>비대칭 커버리지는 비교 자체를 편향</b>시키므로 양쪽을 같은 방법으로 맞춘다.</p>
     */
    @PostMapping("/backfill-news")
    public NewsBacktagService.BacktagReport backfillNews(
            @RequestParam(required = false) Integer lookbackDays,
            @RequestParam(defaultValue = "true") boolean force) {
        return newsBacktagService.backfill(lookbackDays, force);
    }

    /** 장중흐름(mom30/60) 소급 태깅 — 저장된 entry_market_change로 각 날 지수경로 재구성·보간(근사, mom10 제외). */
    @PostMapping("/backfill-flow-tags")
    public FlowBacktagService.BacktagResult backfillFlowTags() {
        return flowBacktagService.backfill();
    }

    /**
     * 멀티데이 일봉 종가 경로 소급 백필(Phase 2 즉시가동) — 기존 C/D/J 진입분에 KIS 일봉(~30거래일)으로
     * D0..D+15 종가를 채운다. 결과: 처리한 outcome 수. 이후 /multiday-exit-comparison 으로 시뮬.
     */
    @PostMapping("/backfill-multiday-marks")
    public java.util.Map<String, Object> backfillMultidayMarks() {
        return java.util.Map.of("touched", tradeFollowUpService.backfillMultidayMarks());
    }

    /** 멀티데이 청산 트리거 시뮬 — 일봉 경로에 보유D+N/트레일%/MA이탈/손절 시뮬해 전략별 net 최대 방식(Phase 2). */
    /**
     * @param fullPathsOnly true면 D+15까지 마크가 다 찬 표본만(고정 코호트) — horizon마다 표본이 바뀌는
     *                      코호트 편향 없이 "보유기간만"의 효과를 본다. 대가는 표본 급감이라 기본(false)과 함께 볼 것.
     */
    @GetMapping("/multiday-exit-comparison")
    public Object multidayExitComparison(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean fullPathsOnly) {
        if (multidayExitAnalysisService == null) return java.util.Map.of("error", "service unavailable");
        return multidayExitAnalysisService.compare(fullPathsOnly);
    }

    /**
     * Feature-space 마이닝(생성적 분석) — 쌓인 진입을 feature 축으로 bin해 "어떤 조건 구간이 수익이었나" 스캔.
     * 교차거래일 가드로 단일일 클러스터 pocket은 highlights에서 제외. 아직 전략화 안 된 수익 조건 발굴용.
     * 파라미터: lookbackDays(40)·market·regime(국면 세그먼트)·minSamples(20)·maxDayShare(80)·includeControl(false).
     */
    @GetMapping("/feature-mining")
    public Object featureMining(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "40") int lookbackDays,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "close") String horizon,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String market,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String regime,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int minSamples,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "80") double maxDayShare,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean includeControl,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String since,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String until,
            // 전략별 분해(2026-09-02) — 풀링 edge만 보고 전역 필터를 넣지 않기 위한 전제. 저장 컬럼 필터라 비용 0.
            @org.springframework.web.bind.annotation.RequestParam(required = false) String strategy) {
        if (featureMiningService == null) return java.util.Map.of("error", "service unavailable");
        return featureMiningService.mine(lookbackDays, horizon, market, regime, minSamples, maxDayShare,
                includeControl, since, until, strategy);
    }

    /** 장중흐름 분석 — 전략별 진입시 지수흐름(mom lag) 부호별 net·승률(exit-horizon)·국면분리·what-if. lag=30|60. */
    /** 보유 점수(2026-07-23, 관찰 전용) — 미청산 포지션별 7신호 합의 "계속 들고갈만한가". 청산 결정과 무관. */
    @GetMapping("/hold-score")
    public List<HoldScoreService.HoldScore> holdScore() {
        return holdScoreService.scores();
    }

    @GetMapping("/flow-analysis")
    public List<FlowAnalysisService.StrategyFlowAnalysis> flowAnalysis(
            @RequestParam(defaultValue = "60") int lag) {
        return flowAnalysisService.analyze(lag);
    }

    /** MAE 히트 분석 — 전략별 승자/패자 MAE(최대 역행)·MFE 분포 + 손절선 시뮬(−7% 검증). peak/trough 기반. */
    @GetMapping("/heat-analysis")
    public List<MaeAnalysisService.StrategyHeat> heatAnalysis() {
        return maeAnalysisService.analyze();
    }

    /** 체결 동기화 즉시 실행 — LIVE 접수 주문의 실제 체결 반영(DRY_RUN은 no-op). */
    @PostMapping("/sync-fills")
    public Map<String, Object> syncFills() {
        return Map.of("updated", fillSyncService.syncFills());
    }

    /** 미체결 주문 취소 즉시 실행 — 타임아웃 경과 지정가 취소(DRY_RUN은 no-op). */
    @PostMapping("/cancel-stale-orders")
    public Map<String, Object> cancelStaleOrders() {
        return Map.of("cancelled", orderCancelService.cancelStaleOrders());
    }

    /** 승인 대기 주문 목록. */
    @GetMapping("/orders/pending")
    public List<Order> pendingOrders() {
        return orderService.pendingApprovals();
    }

    /** 승인 대기 주문 승인 → 발사. */
    @PostMapping("/orders/{id}/approve")
    public OrderService.OrderResult approveOrder(@PathVariable long id) {
        return orderService.approve(id);
    }

    /** 승인 대기 주문 거부. */
    @PostMapping("/orders/{id}/reject")
    public OrderService.OrderResult rejectOrder(@PathVariable long id) {
        return orderService.reject(id);
    }

    /** 잔고 reconcile 즉시 실행 — 내부 포지션 ↔ KIS 실계좌 대조(LIVE만 실동작, DRY_RUN은 skip). */
    @PostMapping("/reconcile")
    public PositionReconcileService.ReconcileResult reconcile() {
        return positionReconcileService.reconcile();
    }

    /** 시간기반 청산 즉시 실행 — 보유시간 경과/장마감 포지션 매도. */
    @PostMapping("/close-positions")
    public Map<String, Object> closePositions() {
        int closed = positionExitService.closeDuePositions();
        return Map.of("closed", closed);
    }

    /** 계좌 잔고·예수금 조회 (주문 없이 계좌 API 권한 검증용). */
    @GetMapping("/account-balance")
    public KisBalanceResponse accountBalance() {
        return kisApiClient.fetchBalance();
    }

    /**
     * 수동 주문 트리거 (정책·모드 그대로 적용). enabled=false면 PolicyGate가 거부,
     * mode=DRY_RUN이면 기록만, LIVE면 실주문. 지정가 기준이라 price 필수.
     */
    @PostMapping("/test-order")
    public OrderService.OrderResult testOrder(@RequestParam String stockCode,
                                              @RequestParam(defaultValue = "BUY") OrderSide side,
                                              @RequestParam(defaultValue = "1") long qty,
                                              @RequestParam long price,
                                              @RequestParam(required = false) String idempotencyKey) {
        String idem = idempotencyKey != null ? idempotencyKey
                : "MANUAL:" + side + ":" + stockCode + ":" + System.currentTimeMillis();
        return orderService.submitManual("MANUAL", stockCode, side, qty, price, idem);
    }

    /** 청산방식 비교: 시간기반 vs 익절/손절(TP/SL) 평균수익 + 추천 */
    @GetMapping("/exit-comparison")
    public List<ExitStrategyService.ExitComparison> exitComparison() {
        return exitStrategyService.compare();
    }

    /** 트레일링 되돌림%(1~10) 격자별 전략 평균 net·승률 — 최선만이 아닌 전 %. */
    @GetMapping("/trailing-grid")
    public List<ExitStrategyService.StrategyTrailGrid> trailingGrid() {
        return exitStrategyService.trailingGrid();
    }

    /**
     * 멀티데이(2-3주) 일봉 종가 경로 수집 현황 (2026-08-07, Phase 1 — 측정 검증용).
     * strategy 지정 시 outcome별 D+N 종가 경로(수익률%)까지, 미지정이면 전략별 요약(행수·outcome수·최대 D+N).
     */
    @GetMapping("/multiday-marks")
    public Object multidayMarks(@org.springframework.web.bind.annotation.RequestParam(required = false) String strategy) {
        if (dailyMarkRepository == null) return java.util.Map.of("error", "repository unavailable");
        String[] targets = (strategy != null && !strategy.isBlank())
                ? new String[]{strategy}
                : new String[]{"MEAN_REVERSION_C", "INDEX_RELATIVE_D", "VALUE_REVERSAL_J"};
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (String s : targets) {
            List<com.stockadvisor.domain.OutcomeDailyMark> marks = dailyMarkRepository.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(s);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("strategy", s);
            row.put("rows", marks.size());
            row.put("outcomes", marks.stream().map(com.stockadvisor.domain.OutcomeDailyMark::getOutcomeId).distinct().count());
            row.put("maxMarkDays", marks.stream().mapToInt(com.stockadvisor.domain.OutcomeDailyMark::getMarkDays).max().orElse(-1));
            if (strategy != null && !strategy.isBlank()) {
                row.put("marks", marks.stream().map(m -> java.util.Map.of(
                        "outcomeId", m.getOutcomeId(), "d", m.getMarkDays(),
                        "date", m.getBusinessDate(), "close", m.getClosePrice(),
                        "retPct", Math.round(m.returnPct() * 100) / 100.0)).toList());
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 전략 성과 게이트 상태 — A/B/C 전략별 최근 net 평균수익률·표본수와 LIVE 진입 허용 여부.
     * (실주문이 자동으로 켜져 있는지/꺼져 있는지, 왜 그런지 확인용. DRY_RUN/관찰엔 영향 없음.)
     */
    @GetMapping("/strategy-gate")
    public List<StrategyPerformanceGate.GateDecision> strategyGate() {
        return strategyPerformanceGate.evaluateAll();
    }

    /** 국면 가정 게이트 — 전략×시장×국면(강세/중립/약세) 매수대기 시뮬("이 국면이면 진입 대기인가"). */
    @GetMapping("/strategy-gate-by-regime")
    public List<StrategyPerformanceGate.GateDecision> strategyGateByRegime() {
        return strategyPerformanceGate.evaluateByRegime();
    }

    /**
     * 시장 국면 (레이어 1) — KOSPI/KOSDAQ 추세(강세/중립/약세)·변동성(저/중/고)과 근거 지표
     * (종가·MA·MA기울기·실현변동성). 이후 국면조건부 전략 배분·리스크의 상위 입력.
     */
    @GetMapping("/market-regime")
    public List<MarketRegimeService.MarketRegime> marketRegime() {
        return marketRegimeService.all();
    }

    /**
     * 리스크 상태 (레이어 3) — 현재 국면 기준 총노출 상한·현 노출액·서킷브레이커 발동 여부.
     * 약세/고변동이면 상한↓, 지수 급락 시 riskOff=true(신규진입 중단·청산 가속).
     */
    @GetMapping("/risk-status")
    public MarketRiskGuard.RiskStatus riskStatus() {
        return marketRiskGuard.status();
    }

    /**
     * ATR 사이징 미리보기 (레이어 3.1, 튜닝용) — 종목·가격·순자산을 주면 산출 ATR과 매수수량
     * (ATR 기반 vs 1주문 상한 천장)을 비교해 보여준다. 실제 진입과 동일 계산.
     */
    @GetMapping("/sizing")
    public PositionSizer.Sizing sizingPreview(@RequestParam String stockCode,
                                              @RequestParam long price,
                                              @RequestParam long netAssets) {
        return positionSizer.size(stockCode, price, netAssets);
    }

    /**
     * 체결비용 미리보기 (레이어 4, 튜닝용) — 가격·거래대금을 주면 호가단위·추정 왕복 스프레드/슬리피지·
     * 거래가능 여부를 보여준다. net 분석에 가산되는 슬리피지와 유동성 필터의 근거.
     */
    @GetMapping("/execution-cost")
    public ExecutionCostModel.CostBreakdown executionCost(@RequestParam long price,
                                                          @RequestParam(defaultValue = "0") long turnoverKrw) {
        return executionCostModel.breakdown(price, turnoverKrw);
    }

    /**
     * 실측 호가 스프레드 (레이어 4.1 검증용) — 종목의 최우선 매도/매수호가와 그로부터 산출한 왕복 슬리피지(%).
     * 호가 미가용이면 spread=null(진입 시 tick 추정으로 fallback). 호가창 API 동작 확인용.
     */
    /** 종목 체결강도 조회(FHKST01010300) — 진입 체결강도 태깅 검증용. */
    @GetMapping("/ccnl")
    public Map<String, Object> ccnl(@RequestParam String stockCode) {
        var r = kisApiClient.fetchCcnl(stockCode);
        return Map.of("stockCode", stockCode, "latestStrength",
                r.latestStrength() == null ? "N/A" : r.latestStrength(),
                "ticks", r.output() == null ? 0 : r.output().size());
    }

    /**
     * ⚠️ <b>검증 전용(2026-08-22)</b> — 외국인·기관 수급 API 실응답 확인. 조회만 하고 태깅·매매엔 일절 관여하지 않는다.
     *
     * <p>두 후보를 <b>동시에</b> 찔러 비교한다: ① 종목별 일별 투자자매매동향(후보당 1콜)
     * ② 시장 전체 가집계(스캔당 1콜, 단 커버리지 미지수). 어느 쪽 응답이 쓸 만한지가 태깅 설계를 결정한다.</p>
     *
     * <p>⚠️ 한쪽 실패가 다른 쪽 결과를 가리지 않게 <b>개별 격리</b>한다 — TR ID·파라미터가 미검증이라
     * 둘 중 하나만 통할 가능성이 실제로 있다(그 판정이 이 엔드포인트의 목적이다).</p>
     */
    @GetMapping("/investor-probe")
    public Map<String, Object> investorProbe(@RequestParam(required = false) String stockCode,
                                             @RequestParam(defaultValue = "0000") String market) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (stockCode != null && !stockCode.isBlank()) {
            try {
                out.put("종목별_일별", kisApiClient.probeInvestorDaily(stockCode));
            } catch (Exception e) {
                out.put("종목별_일별_오류", String.valueOf(e.getMessage()));
            }
        }
        try {
            out.put("시장전체_가집계", kisApiClient.probeForeignInstitutionTotal(market, "0"));
        } catch (Exception e) {
            out.put("시장전체_가집계_오류", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /**
     * ⚠️ <b>검증 전용(2026-08-22)</b> — 뉴스 <b>날짜 페이징</b> 가능 여부 확인. 조회만 한다.
     *
     * <p>과거 날짜를 지정해 그 시점 뉴스가 오면 소급 태깅이 가능해지고, 대조군 커버리지 0%(=뉴스 축의
     * {@code edgeVsControlPct}가 구조적 null)라는 8/21 미판정이 해소된다. 무시되고 최신만 오면 소급은 불가.</p>
     */
    @GetMapping("/news-probe")
    public Map<String, Object> newsProbe(@RequestParam String stockCode,
                                         @RequestParam(required = false) String date,
                                         @RequestParam(required = false) String hour,
                                         @RequestParam(required = false) String srno) {
        return kisApiClient.probeNews(stockCode, date, hour, srno);
    }

    /** 종목 뉴스/공시 제목 조회(FHKST01011800) — 진입 뉴스 태깅 검증용. */
    @GetMapping("/news")
    public Object news(@RequestParam String stockCode) {
        return kisApiClient.fetchNewsTitles(stockCode);
    }

    /** 실측 호가 — 스프레드/슬리피지 + 호가 불균형(진입 태깅 entry_obi1/obi5와 같은 계산) 검증용. */
    @GetMapping("/spread")
    public Map<String, Object> spread(@RequestParam String stockCode) {
        KisApiClient.OrderBook ob = kisApiClient.fetchOrderBook(stockCode);
        KisApiClient.Spread sp = ob == null ? null : ob.spread();
        if (sp == null) {
            return Map.of("stockCode", stockCode, "available", false);
        }
        Double slip = executionCostModel.roundTripSlippagePctFromSpread(sp.bestAsk(), sp.bestBid());
        Double obi1 = ob.imbalancePct(1);
        Double obi5 = ob.imbalancePct(5);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("stockCode", stockCode);
        out.put("available", true);
        out.put("bestAsk", sp.bestAsk());
        out.put("bestBid", sp.bestBid());
        out.put("roundTripSlippagePct", slip == null ? "n/a" : slip);
        out.put("obi1Pct", obi1 == null ? "n/a" : obi1);       // + = 매수 대기물량 우위
        out.put("obi5Pct", obi5 == null ? "n/a" : obi5);
        out.put("askLevels", ob.asks().size());
        out.put("bidLevels", ob.bids().size());
        return out;
    }

    /**
     * 적응형 청산 보유시간 — A/B/C 전략별로 현재 적용 중인 보유시간(분)과 그 출처(자동/고정).
     * 자동이면 exit-timing 권장 마크의 표본수·평균 net 수익도 함께. (실주문·DRY_RUN 청산에 그대로 적용)
     */
    /** 스윙 트레일링 검증 — 익일보유 vs 트레일 3/5/7% net 비교(스윙 전략). */
    @GetMapping("/swing-trail-analysis")
    public List<SwingTrailAnalysisService.StrategySwingTrail> swingTrailAnalysis() {
        return swingTrailAnalysisService.analyze();
    }

    /** 스윙 청산 채택 현황 — 검증돼 트레일 채택된 전략 vs 익일보유(fail-closed). */
    @GetMapping("/swing-exit")
    public List<SwingExitProvider.ExitChoice> swingExit() {
        return swingExitProvider.describe();
    }

    /** 시장 폭 — 직전 스캔 기준 전체/시장별 상승종목 비율·중앙 등락률(참여 넓이). */
    @GetMapping("/market-breadth")
    public List<MarketBreadthService.Breadth> marketBreadth() {
        return marketBreadthService.describe();
    }

    /**
     * 유니버스 스냅샷 수집 현황(Phase 1) — 일자·버킷별 행 수 + 사후 타깃 충족률.
     * 분석(lift 테이블)은 {@code /universe-analysis}.
     */
    @GetMapping("/universe-snapshot-status")
    public Map<String, Object> universeSnapshotStatus() {
        return Map.of("config", universeSnapshotService.config(),
                "collected", universeSnapshotService.describe());
    }

    /**
     * 유니버스 횡단면 분석(Phase 2) — <b>P(승자|feature)</b>를 전체 base rate 대비 lift로.
     *
     * <p>다른 분석 엔드포인트는 전부 "거래량 급증 모집단" 안에서만 표본을 뽑으므로 스크리닝 자체를 검증할 수 없다.
     * 이건 워치리스트 전 종목 스냅샷이 분모라 <b>급증 밖</b>까지 본다 — 신규 전략 발굴의 유일한 비편향 소스.</p>
     *
     * @param horizon m90(+90분) / close(당일종가, 기본) / nextClose(익일종가)
     * @param since   snapDate 하한(yyyyMMdd) — 시간분할 검증용. 종가는 20260819부터 확정 종가라 그 이전은 편향
     * @param until   snapDate 상한(yyyyMMdd)
     */
    @GetMapping("/universe-analysis")
    public Object universeAnalysis(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int lookbackDays,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "close") String horizon,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String market,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String snapTime,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int minSamples,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String since,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String until) {
        if (universeAnalysisService == null) return Map.of("error", "service unavailable");
        return universeAnalysisService.analyze(lookbackDays, horizon, market, snapTime, minSamples, since, until);
    }

    /** 전략별 적응형 손절선 — 승자 MAE 기반 채택값 vs 고정 −7%(fail-closed). */
    @GetMapping("/exit-stop")
    public List<StrategyStopProvider.StopInfo> exitStop() {
        return strategyStopProvider.describe();
    }

    @GetMapping("/exit-hold")
    public List<StrategyHoldTimeProvider.HoldInfo> exitHold() {
        return strategyHoldTimeProvider.describe();
    }

    /** 업종 밸류에이션 중앙값(업종 상대평가 근거) — 업종별 PER/PBR 중앙값·종목수. */
    @GetMapping("/sector-valuation")
    public List<SectorValuationService.SectorStat> sectorValuation() {
        return sectorValuationService.describe();
    }

    /** 업종 중앙값 즉시 재계산 (전 종목 순회 — 무거움, 장전/수동용). @return 산출 업종 수 */
    @PostMapping("/refresh-sector-valuation")
    public Map<String, Object> refreshSectorValuation() {
        return Map.of("sectors", sectorValuationService.refresh());
    }

    /** 적응형 청산방식 — A/B/C 전략별 현재 채택 청산방식(시간기반/트레일링/VWAP이탈/추세전환)·근거 표본·평균수익. */
    @GetMapping("/exit-method")
    public List<ExitStrategyService.BestExit> exitMethod() {
        return exitMethodProvider.describe();
    }

    /** 당일 청산시점 분석: 전략별 보유시간별 평균수익률·승률 + 권장 청산시점 */
    @GetMapping("/exit-timing")
    public List<ExitTimingService.StrategyExitTiming> exitTiming() {
        return exitTimingService.analyze();
    }

    /** 일별 성과 리포트 즉시 발송 (테스트용) */
    @PostMapping("/daily-report")
    public Map<String, Object> dailyReport() {
        String msg = dailyReportService.sendDailyReport();
        return Map.of("sent", true, "message", msg);
    }

    /** 장 시작 알림 즉시 발송 (시초 국면 + 전략 태세, 테스트용). 조립된 메시지를 함께 반환. */
    @PostMapping("/market-open-alert")
    public Map<String, Object> marketOpenAlert() {
        String msg = marketOpenNotifier.buildMessage();
        marketOpenNotifier.notifyMarketOpen();
        return Map.of("sent", true, "message", msg);
    }

    /** 게이트 통과 집합 테스트 알림 즉시 발송 (현재 진입 가능 전략, baseline 미갱신). */
    @PostMapping("/gate-change-test")
    public Map<String, Object> gateChangeTest() {
        String msg = gateChangeNotifier.sendTestAlert();
        return Map.of("sent", true, "message", msg);
    }

    /** 진입 알림 포맷 테스트 발송 (현재가 + 주문 상태/미매수 사유 시연). */
    @PostMapping("/signal-alert-test")
    public Map<String, Object> signalAlertTest() {
        String msg = strategyEvaluator.sendTestAlert();
        return Map.of("sent", true, "message", msg);
    }

    /** 승자/패자 진입 feature 분석 (horizon: close/nextClose/p10/p30) */
    @GetMapping("/outcome-analysis")
    public List<OutcomeAnalysisService.StrategyAnalysis> outcomeAnalysis(
            @RequestParam(defaultValue = "close") String horizon) {
        return outcomeAnalysisService.analyze(horizon);
    }

    /**
     * 대조군 분석 — 진입 vs 미진입(탈락 사유별) net 성과 비교(필터 검증/개선용).
     * 미진입이 진입보다 수익 좋은 사유가 있으면 hint로 "필터 완화 검토" 표시. (horizon: close/nextClose/d2/d3/p10/p30)
     */
    @GetMapping("/control-analysis")
    public List<ControlAnalysisService.StrategyControl> controlAnalysis(
            @RequestParam(defaultValue = "close") String horizon) {
        return controlAnalysisService.analyze(horizon);
    }

    /** 자동 진단 — 전략별 올바른 horizon(스윙=nextClose)으로 손실 원인 분류·정렬(손실전략 우선). */
    @GetMapping("/control-diagnosis")
    public List<ControlAnalysisService.Diagnosis> controlDiagnosis() {
        return controlAnalysisService.diagnose();
    }

    /**
     * 집행품질 — 실제 LIVE 매매 실현손익(trade_order) vs 같은 신호 섀도우 성과(trade_outcome) 대조.
     * 진입 슬리피지·청산 타이밍·집행 버그로 인한 "전략은 +인데 실집행은 −" 괴리를 정량화(전략별 realNet/shadowNet/gap).
     */
    @GetMapping("/execution-quality")
    public List<ExecutionQualityService.StrategyExecQuality> executionQuality() {
        return executionQualityService.analyze();
    }

    /**
     * 주문 추격(취소→재주문) 비용 — 미체결 타임아웃 후 현재가로 재주문하며 호가를 따라가는 비용을 측정.
     *
     * <p>{@code adverseDriftPct}는 <b>음수가 불리</b>(매도=더 싸게 팔림 / 매수=더 비싸게 삼).
     * 기존 {@code trade_order}만으로 소급 계산되므로 신규 태깅이 없다 — 같은 멱등키를 공유하는 행이 곧 한 체인.</p>
     *
     * @param since yyyyMMdd(포함). 생략하면 전체 기간.
     */
    @GetMapping("/order-chase")
    public List<OrderChaseAnalysisService.Summary> orderChase(@RequestParam(required = false) String since) {
        return orderChaseAnalysisService.analyze(since);
    }

    /**
     * C(눌림목) 일봉 백테스트 — 전 종목 ~30거래일에서 C 핵심조건 진입의 D+1/D+2/D+3 종가 net 수익 집계.
     * 전 종목 순회라 무거움(~워치리스트 콜, 수 분). limit 으로 표본 줄여 빠르게 테스트 가능. ⚠️ 일봉 근사·1국면.
     */
    @PostMapping("/backtest-c")
    public BacktestService.Backtest backtestC(@RequestParam(required = false) Integer limit) {
        return backtestService.meanReversion(limit);
    }

    /** B(거래량 선행) 일봉 백테스트 — 횡보+거래량 진입의 D+1~3 종가 net. ⚠️ B는 인트라데이성이라 일봉 근사 한계 큼. */
    @PostMapping("/backtest-b")
    public BacktestService.Backtest backtestB(@RequestParam(required = false) Integer limit) {
        return backtestService.volumeLeading(limit);
    }

    /** 워치리스트 시장전략(B/C) 스캔 즉시 실행. limit 으로 테스트 가능. */
    @PostMapping("/scan-market")
    public Map<String, Object> scanMarket(@RequestParam(required = false) Integer limit) {
        int alerts = watchlistScanService.scan(limit);
        return Map.of("alertsSent", alerts);
    }

    /** 전략별 가상매수 성과 비교 리포트 */
    @GetMapping("/strategy-report")
    public List<StrategyReportService.StrategyPerformance> strategyReport() {
        return strategyReportService.report();
    }

    /** 매수가정 후속 추적 즉시 실행 (+5분/+10분 샘플 조건 충족분 처리) */
    @PostMapping("/track-followups")
    public Map<String, Object> trackFollowUps() {
        int sent = tradeFollowUpService.processFollowUps();
        return Map.of("followUpsSent", sent);
    }

    /**
     * 워치리스트 동기화 (코스피/코스닥 시총 상위 적재).
     * 전 종목 순회라 수 분 소요될 수 있음. limit 으로 테스트 가능.
     */
    @PostMapping("/sync-watchlist")
    public WatchlistSyncService.SyncResult syncWatchlist(
            @RequestParam(defaultValue = "1000") int kospiTop,
            @RequestParam(defaultValue = "500") int kosdaqTop,
            @RequestParam(required = false) Integer limit) {
        return watchlistSyncService.sync(kospiTop, kosdaqTop, limit);
    }

    /** 공시 폴링 즉시 실행 */
    @PostMapping("/poll-disclosures")
    public Map<String, Object> pollDisclosures() {
        int stored = pollingService.pollAndStore();
        return Map.of("storedNew", stored);
    }

    /** 신호 평가 + 알림 즉시 실행 */
    @PostMapping("/scan-signals")
    public Map<String, Object> scanSignals() {
        int alerted = signalAlertService.scanAndAlert();
        return Map.of("alertsSent", alerted);
    }

    /** 특정 종목의 현재 신호 상태만 조회(알림 없이) */
    @GetMapping("/signal/{stockCode}")
    public Object signal(@PathVariable String stockCode) {
        Optional<SignalResult> result = marketSignalService.evaluate(stockCode);
        return result.<Object>map(r -> r).orElseGet(() -> Map.of("message", "데이터 부족"));
    }
}
