package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 안전 게이트 단위 테스트. 실전 1일차부터 필요한 하드캡들이 실제로 차단하는지 검증.
 */
class PolicyGateTest {

    private static final String TODAY = "20260626";

    private OrderRepository orderRepository;
    private StrategyHoldTimeProvider holdProvider;
    private PolicyGate gate;

    /** enabled=true 기본 정책(제안 디폴트 값). */
    private TradingPolicyProperties enabledPolicy() {
        return new TradingPolicyProperties(true, TradingMode.DRY_RUN,
                10.0, 5, 50_000, 3, "15:20", 60, true, java.util.List.of(), 3, 5, 0);
    }

    // 1주문 한도(maxKrw) 100,000원 가정 — 호출측(OrderService)이 순자산×10%로 산정해 넘기는 값
    private PolicyGate.OrderRequest buy(long qty, long price, LocalTime now) {
        return new PolicyGate.OrderRequest("MEAN_REVERSION_C", "005930", OrderSide.BUY,
                qty, price, 100_000, "MEAN_REVERSION_C:005930:" + TODAY, now);
    }

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        // 진입 규칙 검증은 보유 60분 기준(고정값과 동일) — 적응형 provider 가 60 반환하도록 스텁
        holdProvider = mock(StrategyHoldTimeProvider.class);
        when(holdProvider.holdMinutes(any())).thenReturn(60);
        gate = new PolicyGate(enabledPolicy(), orderRepository, holdProvider, "", "114800,251340");   // 스윙 없음(기존 규칙 그대로)
    }

    @Test
    void 정상_매수는_허용() {
        // 한도 내 1주, 장중, 잔여 한도 충분
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void 마스터스위치_꺼지면_차단() {
        PolicyGate offGate = new PolicyGate(
                new TradingPolicyProperties(false, TradingMode.DRY_RUN, 10.0, 5, 50_000, 3, "15:20", 60, true, java.util.List.of(), 3, 5, 0),
                orderRepository, holdProvider, "", "114800,251340");
        PolicyGate.PolicyDecision d = offGate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("비활성화");
    }

    @Test
    void 킬스위치_ON이면_차단() {
        gate.setKillSwitch(true);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("킬스위치");
    }

    @Test
    void 중복주문_idempotency_차단() {
        when(orderRepository.existsByIdempotencyKeyAndStatusNotIn(
                org.mockito.ArgumentMatchers.eq("MEAN_REVERSION_C:005930:" + TODAY),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("중복");
    }

    @Test
    void 매도는_한도와_무관하게_허용() {
        // 한도들이 다 찼어도 매도(청산)는 허용 — 리스크 축소 방향
        when(orderRepository.countByOrderDateAndSide(TODAY, OrderSide.BUY)).thenReturn(99L);
        when(orderRepository.countOpenPositions()).thenReturn(99L);
        PolicyGate.OrderRequest sell = new PolicyGate.OrderRequest("MEAN_REVERSION_C", "005930",
                OrderSide.SELL, 1, 70_000, 0, "SELL:005930:1", LocalTime.of(15, 20));
        assertThat(gate.evaluate(sell, TODAY).allowed()).isTrue();
    }

    @Test
    void 일주문_금액상한_초과_차단() {
        // 2주 × 70,000 = 140,000 > 100,000
        PolicyGate.PolicyDecision d = gate.evaluate(buy(2, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("1주문 한도 초과");
    }

    @Test
    void 수량또는가격_0이면_차단() {
        PolicyGate.PolicyDecision d = gate.evaluate(buy(0, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("산정 불가");
    }

    @Test
    void 시간기반청산이_장마감_넘기면_신규진입_차단() {
        // 14:30 진입 + 보유 60분 = 15:30 > session-end 15:20 → 차단
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(14, 30)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("신규진입 금지");
    }

    @Test
    void 스윙전략은_장마감_넘겨도_진입허용() {
        // C(스윙)는 오버나잇 보유라 "장마감 초과 청산" 진입금지 규칙 면제 → 14:30 진입(+60분=15:30>15:20)도 허용
        PolicyGate swingGate = new PolicyGate(enabledPolicy(), orderRepository, holdProvider, "MEAN_REVERSION_C", "114800,251340");
        PolicyGate.PolicyDecision d = swingGate.evaluate(buy(1, 70_000, LocalTime.of(14, 30)), TODAY);
        assertThat(d.allowed()).isTrue();   // 비스윙이면 차단됐을 시각
    }

    @Test
    void 인버스는_장마감_넘겨도_진입허용() {
        // 2026-07-20 실측: 적응형 90분이 시간규칙에 적용돼 15:12 I 재진입이 오차단 — 인버스는 시간청산을
        // 안 쓰고(지수조건) sessionEnd 백스톱이 당일청산을 보장하므로 이 규칙 면제. 15:12 진입(+60분>15:20)도 허용.
        PolicyGate.PolicyDecision d = gate.evaluate(new PolicyGate.OrderRequest(
                "INVERSE_INDEX_I", "114800", OrderSide.BUY,
                100, 1_150, 200_000, "INVERSE_INDEX_I:114800:" + TODAY, LocalTime.of(15, 12)), TODAY);
        assertThat(d.allowed()).isTrue();   // 일반주면 차단됐을 시각
    }

    @Test
    void 시간기반청산이_장마감_이내면_늦은시각도_허용() {
        // 14:00 진입 + 보유 60분 = 15:00 ≤ 15:20 → 허용
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(14, 0)), TODAY);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void 적응형_보유시간이_진입규칙에_반영() {
        // 전략 권장 보유시간 120분 → 14:00 진입 + 120분 = 16:00 > 15:20 → 차단
        // (고정 60분 기준이었다면 15:00 ≤ 15:20 으로 허용됐을 시각)
        when(holdProvider.holdMinutes("MEAN_REVERSION_C")).thenReturn(120);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(14, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("보유 120분");
    }

    @Test
    void 일일_주문수_한도_도달시_차단() {
        when(orderRepository.countByOrderDateAndSide(TODAY, OrderSide.BUY)).thenReturn(5L);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("1일 주문 한도");
    }

    @Test
    void 최대_동시보유_도달시_차단() {
        when(orderRepository.countOpenPositions()).thenReturn(3L);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("최대 동시 보유");
    }

    @Test
    void 일일_손실한도_도달시_차단() {
        // 당일 확정손실 -50,000 → 손실액 50,000 ≥ 한도 50,000
        when(orderRepository.sumRealizedPnlByDate(TODAY)).thenReturn(-50_000L);
        PolicyGate.PolicyDecision d = gate.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("일일 손실 한도");
    }

    @org.junit.jupiter.api.Test
    void 인버스는_일일손실한도_면제() {
        // 2026-07-24: H 손실이 한도 소진 → 코스피 −5.9% 폭락일 인버스 재진입 전부 거부된 실측 —
        // 헤지 다리는 하락일에 버는 방향이라 면제. 일반주는 같은 상황에서 여전히 차단(대조 검증).
        org.mockito.Mockito.when(orderRepository.sumRealizedPnlByDate(TODAY)).thenReturn(-79_107L);
        PolicyGate.PolicyDecision inverse = gate.evaluate(new PolicyGate.OrderRequest(
                "INVERSE_INDEX_I", "114800", com.stockadvisor.domain.OrderSide.BUY,
                145, 1_110, 300_000, "INVERSE_INDEX_I:114800:" + TODAY + "#4", LocalTime.of(11, 13)), TODAY);
        assertThat(inverse.allowed()).isTrue();

        PolicyGate.PolicyDecision normal = gate.evaluate(buy(1, 70_000, LocalTime.of(11, 13)), TODAY);
        assertThat(normal.allowed()).isFalse();
        assertThat(normal.reason()).contains("일일 손실 한도");
    }

    @org.junit.jupiter.api.Test
    void 일일손실한도_순자산비율_모드() {
        // pct=10, maxOrderPct=10, maxKrw=100,000 → 순자산 1,000,000 역산 → 한도 100,000원
        TradingPolicyProperties p = new TradingPolicyProperties(true, TradingMode.DRY_RUN,
                10.0, 0, 50_000, 10, "15:20", 60, false, java.util.List.of(), 3, 5, 10.0);
        PolicyGate g = new PolicyGate(p, orderRepository, holdProvider, "", "114800,251340");
        org.mockito.Mockito.when(orderRepository.sumRealizedPnlByDate(TODAY)).thenReturn(-79_107L);
        assertThat(g.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY).allowed()).isTrue();   // 79k < 100k
        org.mockito.Mockito.when(orderRepository.sumRealizedPnlByDate(TODAY)).thenReturn(-120_000L);
        PolicyGate.PolicyDecision d = g.evaluate(buy(1, 70_000, LocalTime.of(10, 0)), TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("100,000");   // 절대액(50,000)이 아니라 비율 한도로 판정
    }
}
