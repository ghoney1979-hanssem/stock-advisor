package com.stockadvisor.service;

import com.stockadvisor.config.properties.StrategyPerformanceProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 성과 게이트: 표본 부족이면 차단(fail-closed), net 평균이 기준 미달이면 차단, 충족이면 통과.
 * net = (priceClose - buyPrice)/buyPrice*100 - roundTripCost. 레이어2: 국면조건부 표본 필터링.
 */
class StrategyPerformanceGateTest {

    private static final double COST = 0.18;

    // 국면 무관(regimeConditional=false) — 순수 net평균 로직 검증용. fallback off(기존 fail-closed).
    private StrategyPerformanceProperties props(boolean enabled, int minSamples, double minNetAvg) {
        return new StrategyPerformanceProperties(enabled, 20, minSamples, minNetAvg, "close", false, false,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
    }

    // 국면조건부(trend만, 시장 분리 off) — 기존 레이어2 검증
    private StrategyPerformanceProperties regimeProps(int minSamples) {
        return new StrategyPerformanceProperties(true, 20, minSamples, 0.0, "close", true, false,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
    }

    // (market,trend) 2차원 분리 on
    private StrategyPerformanceProperties marketSplitProps(int minSamples) {
        return new StrategyPerformanceProperties(true, 20, minSamples, 0.0, "close", true, true,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
    }

    // 국면조건부 + 보수적 fallback on(엄격바) — fallback 경로 검증용
    private StrategyPerformanceProperties fallbackProps(int minSamples, int fbMinSamples, double fbMinNet) {
        return new StrategyPerformanceProperties(true, 20, minSamples, 0.0, "close", true, false,
                true, fbMinSamples, fbMinNet, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
    }

    /** buyPrice 대비 closeReturnPct% 오른(또는 내린) 종가의 가상매수 표본 n건. trend 태깅 가능. */
    private List<TradeOutcome> samples(String strategy, int n, double closeReturnPct, String trend) {
        return samples(strategy, n, closeReturnPct, trend, null);
    }

    /** trend + 시장(entryMarket) 태깅 표본 — 2차원 분리 검증용. */
    private List<TradeOutcome> samples(String strategy, int n, double closeReturnPct, String trend, String market) {
        long buy = 10_000;
        long close = Math.round(buy * (1 + closeReturnPct / 100.0));
        List<TradeOutcome> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TradeOutcome o = new TradeOutcome(strategy, null, "00593" + i, "20260620", buy);
            o.setPriceClose(close);
            o.setEntryMarketTrend(trend);
            o.setEntryMarket(market);
            list.add(o);
        }
        return list;
    }

    private StrategyPerformanceGate gate(StrategyPerformanceProperties p, List<TradeOutcome> rows, MarketTrend regime) {
        return gate(p, rows, regime, 0.0);   // 교차거래일 요건 off(기존 테스트는 단일일 표본이라 영향 없음)
    }

    private StrategyPerformanceGate gate(StrategyPerformanceProperties p, List<TradeOutcome> rows, MarketTrend regime,
                                         double maxSingleDaySharePct) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(regime);   // 시장별 국면 매칭 — 시장 무관 스텁
        // 슬리피지 0 스텁 — 순수 net(수수료만) 검증 유지
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        return new StrategyPerformanceGate(repo, p, regimeSvc, costModel, mock(StrategyHoldTimeProvider.class),
                mock(OutcomeSampleRepository.class), List.of(), COST, "", "nextClose", maxSingleDaySharePct, "", "", "", "");   // 스윙 없음(전일국면 전략도 없음)
    }

    // 스윙 전략 게이트(swingHorizon=nextClose)
    private StrategyPerformanceGate swingGate(StrategyPerformanceProperties p, List<TradeOutcome> rows) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(null);   // 국면 무관(전체)
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        return new StrategyPerformanceGate(repo, p, regimeSvc, costModel, mock(StrategyHoldTimeProvider.class),
                mock(OutcomeSampleRepository.class), List.of(), COST, "MEAN_REVERSION_C", "nextClose", 0.0, "", "", "", "");
    }

    /** trend + mom30 태깅 표본 — 국면+흐름 3차원 검증용. */
    private List<TradeOutcome> flowSamples(String strategy, int n, double closeReturnPct, String trend, double mom30) {
        List<TradeOutcome> list = samples(strategy, n, closeReturnPct, trend, null);
        for (TradeOutcome o : list) o.setEntryIntradayFlow(null, mom30, null);
        return list;
    }

    /** 국면 + 현재 장중 흐름(mom30)까지 스텁된 게이트. */
    private StrategyPerformanceGate flowGate(StrategyPerformanceProperties p, List<TradeOutcome> rows,
                                             MarketTrend regime, Double currentMom30) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(regime);
        when(regimeSvc.intradayFlow(any())).thenReturn(
                new MarketRegimeService.IntradayFlow(null, currentMom30, null, currentMom30 != null));
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        return new StrategyPerformanceGate(repo, p, regimeSvc, costModel, mock(StrategyHoldTimeProvider.class),
                mock(OutcomeSampleRepository.class), List.of(), COST, "", "nextClose", 0.0, "", "", "", "");
    }

    @Test
    void 흐름버킷_표본충족이면_흐름까지_반영해_차단() {
        // 국면(중립) 전체로는 플러스지만 현재 흐름(↓) 버킷은 손실 — 흐름버킷 표본 35 ≥ 30 → 흐름으로 판정, 차단.
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 35, 0.1, "NEUTRAL", -1.0));   // 흐름↓ 진입: net −0.08
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 35, 2.0, "NEUTRAL", 1.0));    // 흐름↑ 진입: net +1.82
        StrategyPerformanceGate g = flowGate(regimeProps(30), rows, MarketTrend.NEUTRAL, -0.5);   // 지금 흐름↓

        StrategyPerformanceGate.GateDecision d = g.evaluate("SQUEEZE_BREAKOUT_H", "KOSPI");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("흐름버킷 성과 미달").contains("흐름↓");
        assertThat(d.samples()).isEqualTo(35);
    }

    @Test
    void 흐름버킷_표본충족_성과충족이면_통과() {
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 35, 0.1, "NEUTRAL", -1.0));
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 35, 2.0, "NEUTRAL", 1.0));
        StrategyPerformanceGate g = flowGate(regimeProps(30), rows, MarketTrend.NEUTRAL, 0.5);   // 지금 흐름↑

        StrategyPerformanceGate.GateDecision d = g.evaluate("SQUEEZE_BREAKOUT_H", "KOSPI");
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("흐름버킷 통과").contains("흐름↑");
    }

    @Test
    void 흐름표본_부족이면_국면버킷으로_판정() {
        // 흐름↓ 버킷 10건(<30) → 흐름 레이어 생략, 국면(중립) 전체 70건으로 판정 → 통과(기존 동작 유지).
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 10, 0.1, "NEUTRAL", -1.0));
        rows.addAll(flowSamples("SQUEEZE_BREAKOUT_H", 60, 2.0, "NEUTRAL", 1.0));
        StrategyPerformanceGate g = flowGate(regimeProps(30), rows, MarketTrend.NEUTRAL, -0.5);   // 지금 흐름↓

        StrategyPerformanceGate.GateDecision d = g.evaluate("SQUEEZE_BREAKOUT_H", "KOSPI");
        assertThat(d.allowed()).isTrue();                       // 국면 버킷(avg +) 판정
        assertThat(d.reason()).doesNotContain("흐름버킷");       // 흐름 레이어 미적용
        assertThat(d.samples()).isEqualTo(70);
    }

    @Test
    void 인버스_버킷은_전용_하향_minSamples로_판정() {
        // 일반 minSamples=30이지만 INVERSE 버킷은 inverseMinSamples=10 — 표본 12건(net +)이면 통과(승격 경로).
        // 인버스 표본은 폭락일에만 쌓여 30 도달이 비현실적이라 표본만 완화(net 기준은 동일).
        StrategyPerformanceGate g = gate(marketSplitProps(30),
                samples("INVERSE_INDEX_I", 12, 2.0, null, "INVERSE"), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isTrue();
        assertThat(d.samples()).isEqualTo(12);
    }

    @Test
    void 인버스_부트스트랩_표본미달이어도_축소진입으로_허용() {
        // 표본 8 < 10이지만 부트스트랩(×0.3) — 적은 비용으로 실표본 수집. fallback=true → OrderService가 축소사이징.
        StrategyPerformanceGate g = gate(marketSplitProps(30),
                samples("INVERSE_INDEX_I", 8, 2.0, null, "INVERSE"), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isTrue();
        assertThat(d.reason()).contains("부트스트랩");
    }

    @Test
    void 인버스_부트스트랩_비활성이면_표본미달_차단() {
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 30, 0.0, "close", true, true,
                false, 50, 0.5, 0.5, 10, 0, true, 30, "", 0, 999.0);   // bootstrap mult 0 = 비활성
        StrategyPerformanceGate g = gate(p, samples("INVERSE_INDEX_I", 8, 2.0, null, "INVERSE"), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("/10");   // 임계가 30이 아닌 10으로 표기
    }

    @Test
    void 인버스_표본충족_후_성과미달이면_부트스트랩과_무관하게_차단() {
        // 졸업 경계: 표본 12 ≥ 10인데 net −0.08% < 0 → 엄격 경로 차단. 부트스트랩이 성과 미달을 우회하지 못함.
        StrategyPerformanceGate g = gate(marketSplitProps(30),
                samples("INVERSE_INDEX_I", 12, 0.1, null, "INVERSE"), null);   // net = 0.1 − 0.18 < 0

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("성과 미달");
    }

    /** 청산완료 인버스 LIVE 주문 목(mock) — realized_pnl은 net 기록. */
    private com.stockadvisor.domain.Order inverseOrder(String strategy, String date, long buyPrice, long qty, long pnl) {
        var o = mock(com.stockadvisor.domain.Order.class);
        when(o.getStrategy()).thenReturn(strategy);
        when(o.getMarket()).thenReturn("INVERSE");
        when(o.getOrderDate()).thenReturn(date);
        when(o.getRealizedPnl()).thenReturn(pnl);
        when(o.getAvgFillPrice()).thenReturn(buyPrice);
        when(o.getFilledQty()).thenReturn(qty);
        return o;
    }

    @Test
    void 인버스_실현손익으로_채점_표본충족시_통과() {
        // 10건 × (+2,000/200,000 = +1.0%) — 실현 net 평균 +1.0% ≥ 0.3 → 정식 통과(정상 사이징)
        StrategyPerformanceGate g = gate(marketSplitProps(30), List.of(), null);
        var orderRepo = mock(com.stockadvisor.repository.OrderRepository.class);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        var orders = new java.util.ArrayList<com.stockadvisor.domain.Order>();
        for (int i = 0; i < 10; i++) orders.add(inverseOrder("INVERSE_INDEX_I", today, 2_000, 100, 2_000));
        when(orderRepo.findByModeAndSideAndClosed(com.stockadvisor.domain.TradingMode.LIVE,
                com.stockadvisor.domain.OrderSide.BUY, true)).thenReturn(orders);
        g.setOrderRepository(orderRepo);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isFalse();   // 부트스트랩 졸업 — 정상 사이징
        assertThat(d.reason()).contains("실현손익").contains("통과");
        assertThat(d.netAvgReturnPct()).isEqualTo(1.0);
    }

    @Test
    void 인버스_실현손익_성과미달이면_졸업시점에_차단() {
        // 10건 × (−2,000/200,000 = −1.0%) — 표본은 찼지만 net 미달 → 부트스트랩으로 우회 불가, 차단
        StrategyPerformanceGate g = gate(marketSplitProps(30), List.of(), null);
        var orderRepo = mock(com.stockadvisor.repository.OrderRepository.class);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        var orders = new java.util.ArrayList<com.stockadvisor.domain.Order>();
        for (int i = 0; i < 10; i++) orders.add(inverseOrder("INVERSE_INDEX_I", today, 2_000, 100, -2_000));
        when(orderRepo.findByModeAndSideAndClosed(com.stockadvisor.domain.TradingMode.LIVE,
                com.stockadvisor.domain.OrderSide.BUY, true)).thenReturn(orders);
        g.setOrderRepository(orderRepo);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("성과 미달");
    }

    @Test
    void 인버스_실현표본_부족이면_부트스트랩_유지() {
        StrategyPerformanceGate g = gate(marketSplitProps(30), List.of(), null);
        var orderRepo = mock(com.stockadvisor.repository.OrderRepository.class);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        var one = inverseOrder("INVERSE_INDEX_I", today, 2_000, 100, -500);   // 중첩 스터빙 회피 — when() 밖에서 생성
        when(orderRepo.findByModeAndSideAndClosed(com.stockadvisor.domain.TradingMode.LIVE,
                com.stockadvisor.domain.OrderSide.BUY, true))
                .thenReturn(List.of(one));   // 1건뿐
        g.setOrderRepository(orderRepo);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isTrue();   // 축소진입 지속
        assertThat(d.reason()).contains("실현표본 1/10");
    }

    @Test
    void 신로직_시작일_이전_실현표본은_채점_제외되어_부트스트랩_재개() {
        // 로직 세대 교체(2026-07-20): 구로직(7/16 휩쏘)이 만든 실현 11표본이 개선판을 영구 차단하는
        // 캐치22 해소 — inverse-realized-since 이후 표본만 채점 → 구표본 전부 제외되면 0/10 부트스트랩 재개.
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 30, 0.0, "close", true, true,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "20260717", 0, 999.0);
        StrategyPerformanceGate g = gate(p, List.of(), null);
        var orderRepo = mock(com.stockadvisor.repository.OrderRepository.class);
        var orders = new java.util.ArrayList<com.stockadvisor.domain.Order>();
        for (int i = 0; i < 11; i++) orders.add(inverseOrder("INVERSE_INDEX_I", "20260716", 2_000, 100, -2_000));   // 구로직 손실 표본
        when(orderRepo.findByModeAndSideAndClosed(com.stockadvisor.domain.TradingMode.LIVE,
                com.stockadvisor.domain.OrderSide.BUY, true)).thenReturn(orders);
        g.setOrderRepository(orderRepo);

        StrategyPerformanceGate.GateDecision d = g.evaluate("INVERSE_INDEX_I", "INVERSE");
        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isTrue();                        // 축소진입(×0.3) 부트스트랩 재개
        assertThat(d.reason()).contains("실현표본 0/10");          // 구표본 11건 전부 채점 제외
    }

    @Test
    void 표본_부족이면_차단() {
        StrategyPerformanceGate g = gate(props(true, 20, 0.0), samples("MOMENTUM_A", 15, 2.0, null), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MOMENTUM_A");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("표본 부족");
        assertThat(d.samples()).isEqualTo(15);
    }

    @Test
    void net_평균이_기준미달이면_차단() {
        // 종가수익 +0.1% → net = 0.1 - 0.18 = -0.08% < 0 → 차단
        StrategyPerformanceGate g = gate(props(true, 20, 0.0), samples("VOLUME_LEADING_B", 30, 0.1, null), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("성과 미달");
        assertThat(d.netAvgReturnPct()).isNegative();
    }

    @Test
    void net_평균이_기준이상이면_통과() {
        // 종가수익 +1.0% → net = 1.0 - 0.18 = 0.82% ≥ 0 → 통과
        StrategyPerformanceGate g = gate(props(true, 20, 0.0), samples("MEAN_REVERSION_C", 30, 1.0, null), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MEAN_REVERSION_C");

        assertThat(d.allowed()).isTrue();
        assertThat(d.netAvgReturnPct()).isEqualTo(0.82);
        assertThat(d.samples()).isEqualTo(30);
    }

    @Test
    void 게이트_비활성이면_표본부족이어도_통과() {
        StrategyPerformanceGate g = gate(props(false, 20, 0.0), samples("MOMENTUM_A", 3, -5.0, null), null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MOMENTUM_A");

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("비활성");
    }

    @Test
    void 종가_미수집_표본은_집계제외() {
        List<TradeOutcome> rows = samples("MEAN_REVERSION_C", 25, 1.0, null);
        for (int i = 0; i < 10; i++) {
            rows.add(new TradeOutcome("MEAN_REVERSION_C", null, "0999" + i, "20260620", 10_000)); // priceClose=null
        }
        StrategyPerformanceGate g = gate(props(true, 20, 0.0), rows, null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MEAN_REVERSION_C");

        assertThat(d.samples()).isEqualTo(25);
        assertThat(d.allowed()).isTrue();
    }

    // ───── 레이어 2: 국면조건부 ─────

    @Test
    void 국면조건부_현재국면과_같은_표본만_집계() {
        // 현재 강세장. 강세장 진입 25건(+1.0%) + 약세장 진입 30건(+1.0%) 섞여 있어도
        // 강세장 25건만 집계 → 표본 25, 통과. (약세 30건은 제외)
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("VOLUME_LEADING_B", 25, 1.0, "BULL"));
        rows.addAll(samples("VOLUME_LEADING_B", 30, 1.0, "BEAR"));
        StrategyPerformanceGate g = gate(regimeProps(20), rows, MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B");

        assertThat(d.regimeTrend()).isEqualTo("BULL");
        assertThat(d.samples()).isEqualTo(25);
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("강세");
    }

    @Test
    void 국면조건부_현재국면_표본부족이면_차단() {
        // 현재 약세장인데 약세장 진입 표본은 5건뿐(나머지는 강세장) → 표본부족 차단
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("MEAN_REVERSION_C", 5, 1.0, "BEAR"));
        rows.addAll(samples("MEAN_REVERSION_C", 40, 1.0, "BULL"));
        StrategyPerformanceGate g = gate(regimeProps(20), rows, MarketTrend.BEAR);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MEAN_REVERSION_C");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("표본 부족");
        assertThat(d.samples()).isEqualTo(5);
    }

    @Test
    void 시장분리_같은시장_같은국면_표본만_집계() {
        // 현재 강세. KOSPI-강세 25건 + KOSDAQ-강세 30건. KOSPI 후보 평가 → KOSPI-강세 25건만
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("VOLUME_LEADING_B", 25, 1.0, "BULL", "KOSPI"));
        rows.addAll(samples("VOLUME_LEADING_B", 30, 1.0, "BULL", "KOSDAQ"));
        StrategyPerformanceGate g = gate(marketSplitProps(20), rows, MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B", "KOSPI");

        assertThat(d.market()).isEqualTo("KOSPI");
        assertThat(d.samples()).isEqualTo(25);          // KOSDAQ 30건 제외
        assertThat(d.reason()).contains("KOSPI·강세");
    }

    @Test
    void 시장분리_다른시장_표본부족이면_차단() {
        // KOSDAQ-강세는 5건뿐 → KOSDAQ 후보는 표본부족 차단(KOSPI 40건은 무관)
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("MEAN_REVERSION_C", 40, 1.0, "BULL", "KOSPI"));
        rows.addAll(samples("MEAN_REVERSION_C", 5, 1.0, "BULL", "KOSDAQ"));
        StrategyPerformanceGate g = gate(marketSplitProps(20), rows, MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MEAN_REVERSION_C", "KOSDAQ");

        assertThat(d.allowed()).isFalse();
        assertThat(d.samples()).isEqualTo(5);
        assertThat(d.reason()).contains("표본 부족");
    }

    @Test
    void 스윙전략은_익일종가_horizon으로_게이트() {
        // 당일종가는 −2%(close면 차단), 익일종가는 +2%. 스윙 게이트는 익일종가로 평가 → 통과해야 함.
        long buy = 10_000;
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            TradeOutcome o = new TradeOutcome("MEAN_REVERSION_C", null, "0059" + i, "20260620", buy);
            o.setPriceClose(9_800);        // 당일종가 −2%
            o.setPriceNextClose(10_200);   // 익일종가 +2%
            rows.add(o);
        }
        StrategyPerformanceGate g = swingGate(props(true, 20, 0.0), rows);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MEAN_REVERSION_C");

        assertThat(d.samples()).isEqualTo(25);
        assertThat(d.netAvgReturnPct()).isEqualTo(1.82);   // 익일 +2% − 0.18 비용 (close였다면 −2.18로 차단)
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("nextClose");
    }

    @Test
    void 국면_미산출이면_국면무관_전체표본으로_fallback() {
        // overallTrend=null(데이터 부족) → 국면 필터 없이 전체 55건 집계
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("MOMENTUM_A", 25, 1.0, "BULL"));
        rows.addAll(samples("MOMENTUM_A", 30, 1.0, "BEAR"));
        StrategyPerformanceGate g = gate(regimeProps(20), rows, null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("MOMENTUM_A");

        assertThat(d.regimeTrend()).isNull();
        assertThat(d.samples()).isEqualTo(55);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void exit_horizon은_당일종가가_아니라_권장청산마크_실제가로_측정() {
        // 인트라데이 전략을 "exit" horizon으로 검증: 권장 보유 60분의 OutcomeSample 가격(+1%)으로 net.
        // (종가는 안 씀 — 마크 미수집 표본은 제외되어 net 계산에 안 들어감.)
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        List<TradeOutcome> rows = new ArrayList<>();
        List<OutcomeSample> samples = new ArrayList<>();
        for (long i = 1; i <= 25; i++) {
            TradeOutcome o = mock(TradeOutcome.class);
            when(o.getId()).thenReturn(i);
            when(o.getBuyPrice()).thenReturn(10_000L);
            rows.add(o);
            samples.add(new OutcomeSample(i, "VOLUME_LEADING_B", 10_000, 60, 10_100)); // 60분 가격 +1%
        }
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(null);
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
        when(hold.holdMinutes("VOLUME_LEADING_B")).thenReturn(60);
        OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
        when(sampleRepo.findByStrategyAndMarkMinutesBetween("VOLUME_LEADING_B", 30, 90)).thenReturn(samples);
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 20, 0.0, "exit", false, false,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
        StrategyPerformanceGate g = new StrategyPerformanceGate(repo, p, regimeSvc, costModel, hold, sampleRepo,
                List.of(), COST, "", "nextClose", 0.0, "", "", "", "");

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B");

        assertThat(d.samples()).isEqualTo(25);
        assertThat(d.netAvgReturnPct()).isEqualTo(0.82);   // 60분 +1% − 0.18 비용
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("60분");
    }

    @Test
    void exit_정확마크_없어도_근접마크로_대체집계() {
        // 표본 기근 보정: 권장 60분인데 표본이 90분 마크(코스마크)만 가진 경우, ±30 근접 대체로 집계돼야 함.
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        List<TradeOutcome> rows = new ArrayList<>();
        List<OutcomeSample> samples = new ArrayList<>();
        for (long i = 1; i <= 25; i++) {
            TradeOutcome o = mock(TradeOutcome.class);
            when(o.getId()).thenReturn(i);
            when(o.getBuyPrice()).thenReturn(10_000L);
            rows.add(o);
            samples.add(new OutcomeSample(i, "VOLUME_LEADING_B", 10_000, 90, 10_100)); // 90분(근접) +1%
        }
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(null);
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
        when(hold.holdMinutes("VOLUME_LEADING_B")).thenReturn(60);
        OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
        when(sampleRepo.findByStrategyAndMarkMinutesBetween("VOLUME_LEADING_B", 30, 90)).thenReturn(samples);
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 20, 0.0, "exit", false, false,
                false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
        StrategyPerformanceGate g = new StrategyPerformanceGate(repo, p, regimeSvc, costModel, hold, sampleRepo,
                List.of(), COST, "", "nextClose", 0.0, "", "", "", "");

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B");

        assertThat(d.samples()).isEqualTo(25);   // 정확 60분 없어도 90분 근접 대체로 집계
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void 대조군은_게이트net에서_제외() {
        // 진입분 20건(+1% → net 0.82)에 대조군 20건(-5% → net -5.18)을 섞어도, 진입분만 집계돼야 통과.
        java.util.List<TradeOutcome> rows = new ArrayList<>(samples("VOLUME_LEADING_B", 20, 1.0, null));
        java.util.List<TradeOutcome> controls = samples("VOLUME_LEADING_B", 20, -5.0, null);
        controls.forEach(o -> o.markControl("DIRECTION_DOWN"));
        rows.addAll(controls);
        StrategyPerformanceGate g = gate(props(true, 20, 0.0), rows, null);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B");

        assertThat(d.samples()).isEqualTo(20);            // 진입분만(대조군 20 제외)
        assertThat(d.netAvgReturnPct()).isEqualTo(0.82);  // 대조군 -5% 미포함
        assertThat(d.allowed()).isTrue();                 // 오염됐다면 net 음수로 차단됐을 것
    }

    // ─── 보수적 국면무관 fallback(②엄격바+③축소사이징+④자동졸업) ───

    @Test
    void fallback_현재국면표본부족이면_전국면pool로_통과_축소플래그() {
        // 현재 국면=BULL(강세), 강세 표본은 10건뿐(<minSamples 30) → 국면표본 부족.
        // 전국면 pool: 강세10 + 중립40 = 50건 모두 +1%(net 0.82%) → fallbackMinSamples 50·net 0.5% 충족 → fallback 통과.
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("VOLUME_LEADING_B", 10, 1.0, "BULL"));
        rows.addAll(samples("VOLUME_LEADING_B", 40, 1.0, "NEUTRAL"));
        StrategyPerformanceGate g = gate(fallbackProps(30, 50, 0.5), rows, MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B", "KOSPI");

        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isTrue();
        assertThat(d.samples()).isEqualTo(50);              // 전국면 pool 표본 노출
        assertThat(d.reason()).contains("fallback통과");
    }

    @Test
    void fallback_전국면pool도_표본부족이면_차단() {
        // 강세 5건 + 중립 20건 = 25건 < fallbackMinSamples 50 → fallback도 미달 → 차단(fallback=false).
        List<TradeOutcome> rows = new ArrayList<>();
        rows.addAll(samples("VOLUME_LEADING_B", 5, 1.0, "BULL"));
        rows.addAll(samples("VOLUME_LEADING_B", 20, 1.0, "NEUTRAL"));
        StrategyPerformanceGate g = gate(fallbackProps(30, 50, 0.5), rows, MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B", "KOSPI");

        assertThat(d.allowed()).isFalse();
        assertThat(d.fallback()).isFalse();
        assertThat(d.reason()).contains("fallback미달");
    }

    @Test
    void fallback_국면표본충분하면_엄격경로로_졸업_fallback미사용() {
        // 강세 30건(≥minSamples 30) net 0.82% → 국면조건부 엄격 경로로 통과, fallback 안 씀.
        StrategyPerformanceGate g = gate(fallbackProps(30, 50, 0.5),
                samples("VOLUME_LEADING_B", 30, 1.0, "BULL", "KOSPI"), MarketTrend.BULL);

        StrategyPerformanceGate.GateDecision d = g.evaluate("VOLUME_LEADING_B", "KOSPI");

        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isFalse();                 // ④ 졸업: 엄격 경로
        assertThat(d.reason()).contains("통과");
        assertThat(d.reason()).doesNotContain("fallback");
    }

    @Test
    void 히스테리시스_열땐0_3_닫을땐m0_2_밴드에선_직전상태_유지() {
        // 열기 0.3 / 닫기 −0.2. 밴드(−0.2~0.3)에선 직전 상태 유지 → 문턱 근처 여닫이 진동 억제.
        // props: min=0.3, close=−0.2, regimeConditional=false, minSamples=5.
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 5, 0.3, "close",
                false, false, false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, -0.2);
        List<TradeOutcome> rows = new ArrayList<>();   // 가변 — 매 호출 사이 net 교체(같은 gate 인스턴스=상태 유지)
        StrategyPerformanceGate g = gate(p, rows, null);
        // net = closeReturnPct − COST(0.18)
        java.util.function.DoubleConsumer setNet = net -> {
            rows.clear();
            rows.addAll(samples("MOMENTUM_A", 5, net + COST, null));
        };

        // ① 닫힘 상태 + 밴드(net 0.1 < 열기 0.3) → 계속 닫힘
        setNet.accept(0.1);
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isFalse();
        // ② net 0.4 ≥ 0.3 → 오픈
        setNet.accept(0.4);
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isTrue();
        // ③ 오픈 상태 + 밴드(net 0.1, 열기 0.3엔 미달이지만 닫기 −0.2 이상) → 유지(오픈)
        setNet.accept(0.1);
        StrategyPerformanceGate.GateDecision hold = g.evaluate("MOMENTUM_A", "KOSPI");
        assertThat(hold.allowed()).isTrue();
        assertThat(hold.reason()).contains("히스테리시스");
        // ④ net −0.3 < 닫기 −0.2 → 닫힘
        setNet.accept(-0.3);
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isFalse();
    }

    @Test
    void 히스테리시스_off면_밴드에서_닫힘_유지안됨() {
        // close(0.3) ≥ min(0.3) → 히스테리시스 off = stateless. 오픈 뒤 net 0.1이면 그냥 닫힘(기존 동작).
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 5, 0.3, "close",
                false, false, false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 0.3);   // close=min → off
        List<TradeOutcome> rows = new ArrayList<>();
        StrategyPerformanceGate g = gate(p, rows, null);
        rows.addAll(samples("MOMENTUM_A", 5, 0.4 + COST, null));   // net 0.4 → 오픈
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isTrue();
        rows.clear(); rows.addAll(samples("MOMENTUM_A", 5, 0.1 + COST, null));   // net 0.1
        StrategyPerformanceGate.GateDecision d = g.evaluate("MOMENTUM_A", "KOSPI");
        assertThat(d.allowed()).isFalse();                  // off라 유지 안 됨(0.1<0.3)
        assertThat(d.reason()).doesNotContain("히스테리시스");
    }

    @Test
    void 히스테리시스_상태는_국면버킷별로_분리된다() {
        // 실측 계기(2026-08-13 K): 장중에 라벨이 중립↔강세로 뒤집히면 표본도 net도 다른 버킷이 된다.
        // 키가 (전략:시장)뿐이던 시절엔 버킷이 바뀌어도 같은 상태를 물려받아, 옛 버킷의 '열림'이 새 버킷에
        // 완화된 닫기바를 잘못 적용했다. 이제 키가 (전략:시장:국면:흐름)이라 버킷끼리 간섭하지 않는다.
        // props: min=0.3, close=−0.2, regimeConditional=true, minSamples=5, fallback on(50/0.5).
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 5, 0.3, "close",
                true, false, true, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, -0.2);
        List<TradeOutcome> rows = new ArrayList<>();
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);   // 국면을 장중에 바꿔가며 버킷 교체를 재현
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        StrategyPerformanceGate g = new StrategyPerformanceGate(repo, p, regimeSvc, costModel,
                mock(StrategyHoldTimeProvider.class), mock(OutcomeSampleRepository.class), List.of(),
                COST, "", "nextClose", 0.0, "", "", "", "");

        // ① 강세 버킷 net 0.4 → 열기바(0.3) 통과 → 강세 버킷만 오픈
        when(regimeSvc.trendOf(any())).thenReturn(MarketTrend.BULL);
        rows.addAll(samples("MOMENTUM_A", 5, 0.4 + COST, "BULL"));
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isTrue();

        // ② 장중 라벨 중립 전환 → 강세 표본 미매칭으로 표본부족 → fallback도 미달(5<50) → 차단.
        //    이때 정리되는 건 '중립' 버킷 키뿐 — 강세 버킷의 열림은 보존된다.
        when(regimeSvc.trendOf(any())).thenReturn(MarketTrend.NEUTRAL);
        StrategyPerformanceGate.GateDecision mid = g.evaluate("MOMENTUM_A", "KOSPI");
        assertThat(mid.allowed()).isFalse();
        assertThat(mid.reason()).contains("국면표본부족");

        // ③ 라벨 복귀 + net이 밴드(0.1)로 하락 → 같은 강세 버킷이므로 닫기바(−0.2) 유지 → 통과(진동 억제)
        when(regimeSvc.trendOf(any())).thenReturn(MarketTrend.BULL);
        rows.clear();
        rows.addAll(samples("MOMENTUM_A", 5, 0.1 + COST, "BULL"));
        StrategyPerformanceGate.GateDecision back = g.evaluate("MOMENTUM_A", "KOSPI");
        assertThat(back.allowed()).isTrue();
        assertThat(back.reason()).contains("히스테리시스");
    }

    @Test
    void 같은_버킷이_표본부족이면_그_버킷의_히스테리시스는_닫힘으로_정리된다() {
        // 버킷 교체가 아니라 '그 버킷 자체'가 표본을 잃은 경우 — 열림을 정당화하던 엄격 판정이 없으므로
        // 닫힘으로 정리(fail-closed). 복귀 시 다시 열기바(0.3)를 넘어야 한다.
        // regimeConditional=false → 키의 국면·흐름 자리는 "_"로 고정 = 처음부터 끝까지 같은 버킷.
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 5, 0.3, "close",
                false, false, false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, -0.2);
        List<TradeOutcome> rows = new ArrayList<>();
        StrategyPerformanceGate g = gate(p, rows, null);

        rows.addAll(samples("MOMENTUM_A", 5, 0.4 + COST, null));      // net 0.4 → 오픈
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isTrue();

        rows.clear(); rows.addAll(samples("MOMENTUM_A", 3, 0.4 + COST, null));   // 표본 3<5 → fail-closed + 정리
        assertThat(g.evaluate("MOMENTUM_A", "KOSPI").allowed()).isFalse();

        rows.clear(); rows.addAll(samples("MOMENTUM_A", 5, 0.1 + COST, null));   // 표본 복귀, net은 밴드(0.1)
        StrategyPerformanceGate.GateDecision back = g.evaluate("MOMENTUM_A", "KOSPI");
        assertThat(back.allowed()).isFalse();                          // 정리됐으므로 열기바 0.3 적용
        assertThat(back.reason()).doesNotContain("히스테리시스");
    }

    @Test
    void 전일국면_전략은_장중라벨이_아니라_전일확정라벨로_버킷팅된다() {
        // K(개장갭)는 정의가 "어제까지 국면 + 오늘 시초가 갭"인데, 유효 창(09:00~09:30)이 장중 라벨이 가장
        // 불안정한 구간이라 장중 라벨로 버킷을 잡으면 표본 풀이 계속 바뀐다(2026-08-13 실측).
        // → prior-day-regime-strategies 에 지정된 전략은 priorDayTrendOf 로 버킷팅한다.
        StrategyPerformanceProperties p = new StrategyPerformanceProperties(true, 20, 5, 0.0, "close",
                true, false, false, 50, 0.5, 0.5, 10, 0.3, true, 30, "", 0, 999.0);
        List<TradeOutcome> rows = new ArrayList<>(samples("OPENING_GAP_K", 5, 1.0 + COST, "NEUTRAL"));
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenReturn(rows);
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(MarketTrend.BULL);            // 장중(흔들리는) 라벨
        when(regimeSvc.priorDayTrendOf(any())).thenReturn(MarketTrend.NEUTRAL); // 전일 확정 라벨
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        StrategyPerformanceGate g = new StrategyPerformanceGate(repo, p, regimeSvc, costModel,
                mock(StrategyHoldTimeProvider.class), mock(OutcomeSampleRepository.class), List.of(),
                COST, "", "nextClose", 0.0, "", "", "", "OPENING_GAP_K");

        // 표본은 전부 NEUTRAL 태그 — 전일 라벨(NEUTRAL) 버킷으로 잡혀야 5건이 매칭돼 통과
        StrategyPerformanceGate.GateDecision d = g.evaluate("OPENING_GAP_K", "KOSPI");
        assertThat(d.regimeTrend()).isEqualTo("NEUTRAL");
        assertThat(d.samples()).isEqualTo(5);
        assertThat(d.allowed()).isTrue();

        // 미지정 전략은 종전대로 장중 라벨(BULL) 사용 → NEUTRAL 표본이 안 잡혀 표본부족
        StrategyPerformanceGate g2 = new StrategyPerformanceGate(repo, p, regimeSvc, costModel,
                mock(StrategyHoldTimeProvider.class), mock(OutcomeSampleRepository.class), List.of(),
                COST, "", "nextClose", 0.0, "", "", "", "");
        StrategyPerformanceGate.GateDecision d2 = g2.evaluate("OPENING_GAP_K", "KOSPI");
        assertThat(d2.regimeTrend()).isEqualTo("BULL");
        assertThat(d2.allowed()).isFalse();
    }

    // ── 교차 거래일 요건(단일일 클러스터 방지) ──
    private TradeOutcome outcomeOn(String strategy, String date, int i, double closeReturnPct) {
        long buy = 10_000;
        TradeOutcome o = new TradeOutcome(strategy, null, "00593" + i, date, buy);
        o.setPriceClose(Math.round(buy * (1 + closeReturnPct / 100.0)));
        o.setEntryMarketTrend(null);
        return o;
    }

    @Test
    void 단일일_클러스터_버킷은_net_좋아도_교차거래일_미충족으로_차단() {
        // 30건 전부 한 거래일(20260626) — net +2%(≥기준)여도 이벤트 1개라 LIVE 졸업 불가(share 100%>50)
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(outcomeOn("MOMENTUM_A", "20260626", i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gate(props(true, 20, 0.0), rows, null, 50.0).evaluate("MOMENTUM_A");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("단일일 클러스터");
    }

    @Test
    void 여러_거래일에_분산된_버킷은_통과() {
        // 30건을 5거래일에 6건씩(한 날 점유율 20%<50%) — 정상 net 판정 → 통과
        String[] dates = {"20260620", "20260621", "20260622", "20260623", "20260624"};
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(outcomeOn("MOMENTUM_A", dates[i % 5], i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gate(props(true, 20, 0.0), rows, null, 50.0).evaluate("MOMENTUM_A");
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void 클러스터_요건_0이면_비활성_단일일도_통과() {
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(outcomeOn("MOMENTUM_A", "20260626", i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gate(props(true, 20, 0.0), rows, null, 0.0).evaluate("MOMENTUM_A");   // 0=비활성
        assertThat(d.allowed()).isTrue();
    }

    // ── 전략별 net 재검증 시작일(strategy-since) + 부트스트랩 다리 ──
    /** since/bootstrap 지정 + cutoff를 실제로 존중하는 repo(since 필터 검증용). */
    private StrategyPerformanceGate gateSince(StrategyPerformanceProperties p, List<TradeOutcome> rows,
                                              String sinceCsv, String bootstrapCsv) {
        return gateSince(p, rows, sinceCsv, bootstrapCsv, "");
    }

    private StrategyPerformanceGate gateSince(StrategyPerformanceProperties p, List<TradeOutcome> rows,
                                              String sinceCsv, String bootstrapCsv, String refilterCsv) {
        TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
        when(repo.findByStrategyAndAlertDateGreaterThanEqual(any(), any())).thenAnswer(inv -> {
            String cutoff = inv.getArgument(1);
            return rows.stream().filter(o -> o.getAlertDate().compareTo(cutoff) >= 0).toList();
        });
        MarketRegimeService regimeSvc = mock(MarketRegimeService.class);
        when(regimeSvc.trendOf(any())).thenReturn(null);
        ExecutionCostModel costModel = mock(ExecutionCostModel.class);
        when(costModel.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        return new StrategyPerformanceGate(repo, p, regimeSvc, costModel, mock(StrategyHoldTimeProvider.class),
                mock(OutcomeSampleRepository.class), List.of(), COST, "", "nextClose", 0.0, sinceCsv, bootstrapCsv, refilterCsv, "");
    }

    @Test
    void since_리셋되면_이전_표본_제외되어_표본부족_차단() {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<TradeOutcome> rows = new ArrayList<>();   // 과거(구로직) 표본 30건 — since로 전부 제외됨
        for (int i = 0; i < 30; i++) rows.add(outcomeOn("MOMENTUM_A", "20200101", i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gateSince(props(true, 20, 0.0), rows, "MOMENTUM_A:" + today, "").evaluate("MOMENTUM_A");
        assertThat(d.allowed()).isFalse();
        assertThat(d.samples()).isZero();
        assertThat(d.reason()).contains("since " + today);
    }

    @Test
    void 부트스트랩_전략은_표본미달이어도_축소사이징_허용() {
        List<TradeOutcome> rows = new ArrayList<>();   // 5건(<minSamples 20)
        for (int i = 0; i < 5; i++) rows.add(outcomeOn("MOMENTUM_A", "20260805", i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gateSince(props(true, 20, 0.0), rows, "", "MOMENTUM_A").evaluate("MOMENTUM_A");
        assertThat(d.allowed()).isTrue();
        assertThat(d.fallback()).isTrue();          // 축소사이징
        assertThat(d.reason()).contains("부트스트랩");
    }

    @Test
    void 부트스트랩_미지정_전략은_표본미달이면_차단() {
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(outcomeOn("MOMENTUM_A", "20260805", i, 2.0));
        StrategyPerformanceGate.GateDecision d =
                gateSince(props(true, 20, 0.0), rows, "", "").evaluate("MOMENTUM_A");
        assertThat(d.allowed()).isFalse();          // fail-closed 유지
    }

    // ── 구표본 자동 재필터 ──
    private TradeOutcome outcomeVr(String date, int i, double ret, double volRatio) {
        TradeOutcome o = outcomeOn("MOMENTUM_A", date, i, ret);
        o.recordEntryFeatures(0, volRatio, 50, 10, 1, null, 0, null, null);
        return o;
    }

    @Test
    void 재필터는_새필터_통과_구표본만_채점() {
        String[] dates = {"20260801", "20260802", "20260803", "20260804", "20260805"};
        List<TradeOutcome> rows = new ArrayList<>();
        // 저볼륨(3배) 30건 net −2 — 새 필터(vol≥6)면 걸렀을 구표본
        for (int i = 0; i < 30; i++) rows.add(outcomeVr(dates[i % 5], i, -2.0, 3));
        // 고볼륨(10배) 30건 net +2 — 통과
        for (int i = 0; i < 30; i++) rows.add(outcomeVr(dates[i % 5], 100 + i, 2.0, 10));

        var noRf = gateSince(props(true, 20, 0.0), rows, "", "").evaluate("MOMENTUM_A");
        assertThat(noRf.samples()).isEqualTo(60);   // 재필터 없음 → 전부

        var rf = gateSince(props(true, 20, 0.0), rows, "", "", "MOMENTUM_A:volume_ratio>=6")
                .evaluate("MOMENTUM_A");
        assertThat(rf.samples()).isEqualTo(30);      // 고볼륨 30건만 채점
        assertThat(rf.allowed()).isTrue();           // net +2(−비용) ≥ 0
        assertThat(rf.reason()).contains("재필터");
    }
}
