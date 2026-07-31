package com.stockadvisor.service;

import com.stockadvisor.config.properties.SizingProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.market.dto.KisOrderResponse;
import com.stockadvisor.notification.DiscordNotifier;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService dry-run 골격 검증: 정책 거부는 기록 없이 차단, 통과 시 모드별 처리.
 */
class OrderServiceTest {

    private TradingPolicyProperties policy(TradingMode mode) {
        return policy(mode, java.util.List.of(), false);
    }

    private TradingPolicyProperties policy(TradingMode mode, java.util.List<String> liveStrategies) {
        return policy(mode, liveStrategies, false);
    }

    // manualConfirm 파라미터화 (기본 false=즉시발사, true=승인대기 큐잉)
    private TradingPolicyProperties policy(TradingMode mode, java.util.List<String> liveStrategies, boolean manualConfirm) {
        return new TradingPolicyProperties(true, mode, 10.0, 0, 50_000, 10, "15:20", 60, manualConfirm, liveStrategies, 3, 5, 0);
    }

    /** ATR·시장충격 비활성(고정 사이징) PositionSizer — 기존 고정 수량 동작 유지. */
    private PositionSizer sizer(KisApiClient kis) {
        ExecutionCostModel cost = new ExecutionCostModel(
                new com.stockadvisor.config.properties.ExecutionCostProperties(false, 1, 0, 0, 1.0, 0));
        return new PositionSizer(kis, policy(TradingMode.DRY_RUN), new SizingProperties(false, 14, 2.0, 0.5), cost);
    }

    /** 진입 허용 기본 스텁 리스크 가드. */
    private MarketRiskGuard riskGuard() {
        MarketRiskGuard g = mock(MarketRiskGuard.class);
        when(g.allowEntry(anyLong(), anyLong(), any(), any())).thenReturn(MarketRiskGuard.RiskDecision.allow());
        return g;
    }

    private OrderService.OrderCommand buyCmd() {
        return new OrderService.OrderCommand("MEAN_REVERSION_C", "005930", OrderSide.BUY,
                1, 70_000, 100_000, "MEAN_REVERSION_C:005930:20260629", "전기·전자", 60, "KOSPI");
    }

    /** 순자산 netAsset 원짜리 잔고응답 — 사이징(순자산×10%) 검증용. */
    private KisBalanceResponse balance(long netAsset) {
        return new KisBalanceResponse("0", "ok", java.util.List.of(),
                java.util.List.of(new KisBalanceResponse.Summary(
                        null, null, null, null, String.valueOf(netAsset), null, null, null)));
    }

    @Test
    void 정책_거부시_기록없이_REJECTED() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.deny("1주문 한도 초과"));
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submit(buyCmd());

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("한도");
        verify(repo, never()).save(any());           // 거부는 주문 기록 안 함
        verify(kis, never()).orderCash(any(), any(), anyLong(), anyLong(), any());  // KIS 호출 안 함
    }

    @Test
    void DRY_RUN_통과시_기록만하고_전송안함() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submit(buyCmd());

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.DRY_RUN);
        assertThat(r.isAccepted()).isTrue();
        verify(repo, times(1)).save(any());           // DRY-RUN 1회 기록
        verify(kis, never()).orderCash(any(), any(), anyLong(), anyLong(), any());  // 전송 안 함
    }

    @Test
    void LIVE_전송가는_호가단위로_스냅된다() {
        // 실측 버그(006110): 매도가 33,375(격자밖) → KIS "호가단위 오류" 거부. 이제 전송 전 스냅해야 함.
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        when(kis.orderCash(any(), any(), anyLong(), anyLong(), any())).thenReturn(
                new KisOrderResponse("0", "APBK0013", "정상처리",
                        new KisOrderResponse.Output("91252", "0001234567", "093015")));
        OrderService svc = new OrderService(policy(TradingMode.LIVE), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderCommand sell = new OrderService.OrderCommand("VOLUME_LEADING_B", "006110", OrderSide.SELL,
                6, 33_375, 1_000_000, "SELL:59", null, null, null);
        svc.submit(sell);

        // 매도는 내림 → 33,350 (÷50 유효) 로 전송
        verify(kis).orderCash(eq("006110"), eq(OrderSide.SELL), eq(6L), eq(33_350L), any());
    }

    @Test
    void LIVE_접수성공시_SUBMITTED() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        when(kis.orderCash(any(), any(), anyLong(), anyLong(), any())).thenReturn(
                new KisOrderResponse("0", "APBK0013", "정상처리",
                        new KisOrderResponse.Output("91252", "0001234567", "093015")));
        OrderService svc = new OrderService(policy(TradingMode.LIVE), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submit(buyCmd());

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.SUBMITTED);
        assertThat(r.isAccepted()).isTrue();
        verify(kis, times(1)).orderCash(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void submitEntry_DRY_RUN은_화이트리스트_무관하게_수량산정후_기록() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        when(kis.fetchBalance()).thenReturn(balance(1_000_000));   // 순자산 100만 → 1주문 한도 10만
        // 화이트리스트 비어도 DRY_RUN은 기록 (관찰용)
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        // 한도 100,000(순자산 100만×10%) / 가격 30,000 → 수량 3주
        OrderService.OrderResult r = svc.submitEntry("MEAN_REVERSION_C", "005930", "전기·전자", "KOSPI", 30_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.DRY_RUN);
        verify(repo, times(1)).save(any());
    }

    @Test
    void submitEntry_LIVE_미승인전략은_차단() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        // LIVE인데 화이트리스트에 없음 → 정책 평가 전에 차단
        OrderService svc = new OrderService(policy(TradingMode.LIVE, java.util.List.of("VOLUME_LEADING_B")),
                gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submitEntry("MEAN_REVERSION_C", "005930", "전기·전자", "KOSPI", 30_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("LIVE 미승인");
        verify(kis, never()).orderCash(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void submitEntry_LIVE_성과게이트_차단시_REJECTED() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        StrategyPerformanceGate perfGate = mock(StrategyPerformanceGate.class);
        // 화이트리스트엔 있지만(LIVE 허용) 성과 미달로 게이트 차단
        when(perfGate.evaluate(any(), any())).thenReturn(
                new StrategyPerformanceGate.GateDecision("MEAN_REVERSION_C", false, "성과 미달(net -0.5%)", 30, -0.5, "BEAR", "KOSDAQ", false));
        OrderService svc = new OrderService(policy(TradingMode.LIVE, java.util.List.of("MEAN_REVERSION_C")),
                gate, repo, kis, mock(DiscordNotifier.class), perfGate, riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submitEntry("MEAN_REVERSION_C", "005930", "전기·전자", "KOSPI", 30_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("성과 게이트");
        verify(kis, never()).orderCash(any(), any(), anyLong(), anyLong(), any());
        verify(kis, never()).fetchBalance();   // 게이트에서 끊겨 사이징(잔고조회)도 안 함
    }

    @Test
    void submitEntry_리스크가드_차단시_REJECTED_기록안함() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchBalance()).thenReturn(balance(1_000_000));
        MarketRiskGuard risk = mock(MarketRiskGuard.class);
        // 사이징은 됐지만(잔고 조회 성공) 총노출 한도/서킷브레이커로 진입 차단
        when(risk.allowEntry(anyLong(), anyLong(), any(), any())).thenReturn(
                MarketRiskGuard.RiskDecision.deny("서킷브레이커: 코스피 -3.50% ≤ -3.0%"));
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis,
                mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), risk, sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submitEntry("MEAN_REVERSION_C", "005930", "전기·전자", "KOSPI", 30_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("리스크 가드");
        verify(repo, never()).save(any());   // 차단은 주문 기록 안 함
    }

    @Test
    void submitEntry_인버스는_서킷_노출_리스크가드_면제하고_진입() {
        PolicyGate gate = mock(PolicyGate.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchBalance()).thenReturn(balance(1_000_000));
        MarketRiskGuard risk = mock(MarketRiskGuard.class);
        // 리스크가드가 차단하도록 스텁 — 인버스(market=INVERSE)는 이걸 건너뛰어야 함
        when(risk.allowEntry(anyLong(), anyLong(), any(), any())).thenReturn(MarketRiskGuard.RiskDecision.deny("서킷"));
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis,
                mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), risk, sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submitEntry("VOLUME_LEADING_B", "114800", null, "INVERSE", 960, "k-inv");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.DRY_RUN);   // 리스크가드 차단 무시하고 진입
        verify(risk, never()).allowEntry(anyLong(), anyLong(), any(), any());          // 인버스는 리스크가드 호출 안 함
    }

    @Test
    void submitEntry_같은종목_활성포지션_있으면_크로스전략_중복진입_스킵() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(repo.countActivePositionsByStockCode("005930")).thenReturn(1L);   // 이미 다른 전략이 보유 중
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis,
                mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submitEntry("VOLUME_LEADING_B", "005930", "전기·전자", "KOSPI", 30_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("중복 진입");
        verify(kis, never()).fetchBalance();   // 가드에서 끊겨 사이징(잔고조회)도 안 함
        verify(repo, never()).save(any());
    }

    @Test
    void submitEntry_가격이_한도초과면_수량0_스킵() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchBalance()).thenReturn(balance(1_000_000));   // 한도 10만
        OrderService svc = new OrderService(policy(TradingMode.DRY_RUN), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        // 가격 150,000 > 1주문 한도 100,000 → 수량 0
        OrderService.OrderResult r = svc.submitEntry("MOMENTUM_A", "005930", "전기·전자", "KOSPI", 150_000, "k1");

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.REJECTED);
        assertThat(r.message()).contains("수량 0");
        verify(repo, never()).save(any());
    }

    @Test
    void LIVE_KIS거부시_FAILED() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        when(kis.orderCash(any(), any(), anyLong(), anyLong(), any())).thenReturn(
                new KisOrderResponse("1", "APBK1234", "장운영시간이 아닙니다", null));
        OrderService svc = new OrderService(policy(TradingMode.LIVE), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submit(buyCmd());

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.FAILED);
        assertThat(r.message()).contains("KIS 거부");
    }

    @Test
    void 수동승인_LIVE_매수는_큐잉되고_발사_안함() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        DiscordNotifier discord = mock(DiscordNotifier.class);
        when(gate.evaluate(any(), any())).thenReturn(PolicyGate.PolicyDecision.allow());
        // LIVE + manualConfirm=true
        OrderService svc = new OrderService(
                policy(TradingMode.LIVE, java.util.List.of(), true), gate, repo, kis, discord, mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.submit(buyCmd());

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.PENDING_APPROVAL);
        verify(discord, times(1)).send(any());        // 승인 대기 통지
        verify(kis, never()).orderCash(any(), any(), anyLong(), anyLong(), any());  // 발사 안 함
    }

    @Test
    void 승인하면_발사() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pending = mock(Order.class);
        when(pending.getStatus()).thenReturn(OrderStatus.PENDING_APPROVAL);
        when(pending.getStockCode()).thenReturn("005930");
        when(pending.getSide()).thenReturn(OrderSide.BUY);
        when(pending.getRequestedQty()).thenReturn(1L);
        when(pending.getRequestedPrice()).thenReturn(70_000L);
        when(repo.findById(7L)).thenReturn(java.util.Optional.of(pending));
        when(kis.orderCash(any(), any(), anyLong(), anyLong(), any())).thenReturn(
                new KisOrderResponse("0", "ok", "정상", new KisOrderResponse.Output("91252", "0007", "0930")));
        OrderService svc = new OrderService(
                policy(TradingMode.LIVE, java.util.List.of(), true), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        OrderService.OrderResult r = svc.approve(7L);

        assertThat(r.status()).isEqualTo(OrderService.ResultStatus.SUBMITTED);
        verify(kis, times(1)).orderCash(any(), any(), anyLong(), anyLong(), any());
        verify(pending).markSubmitted();
    }

    @Test
    void 승인_타임아웃_경과시_자동거부() {
        PolicyGate gate = mock(PolicyGate.class);
        OrderRepository repo = mock(OrderRepository.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order stale = mock(Order.class);
        when(stale.getCreatedAt()).thenReturn(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)));
        when(stale.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(stale.getStockCode()).thenReturn("005930");
        when(repo.findByStatus(OrderStatus.PENDING_APPROVAL)).thenReturn(java.util.List.of(stale));
        // 승인 타임아웃 5분 < 경과 10분
        OrderService svc = new OrderService(
                policy(TradingMode.LIVE, java.util.List.of(), true), gate, repo, kis, mock(DiscordNotifier.class), mock(StrategyPerformanceGate.class), riskGuard(), sizer(kis), mock(StrategyHoldTimeProvider.class));

        assertThat(svc.expireStaleApprovals()).isEqualTo(1);
        verify(stale).markRejectedByApproval();
    }

    @org.junit.jupiter.api.Test
    void 계열_그룹키_추출() {
        // 2026-07-24 HLB 3종 동반붕괴 계기 — 선행 영문 그룹명 또는 한글 앞 2자
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf("HLB제약")).isEqualTo("HLB");
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf("HLB이노베이션")).isEqualTo("HLB");
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf("삼성전자")).isEqualTo("삼성");
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf("SK하이닉스")).isEqualTo("SK");
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(OrderService.groupKeyOf("씨젠")).isEqualTo("씨젠");
    }
}
