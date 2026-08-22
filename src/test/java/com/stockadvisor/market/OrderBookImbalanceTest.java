package com.stockadvisor.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 호가 불균형(order book imbalance) 순수 계산 — 진입 태깅(entry_obi1/obi5)의 근거 값.
 * KIS 없이 검증된다(사다리만 주면 결과가 결정된다).
 */
class OrderBookImbalanceTest {

    private KisApiClient.Level lv(long price, long qty) {
        return new KisApiClient.Level(price, qty);
    }

    /** 매도 5단계(가격↑) / 매수 5단계(가격↓) — 잔량만 주면 되는 헬퍼. */
    private KisApiClient.OrderBook book(long[] askQtys, long[] bidQtys) {
        List<KisApiClient.Level> asks = new java.util.ArrayList<>();
        List<KisApiClient.Level> bids = new java.util.ArrayList<>();
        for (int i = 0; i < askQtys.length; i++) asks.add(lv(10_050 + i * 50L, askQtys[i]));
        for (int i = 0; i < bidQtys.length; i++) bids.add(lv(10_000 - i * 50L, bidQtys[i]));
        return new KisApiClient.OrderBook(bids.isEmpty() ? 0 : bids.get(0).price(), asks, bids);
    }

    @Test
    void 매수잔량이_많으면_양수_매도잔량이_많으면_음수() {
        // 1호가: 매수 300 vs 매도 100 → (300-100)/400 = +50%
        var buyPressure = book(new long[]{100, 100}, new long[]{300, 100});
        assertThat(buyPressure.imbalancePct(1)).isEqualTo(50.0);

        var sellPressure = book(new long[]{300, 100}, new long[]{100, 100});
        assertThat(sellPressure.imbalancePct(1)).isEqualTo(-50.0);
    }

    @Test
    void 양측_잔량이_같으면_0() {
        var balanced = book(new long[]{200, 200}, new long[]{200, 200});
        assertThat(balanced.imbalancePct(1)).isEqualTo(0.0);
        assertThat(balanced.imbalancePct(5)).isEqualTo(0.0);
    }

    @Test
    void 다단계는_상위N단계_잔량을_누적한다() {
        // 1호가만 보면 매도 우위(-50%)지만, 5호가 깊이로는 매수 우위 — obi1과 obi5가 갈리는 실제 상황.
        var ob = book(new long[]{300, 100, 100, 100, 100}, new long[]{100, 400, 400, 400, 400});
        assertThat(ob.imbalancePct(1)).isEqualTo(-50.0);
        // 매도 700 vs 매수 1700 → (1700-700)/2400 = +41.67%
        assertThat(ob.imbalancePct(5)).isEqualTo(1000.0 / 2400 * 100);
    }

    @Test
    void 요청단계가_수집분보다_많으면_있는_만큼만_합산() {
        var ob = book(new long[]{100, 100}, new long[]{300, 100});
        // 2단계뿐이므로 5 요청 = 2단계 합산: 매도 200 vs 매수 400
        assertThat(ob.imbalancePct(5)).isEqualTo(ob.imbalancePct(2));
    }

    @Test
    void 한쪽_사다리가_비면_0이_아니라_null() {
        // 장전·거래정지 등 — 0(균형)으로 오해되면 분석에서 '균형 bin'을 오염시킨다.
        var noBids = new KisApiClient.OrderBook(0, List.of(lv(10_050, 100)), List.of());
        assertThat(noBids.imbalancePct(5)).isNull();

        var noAsks = new KisApiClient.OrderBook(10_000, List.of(), List.of(lv(10_000, 100)));
        assertThat(noAsks.imbalancePct(5)).isNull();
    }

    @Test
    void 호환생성자는_bids가_비어_불균형_미산출() {
        // 기존 2인자 호출부(PositionSizer 시장충격 캡)는 매도측만 쓰므로 불균형은 null이어야 한다.
        var ob = new KisApiClient.OrderBook(10_000, List.of(lv(10_050, 100)));
        assertThat(ob.bids()).isEmpty();
        assertThat(ob.imbalancePct(1)).isNull();
        assertThat(ob.spread()).isNotNull();   // 스프레드는 그대로 산출
    }

    @Test
    void 교차호가나_결측이면_스프레드는_null() {
        var crossed = new KisApiClient.OrderBook(10_100, List.of(lv(10_050, 100)), List.of(lv(10_100, 100)));
        assertThat(crossed.spread()).isNull();   // 매도 < 매수 = 무효
    }
}
