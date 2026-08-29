package com.stockadvisor.service;

import com.stockadvisor.config.properties.RiskProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.Order;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포트폴리오 리스크 가드: 섹터 집중 한도, 총노출 상한, 서킷브레이커.
 */
class MarketRiskGuardTest {

    // crashHaltPct=0 → 서킷 비활성(지수조회 안 함). 손절 0=비활성. exposure: bull 100%.
    private RiskProperties props(int maxPerSector, double bearPct) {
        return new RiskProperties(true, 100, 60, bearPct, 0.5, 0, maxPerSector, 0, 2.0, 15.0, 3.0, 0, 30);
    }

    private MarketRiskGuard guard(RiskProperties p, OrderRepository repo, MarketTrend trend) {
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.overallTrend()).thenReturn(trend);
        when(regime.all()).thenReturn(List.of());   // 고변동 없음
        return new MarketRiskGuard(regime, mock(KisApiClient.class), repo, p);
    }

    private Order openOrder(long krw) {
        Order o = mock(Order.class);
        when(o.getRequestedKrw()).thenReturn(krw);
        return o;
    }

    // ── 개장 창 노출 상한(A-2, 2026-08-14) ─────────────────────────
    // 실측 계기: LIVE 15건이 전부 09:01~09:24에 진입, 09:07에 노출 5,012,064원(순자산 47%)으로 예산 도달 → 갭업 고점이라 계좌 -1.78%.
    // (예산 소진이 오후까지 이어졌다는 초기 서술은 오류 — 청산 시 회복되며 그날도 10:42엔 0원. 근거는 갭 구간 상관 노출 축소.)
    // 기본은 0=비활성이라 기존 동작 무변경이고, 켰을 때만 개장 구간 상한이 min으로 결합돼야 한다.
    @Test
    void 개장창_상한은_켰을때만_국면상한과_min결합() {
        java.time.LocalTime nineTen = java.time.LocalTime.of(9, 10);
        // 비활성(0) — 국면 상한 그대로
        assertThat(MarketRiskGuard.openingCapped(50, nineTen, 0, 30)).isEqualTo(50);
        // 활성 + 창 안 — min(50, 25)
        assertThat(MarketRiskGuard.openingCapped(50, nineTen, 25, 30)).isEqualTo(25);
        // 개장 상한이 국면 상한보다 크면 국면 상한이 이김(완화 금지)
        assertThat(MarketRiskGuard.openingCapped(30, nineTen, 80, 30)).isEqualTo(30);
    }

    @Test
    void 개장창_밖에서는_상한_미적용() {
        assertThat(MarketRiskGuard.openingCapped(50, java.time.LocalTime.of(9, 30), 25, 30)).isEqualTo(50);  // 경계(창 끝, 배타)
        assertThat(MarketRiskGuard.openingCapped(50, java.time.LocalTime.of(13, 0), 25, 30)).isEqualTo(50);  // 오후
        assertThat(MarketRiskGuard.openingCapped(50, java.time.LocalTime.of(8, 50), 25, 30)).isEqualTo(50);  // 장전
        assertThat(MarketRiskGuard.openingCapped(50, java.time.LocalTime.of(9, 0), 25, 30)).isEqualTo(25);   // 경계(창 시작, 포함)
    }

    @Test
    void 개장창_상한이_걸리면_진입_차단되고_창_밖이면_허용() {
        Order held = openOrder(3_000_000);   // ⚠️ when(...) 인자 안에서 목을 스텁하면 UnfinishedStubbing — 먼저 만든다
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of(held));   // 이미 300만 보유
        // bull 100%, 고변동 없음 → 국면 상한 100%(=1,000만). 개장 창 상한 30%(=300만)
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 0, 0, 0, 2.0, 15.0, 3.0, 30, 30);
        MarketRiskGuard g = guard(p, repo, MarketTrend.BULL);

        assertThat(g.exposureCapPct(java.time.LocalTime.of(9, 10))).isEqualTo(30);   // 창 안 → 개장 상한
        assertThat(g.exposureCapPct(java.time.LocalTime.of(11, 0))).isEqualTo(100);  // 창 밖 → 국면 상한 복귀

        // 창 밖 시각의 실제 판정: 보유 300만 + 주문 50만 ≤ 1,000만 → 허용(개장 상한이 없으면 통과하는 주문)
        assertThat(g.allowEntry(500_000, 10_000_000, null).allowed()).isTrue();
    }

    @Test
    void 시장폭_리스크오프_광범위투매면_해당시장_진입차단() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(0, 30), repo, MarketTrend.NEUTRAL);
        MarketBreadthService breadth = mock(MarketBreadthService.class);
        when(breadth.isFresh(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(breadth.breadthPct("KOSDAQ")).thenReturn(8.6);          // 상승비율 8.6% < 15%
        when(breadth.medianChangePct("KOSDAQ")).thenReturn(-4.4);    // 중앙 -4.4% ≤ -3%
        when(breadth.breadthPct("KOSPI")).thenReturn(40.0);          // 코스피는 정상
        when(breadth.medianChangePct("KOSPI")).thenReturn(-1.0);
        g.setBreadthService(breadth);

        MarketRiskGuard.RiskDecision kosdaq = g.allowEntry(1_000_000, 100_000_000, null, "KOSDAQ");
        assertThat(kosdaq.allowed()).isFalse();
        assertThat(kosdaq.reason()).contains("시장폭 리스크오프");
        // 시장별 독립 — 코스닥 투매가 코스피 진입을 막지 않음
        assertThat(g.allowEntry(1_000_000, 100_000_000, null, "KOSPI").allowed()).isTrue();
    }

    @Test
    void 시장폭_스냅샷이_오래되면_판정하지_않음() {
        // 마감 후/전일 스냅샷(신선도 미달)에 오발동하면 다음 날 아침 진입이 전일 폭락 데이터로 막힘 — degrade open이어야 함
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(0, 30), repo, MarketTrend.NEUTRAL);
        MarketBreadthService breadth = mock(MarketBreadthService.class);
        when(breadth.isFresh(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);   // 40분 초과
        when(breadth.breadthPct("KOSDAQ")).thenReturn(8.6);
        when(breadth.medianChangePct("KOSDAQ")).thenReturn(-4.4);
        g.setBreadthService(breadth);

        assertThat(g.allowEntry(1_000_000, 100_000_000, null, "KOSDAQ").allowed()).isTrue();
    }

    @Test
    void 시장폭_한조건만_충족이면_통과() {
        // AND 조건 — 상승비율만 낮고 중앙이 안 깊으면(보합 눈치장) 오발동하지 않음
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(0, 30), repo, MarketTrend.NEUTRAL);
        MarketBreadthService breadth = mock(MarketBreadthService.class);
        when(breadth.isFresh(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(breadth.breadthPct("KOSDAQ")).thenReturn(12.0);          // 상승비율은 낮지만
        when(breadth.medianChangePct("KOSDAQ")).thenReturn(-0.8);     // 중앙 하락이 얕음
        g.setBreadthService(breadth);

        assertThat(g.allowEntry(1_000_000, 100_000_000, null, "KOSDAQ").allowed()).isTrue();
    }

    @Test
    void 섹터_보유가_한도이상이면_차단() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.countOpenPositionsBySector("전기·전자")).thenReturn(3L);   // 한도 3 도달
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(3, 30), repo, MarketTrend.BULL);

        MarketRiskGuard.RiskDecision d = g.allowEntry(1_000_000, 100_000_000, "전기·전자");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("섹터 집중 한도");
    }

    @Test
    void 섹터_보유가_한도미만이면_허용() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.countOpenPositionsBySector("전기·전자")).thenReturn(2L);   // 한도 3 미만
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(3, 30), repo, MarketTrend.BULL);

        assertThat(g.allowEntry(1_000_000, 100_000_000, "전기·전자").allowed()).isTrue();
    }

    @Test
    void 섹터_무제한0이면_섹터검사_생략() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = guard(props(0, 30), repo, MarketTrend.BULL);

        assertThat(g.allowEntry(1_000_000, 100_000_000, "전기·전자").allowed()).isTrue();
    }

    @Test
    void 약세장_총노출_상한초과면_차단() {
        // 약세장 노출 상한 30% → 순자산 1000만×30%=300만. 보유 250만 + 주문 100만 = 350만 > 300만 → 차단
        OrderRepository repo = mock(OrderRepository.class);
        Order held = openOrder(2_500_000);   // 중첩 스터빙 회피 — when() 밖에서 먼저 생성
        when(repo.findOpenBuyPositions()).thenReturn(List.of(held));
        MarketRiskGuard g = guard(props(0, 30), repo, MarketTrend.BEAR);

        MarketRiskGuard.RiskDecision d = g.allowEntry(1_000_000, 10_000_000, "전기·전자");

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("총노출 한도");
    }

    @Test
    void 비활성이면_항상_허용() {
        OrderRepository repo = mock(OrderRepository.class);
        RiskProperties disabled = new RiskProperties(false, 100, 60, 30, 0.5, 3.0, 3, 7.0, 2.0, 15.0, 3.0, 0, 30);
        MarketRiskGuard g = guard(disabled, repo, MarketTrend.BEAR);

        assertThat(g.allowEntry(999_999_999, 1, "전기·전자").allowed()).isTrue();
    }

    @Test
    void 손절_하한이하면_hit() {
        // 손절 7% → 매수가 10000 대비 9300(-7%) 이하면 hit
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 0, 0, 7.0, 2.0, 15.0, 3.0, 0, 30);
        MarketRiskGuard g = guard(p, mock(OrderRepository.class), MarketTrend.BULL);

        assertThat(g.catastrophicStopPct()).isEqualTo(7.0);
        assertThat(g.catastrophicStopHit(10_000, 9_300)).isTrue();    // 정확히 -7%
        assertThat(g.catastrophicStopHit(10_000, 9_400)).isFalse();   // -6%
        assertThat(g.catastrophicStopHit(10_000, 0)).isFalse();       // 가격 미상
    }

    @Test
    void 손절_비활성이면_hit안함() {
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 0, 0, 0, 2.0, 15.0, 3.0, 0, 30);   // 손절 0=비활성
        MarketRiskGuard g = guard(p, mock(OrderRepository.class), MarketTrend.BULL);

        assertThat(g.catastrophicStopPct()).isEqualTo(0.0);
        assertThat(g.catastrophicStopHit(10_000, 1_000)).isFalse();   // -90%여도 비활성이면 false
    }

    @Test
    void 서킷_저점대비_반등하면_재개_그리고_새급락시_재발동() {
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.overallTrend()).thenReturn(MarketTrend.BEAR);
        when(regime.all()).thenReturn(List.of());
        KisApiClient kis = mock(KisApiClient.class);
        // KOSPI: -6 → -6 → -4(저점대비 +2%p 반등) → -4(재무장 유지) → -8(새 급락)
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-6.0, -6.0, -4.0, -4.0, -8.0);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(0.0);   // 코스닥 평온
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 3.0, 0, 0, 2.0, 15.0, 3.0, 0, 30);   // crashHalt 3, rebound 2
        MarketRiskGuard g = new MarketRiskGuard(regime, kis, mock(OrderRepository.class), p);

        assertThat(g.isRiskOff().off()).isTrue();    // -6% 급락 → 발동
        assertThat(g.isRiskOff().off()).isTrue();    // -6% 유지 → 발동 유지
        assertThat(g.isRiskOff().off()).isFalse();   // -4% = 저점(-6) 대비 +2%p 반등 → 재개
        assertThat(g.isRiskOff().off()).isFalse();   // -4% 유지 → 재무장, 재발동 안 함(깜빡임 방지)
        assertThat(g.isRiskOff().off()).isTrue();    // -8% = 재무장 고점(-4) 대비 4%p 새 급락 → 재발동
    }

    @Test
    void 서킷_반등미달이어도_크래시레벨_위로_회복하면_레벨기반_재개() {
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.overallTrend()).thenReturn(MarketTrend.BEAR);
        when(regime.all()).thenReturn(List.of());
        KisApiClient kis = mock(KisApiClient.class);
        // KOSDAQ: -3.16(발동) → -2.71(저점대비 +0.45%p 반등<2 이지만 -3% 위로 회복 → 레벨 재개)
        when(kis.fetchIndexChangeRate("0001")).thenReturn(0.0);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-3.16, -2.71);
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 3.0, 0, 0, 2.0, 15.0, 3.0, 0, 30);
        MarketRiskGuard g = new MarketRiskGuard(regime, kis, mock(OrderRepository.class), p);

        assertThat(g.isRiskOff().off()).isTrue();    // -3.16% 발동
        assertThat(g.isRiskOff().off()).isFalse();   // -2.71% > -3% → 레벨 기반 재개 (반등 0.45%p<2였어도)
    }

    @Test
    void 서킷_반등이_임계미만이고_레벨도_미회복이면_유지() {
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.overallTrend()).thenReturn(MarketTrend.BEAR);
        when(regime.all()).thenReturn(List.of());
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("0001")).thenReturn(-6.0, -5.0);   // 저점대비 +1%p < 2 → 유지
        when(kis.fetchIndexChangeRate("1001")).thenReturn(0.0);
        RiskProperties p = new RiskProperties(true, 100, 60, 30, 0.5, 3.0, 0, 0, 2.0, 15.0, 3.0, 0, 30);
        MarketRiskGuard g = new MarketRiskGuard(regime, kis, mock(OrderRepository.class), p);

        assertThat(g.isRiskOff().off()).isTrue();    // -6% 발동
        assertThat(g.isRiskOff().off()).isTrue();    // -5%는 저점대비 +1%p라 아직 재개 안 함
    }
    @Test
    void 약세장_차단이_켜지면_해당_시장_BEAR에서_신규진입_거부() {
        RiskProperties p = props(0, 60);
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.overallTrend()).thenReturn(MarketTrend.NEUTRAL);
        when(regime.trendOf("KOSDAQ")).thenReturn(MarketTrend.BEAR);
        when(regime.trendOf("KOSPI")).thenReturn(MarketTrend.NEUTRAL);
        when(regime.all()).thenReturn(List.of());
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findOpenBuyPositions()).thenReturn(List.of());
        MarketRiskGuard g = new MarketRiskGuard(regime, mock(KisApiClient.class), repo, p);

        g.setBearBlockEnabled(true);
        assertThat(g.allowEntry(100_000, 10_000_000, null, "KOSDAQ").allowed()).isFalse();   // KOSDAQ BEAR → 차단
        assertThat(g.allowEntry(100_000, 10_000_000, null, "KOSDAQ").reason()).contains("bear-block");
        assertThat(g.allowEntry(100_000, 10_000_000, null, "KOSPI").allowed()).isTrue();     // KOSPI 중립 → 통과

        g.setBearBlockEnabled(false);
        assertThat(g.allowEntry(100_000, 10_000_000, null, "KOSDAQ").allowed()).isTrue();    // off = 종전 동작
    }

}
