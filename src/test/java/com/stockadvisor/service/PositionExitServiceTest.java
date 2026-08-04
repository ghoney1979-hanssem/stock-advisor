package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.ExitMethodType;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 시간기반 청산 검증. 시각 의존을 줄이려 session-end 를 극단값으로 둬 결정적으로 만든다.
 */
class PositionExitServiceTest {

    private TradingPolicyProperties policy(String sessionEnd, int holdMinutes) {
        return new TradingPolicyProperties(true, TradingMode.DRY_RUN, 10.0, 0, 50_000, 10,
                sessionEnd, holdMinutes, true, List.of(), 3, 5, 0);
    }

    /** 모든 전략에 고정 보유시간을 반환하는 provider 스텁. */
    private StrategyHoldTimeProvider holdProvider(int holdMinutes) {
        StrategyHoldTimeProvider p = mock(StrategyHoldTimeProvider.class);
        when(p.holdMinutes(any())).thenReturn(holdMinutes);
        return p;
    }

    /** 서킷브레이커 off=주어진 상태인 리스크가드 스텁. */
    private MarketRiskGuard riskGuard(boolean off) {
        MarketRiskGuard g = mock(MarketRiskGuard.class);
        MarketRiskGuard.RiskOff ro = new MarketRiskGuard.RiskOff(off, off ? "코스피 -3.50%" : null);
        when(g.isRiskOff()).thenReturn(ro);
        when(g.isRiskOff(org.mockito.ArgumentMatchers.any())).thenReturn(ro);   // 시장별 서킷 — 시장 무관 스텁(테스트)
        return g;
    }

    /** 손절 provider 스텁 — 전 전략 고정 −7%(fail-closed 기본값과 동일). */
    private StrategyStopProvider stopProvider() {
        StrategyStopProvider p = mock(StrategyStopProvider.class);
        lenient().when(p.stopPct(any())).thenReturn(7.0);
        return p;
    }

    /** 청산방식 provider 스텁(표본 충분 가정). */
    private ExitMethodProvider exitMethod(ExitMethodType type, double param) {
        ExitMethodProvider p = mock(ExitMethodProvider.class);
        when(p.methodFor(any())).thenReturn(new ExitStrategyService.BestExit("X", type, param, 0, 999));
        return p;
    }
    private ExitMethodProvider timeMethod() { return exitMethod(ExitMethodType.TIME, 0); }

    /** 추세전환 방식 + N회 연속 하락 확인 스텁. */
    private ExitMethodProvider trendMethod(int confirm) {
        ExitMethodProvider p = mock(ExitMethodProvider.class);
        when(p.methodFor(any())).thenReturn(new ExitStrategyService.BestExit("X", ExitMethodType.TREND_REVERSAL, 0, 0, 999));
        when(p.trendConfirm()).thenReturn(confirm);
        return p;
    }

    private Order openPosition(long minutesAgo) {
        Order pos = mock(Order.class);
        when(pos.getCreatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        when(pos.getStrategy()).thenReturn("MEAN_REVERSION_C");
        when(pos.getStockCode()).thenReturn("005930");
        when(pos.getRequestedQty()).thenReturn(1L);
        when(pos.getRequestedPrice()).thenReturn(70_000L);
        when(pos.getId()).thenReturn(1L);
        return pos;
    }

    /** 인버스 포지션 목(mock) — 114800(코스피 인버스). */
    private Order inversePosition(long minutesAgo) {
        Order pos = mock(Order.class);
        when(pos.getCreatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        when(pos.getStrategy()).thenReturn("INVERSE_INDEX_I");
        when(pos.getStockCode()).thenReturn("114800");
        when(pos.getRequestedQty()).thenReturn(100L);
        when(pos.getRequestedPrice()).thenReturn(1_100L);
        when(pos.getId()).thenReturn(9L);
        return pos;
    }

    @Test
    void 휴장일_거부_감지시_당일_청산_제출_전면중단() {
        // 2026-07-17 실측: 휴장일에 매분 매도 제출 → KIS "장운영일자가 주문일과 상이합니다" 거부 390건.
        // 첫 거부에서 휴장 감지 → 같은 날 이후 점검은 제출 없이 보류(다음 거래일 처리) 검증.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(200);   // 보유시간 초과 — 평일이면 시간경과 청산 대상
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(71_000L);
        when(orderService.submit(any())).thenReturn(
                OrderService.OrderResult.failed(99L, "KIS 거부: 장운영일자가 주문일과 상이합니다"));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(0);   // 1차: 제출 시도 → 휴장 거부 감지
        assertThat(svc.closeDuePositions()).isEqualTo(0);   // 2차: 당일 플래그로 제출 자체 생략
        verify(orderService, times(1)).submit(any());        // 제출은 정확히 1회만(스팸 차단)
    }

    @Test
    void 주문컷오프_이후엔_청산_제출_보류() {
        // 2026-07-16 안트로젠: 15:30 장 종료 후에도 청산 cron(~16:59)이 매도를 제출 → KIS "장운영시간 아님"
        // 거부 → 매분 REJECTED+알림 스팸. 컷오프 이후엔 제출 없이 보류(다음 거래일 처리) 검증.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(2000);   // 보유시간 한참 초과 — 컷오프 아니면 청산됐을 포지션
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());
        svc.setExitOrderCutoff("00:00");   // 항상 컷오프 이후

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 인버스_지수약세_지속이면_시간무관_보유() {
        // 보유 200분(일반 hold 60분 훨씬 초과)이어도 지수가 계속 약세(-2.5%)면 보유 — 시간청산 미적용
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(200);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_130L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-2.5);   // 약세 지속
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 인버스_지수_레벨회복시_청산() {
        // 지수 -0.3% > -0.5%(회복선) → 약세 명제 소멸 → 청산
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(30);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_090L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-0.3);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(10L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(1);
    }

    @Test
    void 인버스_지수_모멘텀반등시_청산() {
        // 레벨은 아직 약세(-2.0%)지만 mom30 +0.5% ≥ thr max(0.3, 2.0×0.15)=0.3 → 반등 시작 청산.
        // mom10은 0.0으로 둬 판정이 mom30 기준임을 함께 검증(mom10 단독 발사 제거 — 2026-07-16 과민 청산 대응).
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(30);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_120L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-2.0);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.intradayFlow("KOSPI")).thenReturn(new MarketRegimeService.IntradayFlow(0.0, 0.5, null, true));
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(11L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());
        svc.setMarketRegimeService(regime);

        assertThat(svc.closeDuePositions()).isEqualTo(1);
    }

    @Test
    void 인버스_폭락일엔_반등임계가_낙폭비례로_상향() {
        // 당일 -6.7% 폭락일: thr = max(0.3, 6.7×0.15) ≈ 1.0% → mom30 +0.5%(평시라면 청산)로는 보유 유지.
        // 2026-07-16 실측 재현 — 데드캣 미세반등에 팔고 더 높게 재매수하던 과민 반복 방지.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(60);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_120L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-6.7);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.intradayFlow("KOSPI")).thenReturn(new MarketRegimeService.IntradayFlow(0.6, 0.5, null, true));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());
        svc.setMarketRegimeService(regime);

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 인버스_mom10_단독_스파이크는_청산_안함() {
        // mom10 +1.0% 스파이크지만 mom30 -0.2%(30분 기준 아직 하락) → 보유. mom30 미산출이면 판정 생략(보유)도 겸검증.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(30);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_120L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-2.0);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.intradayFlow("KOSPI")).thenReturn(new MarketRegimeService.IntradayFlow(1.0, -0.2, null, true));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());
        svc.setMarketRegimeService(regime);

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 인버스_손절은_지수스케일_2퍼센트() {
        // 인버스는 전략별 적응형(-7%)이 아니라 전용 -2% 손절 — riskGuard가 2.0으로 호출되는지 검증
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = inversePosition(10);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_070L);   // -2.7%
        MarketRiskGuard g = riskGuard(false);
        when(g.catastrophicStopHit(1_100L, 1_070L, 2.0)).thenReturn(true);   // 전용 손절선으로 판정
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(12L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), g, timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(1);
        verify(g).catastrophicStopHit(1_100L, 1_070L, 2.0);
    }

    @Test
    void 추세전환_N회연속하락_확인후_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(pos.getLastPrice()).thenReturn(71_000L);   // 직전가
        when(pos.getTrendDownCount()).thenReturn(2);     // 이미 2회 연속 하락
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_000L);   // 또 하락 → 3회째(=confirm)
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), trendMethod(3), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).setTrendDownCount(3);
        verify(orderService).submit(any());
    }

    @Test
    void 추세전환_확인횟수_미달이면_보유() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(pos.getLastPrice()).thenReturn(71_000L);
        when(pos.getTrendDownCount()).thenReturn(0);     // 첫 하락
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_000L);   // 1회째(<3)
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), trendMethod(3), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);
        verify(pos).setTrendDownCount(1);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 추세전환_반등하면_카운터_리셋() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(pos.getLastPrice()).thenReturn(70_000L);
        when(pos.getTrendDownCount()).thenReturn(2);     // 2회 쌓였다가
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(71_000L);   // 반등 → 리셋
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), trendMethod(3), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);
        verify(pos).setTrendDownCount(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 보유시간_경과시_매도하고_손익기록() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(90);   // 90분 전 진입 (보유 60분 초과)
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(72_000L);   // 청산가
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // session-end 23:59 → 장마감 트리거는 사실상 비활성, 순수 시간경과만 검증
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(orderService).submit(any());
        verify(pos).closePosition(1_846L);   // (72000-70000)×1 − 비용 154(70000×0.22%)
        verify(repo).save(pos);
    }

    @Test
    void 보유시간_미경과_장중이면_스킵() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);    // 10분 전 진입 (보유 60분 미만)
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_000L);
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);
        verify(orderService, never()).submit(any());
        verify(pos, never()).closePosition(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 장마감_도달시_보유시간_무관하게_강제청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);    // 보유 60분 미만이라도
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(69_000L);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // session-end 00:00 → 현재시각이 항상 이후 → 강제청산
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("00:00", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).closePosition(-1_154L);   // (69000-70000)×1 − 비용 154
    }

    @Test
    void 적응형_보유시간이_고정값과_다르면_그_값으로_청산판정() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(90);   // 90분 전 진입
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(71_000L);
        // 적응형 권장 보유시간 120분 → 90분 보유는 아직 미경과 → 스킵 (고정 60분이었다면 청산됐을 것)
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(120), riskGuard(false), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 진입시점_락한_보유시간을_provider보다_우선사용() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(50);                 // 보유 50분
        when(pos.getHoldMinutes()).thenReturn(45);    // 진입 시점 락 45분
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(71_000L);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // provider는 120분이지만 락 45분이 우선 → 50≥45 → 청산 (provider 120이었다면 미청산)
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(120), riskGuard(false), timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(1);
        verify(pos).closePosition(846L);   // (71000-70000)×1 − 비용 154
    }

    @Test
    void 손절_하한_도달시_보유시간_무관하게_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);    // 보유 10분(미경과), 장중(23:59), 리스크오프 아님
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(64_000L);   // 매수 70,000 대비 -8.6%
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // 손절 7% 활성 + 현재가가 하한 이하 → hit
        MarketRiskGuard rg = mock(MarketRiskGuard.class);
        when(rg.isRiskOff()).thenReturn(new MarketRiskGuard.RiskOff(false, null));
        when(rg.isRiskOff(org.mockito.ArgumentMatchers.any())).thenReturn(new MarketRiskGuard.RiskOff(false, null));
        when(rg.catastrophicStopPct()).thenReturn(7.0);
        when(rg.catastrophicStopHit(70_000L, 64_000L, 7.0)).thenReturn(true);
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), rg, timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).closePosition(-6_154L);   // (64000-70000)×1 − 비용 154
    }

    @Test
    void 상한가_도달시_전략무관_즉시_익절청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);   // 보유10분(미경과), 장중, 리스크오프 아님
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(90_000L);   // 매수 70,000 → 이익(상한가 감지 대상)
        when(kis.fetchDayChangeRate("005930")).thenReturn(30.0);    // 당일 +30% = 상한가
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "", stopProvider());
        svc.setLimitUpLockPct(29.0);

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);   // 보유시간 미경과여도 상한가면 즉시 청산
        verify(orderService).submit(any());
    }

    @Test
    void 스윙_트레일링_고점되돌림시_조기청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);   // C(스윙), 보유10분, 장중(23:59) → 익일종가 아직 아님
        when(pos.getPeakPrice()).thenReturn(75_000L);   // 고점 >매수(70,000) → 트레일 arm
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(72_000L);   // 고점 대비 -4% (≤ 75000×0.97=72750) → 트레일 발동
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "MEAN_REVERSION_C", stopProvider());
        svc.setSwingTrailPct(3.0);

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(orderService).submit(any());   // 트레일 되돌림 조기 청산
    }

    @Test
    void 스윙_고점이_매수이하면_트레일_미발동_보유() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(pos.getPeakPrice()).thenReturn(70_000L);   // 고점=매수(이익구간 아님) → arm 안 됨
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(67_000L);   // -4%지만 트레일 arm 안 됨(손절 −7% 미달)
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(false), timeMethod(), "MEAN_REVERSION_C", stopProvider());
        svc.setSwingTrailPct(3.0);

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);   // 보유(익일 대기)
        verify(orderService, never()).submit(any());
    }

    @Test
    void 인버스는_리스크오프_강제청산_면제() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);                 // 보유 10분(시간청산 미도달)
        when(pos.getStockCode()).thenReturn("114800");   // 인버스 ETF
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("114800")).thenReturn(1_000L);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-3.0);   // 지수 약세 지속 — 인버스 전용 청산도 보유 판정
        // 리스크오프 ON — 일반주면 강제청산되지만 인버스는 면제 → 보유 유지
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(true), timeMethod(), "", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(0);   // 인버스는 서킷에도 청산 안 함(승자 보유)
        verify(orderService, never()).submit(any());
    }

    @Test
    void 서킷브레이커_발동시_보유시간_무관하게_청산가속() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);    // 보유 10분(미경과), 장중(23:59)이라도
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(68_000L);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // 서킷브레이커 ON → 즉시 청산
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(true), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).closePosition(-2_154L);   // (68000-70000)×1 − 비용 154
    }

    @Test
    void 서킷브레이커_발동시_미청산_0이어도_알림_발송() {
        // 회귀 방지: 서킷 알림이 open.isEmpty() 조기반환 뒤에 있어 미청산 0일 때 미발송됐던 버그.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());   // 미청산 포지션 없음
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(true), timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);   // 청산할 포지션은 없지만
        // 알림은 나가야 함(시장별 — 코스피/코스닥 각각 전이 시 발송, atLeastOnce)
        verify(orderService, org.mockito.Mockito.atLeastOnce())
                .notifyEvent(org.mockito.ArgumentMatchers.contains("서킷브레이커 발동"));
    }

    @Test
    void 시장별서킷_타시장_폭락엔_강제청산_안함() {
        // 코스닥만 서킷 발동 → 코스피 종목 포지션은 강제청산 안 됨(시장별 서킷).
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);                       // 보유 미경과, 장중
        when(pos.getMarket()).thenReturn("KOSPI");          // 코스피 종목
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_000L);   // 손절 아님(진입가 동일)
        MarketRiskGuard g = mock(MarketRiskGuard.class);
        when(g.isRiskOff("KOSDAQ")).thenReturn(new MarketRiskGuard.RiskOff(true, "코스닥 -7%"));
        when(g.isRiskOff("KOSPI")).thenReturn(new MarketRiskGuard.RiskOff(false, null));   // 코스피 정상
        when(g.catastrophicStopPct()).thenReturn(0.0);      // 손절 비활성
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), g, timeMethod(), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(0);                    // 코스닥 서킷이 코스피 종목을 청산하지 않음
        verify(orderService, never()).submit(any());
    }

    @Test
    void 트레일링_방식_고점대비_되돌림시_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(30);   // 보유 30분(시간 미경과), 장중
        when(pos.getPeakPrice()).thenReturn(100_000L);   // 진입 후 고점 10만
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(97_000L);   // 고점 대비 -3% (트레일 2% 초과)
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // 청산방식 = 트레일링 2% → 고점10만×0.98=98000, 현재가 97000 ≤ 98000 → 청산
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(false), exitMethod(ExitMethodType.TRAILING, 2.0), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).closePosition(26_846L);   // (97000-70000)×1 − 비용 154
    }

    private static String todayKst() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    @Test
    void 스윙전략_진입일엔_장마감에도_청산안함() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(pos.getOrderDate()).thenReturn(todayKst());   // 진입일 == 오늘
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(70_000L);
        // 장마감(00:00)이어도 C는 스윙 → 진입일엔 보유(오버나잇)
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("00:00", 60),
                holdProvider(60), riskGuard(false), timeMethod(), "MEAN_REVERSION_C", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());   // 익일까지 보유
    }

    @Test
    void 스윙전략_익일_장마감에_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(1_000);
        when(pos.getOrderDate()).thenReturn("19000101");   // 진입일 != 오늘(=다음 거래일 도래)
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(71_000L);
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("00:00", 60),
                holdProvider(60), riskGuard(false), timeMethod(), "MEAN_REVERSION_C", stopProvider());

        assertThat(svc.closeDuePositions()).isEqualTo(1);
        verify(pos).closePosition(846L);   // 익일 종가 청산 (71000-70000)×1 − 비용 154
    }

    @Test
    void VWAP방식_가격이_VWAP_아래면_청산() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(30);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(97_000L);
        when(kis.fetchVwapVolume("005930")).thenReturn(new KisApiClient.VwapVol(98_000.0, 1_000L));
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        // 청산방식 = VWAP이탈, 현재가 97000 < VWAP 98000 → 청산
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(false), exitMethod(ExitMethodType.VWAP, 0), "", stopProvider());

        int closed = svc.closeDuePositions();

        assertThat(closed).isEqualTo(1);
        verify(pos).closePosition(26_846L);   // (97000-70000)×1 − 비용 154
    }

    @org.junit.jupiter.api.Test
    void 컷오프_이후_리스크전이는_알림_생략() {
        // 2026-07-24 실측: 장 종료 40분 뒤 breadth 신선도 만료가 "판정 중단"→해제 전이로 위장돼 ✅ 알림 발송.
        // 컷오프 이후엔 전이 알림 음소거(상태 갱신은 유지 — 다음 날 아침 가짜 전이 방지) 검증.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60), holdProvider(60), riskGuard(true), timeMethod(), "", stopProvider());
        svc.setExitOrderCutoff("00:00");   // 항상 컷오프 이후

        svc.closeDuePositions();   // 서킷 ON 전이 발생 상황
        verify(orderService, never()).notifyEvent(org.mockito.ArgumentMatchers.anyString());
    }

    // ── 조기청산 방지 ② 방식청산 최소 보유시간 ──
    @Test
    void 방식청산_최소보유시간_안엔_신호기반청산_보류() {
        // D whipsaw 대응: 진입 10분(min-hold 15분 안) → VWAP 아래여도 신호청산 보류(숨 쉴 시간).
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(10);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(69_000L);                       // VWAP(70000) 아래
        when(kis.fetchVwapVolume("005930")).thenReturn(new KisApiClient.VwapVol(70_000.0, 1000));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(false), exitMethod(ExitMethodType.VWAP, 0), "", stopProvider());
        svc.setMethodMinHoldMinutes(15);

        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());
    }

    @Test
    void 방식청산_최소보유시간_경과후엔_신호기반청산_발동() {
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(20);                                                    // min-hold 15 경과
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchLatestClose("005930")).thenReturn(69_000L);
        when(kis.fetchVwapVolume("005930")).thenReturn(new KisApiClient.VwapVol(70_000.0, 1000));
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(false), exitMethod(ExitMethodType.VWAP, 0), "", stopProvider());
        svc.setMethodMinHoldMinutes(15);

        assertThat(svc.closeDuePositions()).isEqualTo(1);
        verify(orderService).submit(any());   // VWAP이탈 청산
    }

    // ── 조기청산 방지 ③ VWAP 이탈 히스테리시스 버퍼 ──
    @Test
    void VWAP버퍼_안이면_보유_밖이면_청산() {
        // buffer 0.5% → 임계 70000×0.995=69650. 69800(버퍼 안)은 보유, 69600(하향돌파)은 청산.
        OrderRepository repo = mock(OrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        KisApiClient kis = mock(KisApiClient.class);
        Order pos = openPosition(30);   // min-hold 무관
        when(repo.findOpenBuyPositions()).thenReturn(List.of(pos));
        when(kis.fetchVwapVolume("005930")).thenReturn(new KisApiClient.VwapVol(70_000.0, 1000));
        when(orderService.submit(any())).thenReturn(OrderService.OrderResult.dryRun(2L));
        PositionExitService svc = new PositionExitService(repo, orderService, kis, policy("23:59", 60),
                holdProvider(60), riskGuard(false), exitMethod(ExitMethodType.VWAP, 0), "", stopProvider());
        svc.setVwapBufferPct(0.5);

        when(kis.fetchLatestClose("005930")).thenReturn(69_800L);   // 버퍼 안(69650~70000) → 보유
        assertThat(svc.closeDuePositions()).isEqualTo(0);
        verify(orderService, never()).submit(any());

        when(kis.fetchLatestClose("005930")).thenReturn(69_600L);   // 버퍼 하향돌파(<69650) → 청산
        assertThat(svc.closeDuePositions()).isEqualTo(1);
        verify(orderService).submit(any());
    }
}
