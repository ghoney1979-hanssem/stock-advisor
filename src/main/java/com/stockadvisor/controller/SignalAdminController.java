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
import com.stockadvisor.service.BacktestService;
import com.stockadvisor.service.RegimeBacktagService;
import com.stockadvisor.service.FlowBacktagService;
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
    private final BacktestService backtestService;
    private final RegimeBacktagService regimeBacktagService;
    private final FlowBacktagService flowBacktagService;
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
                                 BacktestService backtestService,
                                 RegimeBacktagService regimeBacktagService,
                                 FlowBacktagService flowBacktagService,
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
        this.backtestService = backtestService;
        this.regimeBacktagService = regimeBacktagService;
        this.flowBacktagService = flowBacktagService;
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

    /** 장중흐름(mom30/60) 소급 태깅 — 저장된 entry_market_change로 각 날 지수경로 재구성·보간(근사, mom10 제외). */
    @PostMapping("/backfill-flow-tags")
    public FlowBacktagService.BacktagResult backfillFlowTags() {
        return flowBacktagService.backfill();
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

    /** 종목 뉴스/공시 제목 조회(FHKST01011800) — 진입 뉴스 태깅 검증용. */
    @GetMapping("/news")
    public Object news(@RequestParam String stockCode) {
        return kisApiClient.fetchNewsTitles(stockCode);
    }

    @GetMapping("/spread")
    public Map<String, Object> spread(@RequestParam String stockCode) {
        KisApiClient.Spread sp = kisApiClient.fetchAskingPrice(stockCode);
        if (sp == null) {
            return Map.of("stockCode", stockCode, "available", false);
        }
        Double slip = executionCostModel.roundTripSlippagePctFromSpread(sp.bestAsk(), sp.bestBid());
        return Map.of("stockCode", stockCode, "available", true,
                "bestAsk", sp.bestAsk(), "bestBid", sp.bestBid(),
                "roundTripSlippagePct", slip == null ? "n/a" : slip);
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
