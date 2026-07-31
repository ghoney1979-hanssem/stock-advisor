package com.stockadvisor.service;

import com.stockadvisor.config.properties.ExecutionCostProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 체결비용 모델: KRX 호가단위 기반 스프레드, 거래대금 유동성 필터.
 */
class ExecutionCostModelTest {

    private ExecutionCostModel model(boolean enabled, double spreadTicks, double base,
                                     long minTurnover, double maxSlip) {
        return new ExecutionCostModel(
                new ExecutionCostProperties(enabled, spreadTicks, base, minTurnover, maxSlip, 0));
    }

    private ExecutionCostModel impactModel(double maxImpactPct) {
        return new ExecutionCostModel(new ExecutionCostProperties(true, 1, 0, 0, 999, maxImpactPct));
    }

    private com.stockadvisor.market.KisApiClient.Level lv(long price, long qty) {
        return new com.stockadvisor.market.KisApiClient.Level(price, qty);
    }

    @Test
    void KRX_호가단위_가격대별() {
        assertThat(ExecutionCostModel.tickSize(1_500)).isEqualTo(1);
        assertThat(ExecutionCostModel.tickSize(3_000)).isEqualTo(5);
        assertThat(ExecutionCostModel.tickSize(10_000)).isEqualTo(10);
        assertThat(ExecutionCostModel.tickSize(30_000)).isEqualTo(50);
        assertThat(ExecutionCostModel.tickSize(70_000)).isEqualTo(100);
        assertThat(ExecutionCostModel.tickSize(300_000)).isEqualTo(500);
        assertThat(ExecutionCostModel.tickSize(800_000)).isEqualTo(1_000);
    }

    @Test
    void 호가단위_스냅_격자밖가격정렬() {
        // 실측 버그: 006110 매도 33,375(2만~5만 tick 50, ÷50 아님) → KIS "호가단위 오류" 거부
        assertThat(ExecutionCostModel.snapToTick(33_375, false)).isEqualTo(33_350);   // 매도 내림
        assertThat(ExecutionCostModel.snapToTick(33_375, true)).isEqualTo(33_400);    // 매수 올림
        // 이미 격자에 맞으면 그대로(매수·매도 무관)
        assertThat(ExecutionCostModel.snapToTick(34_100, true)).isEqualTo(34_100);
        assertThat(ExecutionCostModel.snapToTick(34_100, false)).isEqualTo(34_100);
        // 가격대별 tick 적용: 3,000(tick5), 10,000(tick10), 70,000(tick100)
        assertThat(ExecutionCostModel.snapToTick(3_003, false)).isEqualTo(3_000);
        assertThat(ExecutionCostModel.snapToTick(3_003, true)).isEqualTo(3_005);
        assertThat(ExecutionCostModel.snapToTick(10_007, true)).isEqualTo(10_010);
        assertThat(ExecutionCostModel.snapToTick(70_050, false)).isEqualTo(70_000);
        // 밴드 경계 올림도 KRX 유효값(50,000은 ÷100 유효)
        assertThat(ExecutionCostModel.snapToTick(49_970, true)).isEqualTo(50_000);
        assertThat(ExecutionCostModel.snapToTick(0, true)).isEqualTo(0);              // 0 방어
    }

    @Test
    void 슬리피지_저가주가_고가주보다_큼() {
        ExecutionCostModel m = model(true, 1.0, 0.0, 0, 999);
        // 3000원: tick 5 → 5/3000×100 = 0.167%. 70000원: tick 100 → 100/70000×100 = 0.143%.
        double cheap = m.estimateRoundTripSlippagePct(3_000);
        double pricey = m.estimateRoundTripSlippagePct(70_000);
        assertThat(cheap).isCloseTo(0.1667, within(0.001));
        assertThat(pricey).isCloseTo(0.1429, within(0.001));
        assertThat(cheap).isGreaterThan(pricey);   // 저가주 스프레드 비중↑
    }

    @Test
    void base_슬리피지_가산() {
        ExecutionCostModel m = model(true, 1.0, 0.1, 0, 999);   // +0.1% 추가
        assertThat(m.estimateRoundTripSlippagePct(70_000)).isCloseTo(0.2429, within(0.001));
    }

    @Test
    void 비활성이면_슬리피지0_항상거래가능() {
        ExecutionCostModel m = model(false, 1.0, 0.5, 1_000_000_000L, 0.1);
        assertThat(m.estimateRoundTripSlippagePct(3_000)).isEqualTo(0.0);
        assertThat(m.tradable(3_000, 0)).isTrue();   // 거래대금 0이어도 비활성이면 통과
    }

    @Test
    void 거래대금_하한미달이면_거래불가() {
        ExecutionCostModel m = model(true, 1.0, 0.0, 500_000_000L, 1.0);
        assertThat(m.tradable(10_000, 100_000_000L)).isFalse();   // 1억 < 5억
        assertThat(m.tradable(10_000, 600_000_000L)).isTrue();    // 6억 ≥ 5억
    }

    @Test
    void 슬리피지_상한초과면_거래불가() {
        // spread-ticks 큰 소형주 가정: 1500원 tick1, spreadTicks 10 → 10×1/1500×100 = 0.667%
        ExecutionCostModel m = model(true, 10.0, 0.0, 0, 0.5);   // 상한 0.5%
        assertThat(m.estimateRoundTripSlippagePct(1_500)).isGreaterThan(0.5);
        assertThat(m.tradable(1_500, 10_000_000_000L)).isFalse();   // 거래대금 충분해도 슬리피지 과대
    }

    @Test
    void 거래대금_계산() {
        ExecutionCostModel m = model(true, 1.0, 0.0, 0, 999);
        assertThat(m.turnoverKrw(1_000, 50_000)).isEqualTo(50_000_000L);
    }

    @Test
    void 실측스프레드_왕복슬리피지() {
        ExecutionCostModel m = model(true, 1.0, 0.0, 0, 999);
        // 매도 10050 / 매수 9950 → 중간가 10000, 스프레드 100 → 1.0%
        assertThat(m.roundTripSlippagePctFromSpread(10_050, 9_950)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void 실측스프레드_base가산_무효호가는null() {
        ExecutionCostModel m = model(true, 1.0, 0.1, 0, 999);
        assertThat(m.roundTripSlippagePctFromSpread(10_050, 9_950)).isCloseTo(1.1, within(0.001));  // +0.1 base
        assertThat(m.roundTripSlippagePctFromSpread(9_900, 10_000)).isNull();   // ask<bid 무효
        assertThat(m.roundTripSlippagePctFromSpread(0, 9_950)).isNull();        // 호가 없음
    }

    @Test
    void 비활성이면_실측스프레드도_null() {
        ExecutionCostModel m = model(false, 1.0, 0.0, 0, 999);
        assertThat(m.roundTripSlippagePctFromSpread(10_050, 9_950)).isNull();
    }

    @Test
    void tradable_외부슬리피지_기준() {
        ExecutionCostModel m = model(true, 1.0, 0.0, 500_000_000L, 1.0);
        assertThat(m.tradable(600_000_000L, 0.8)).isTrue();    // 거래대금 충분 + 슬리피지 0.8% ≤ 1%
        assertThat(m.tradable(600_000_000L, 1.5)).isFalse();   // 슬리피지 1.5% > 1%
        assertThat(m.tradable(100_000_000L, 0.1)).isFalse();   // 거래대금 미달
    }

    @Test
    void 시장충격_밴드내_잔량합() {
        // best ask 10000, 충격 0.5% → 밴드 10050. 10000(100)+10050(200)=300, 10100(300) 제외
        ExecutionCostModel m = impactModel(0.5);
        long cap = m.maxSharesWithinImpact(List.of(lv(10_000, 100), lv(10_050, 200), lv(10_100, 300)));
        assertThat(cap).isEqualTo(300);
    }

    @Test
    void 시장충격_pct_walk_VWAP() {
        ExecutionCostModel m = impactModel(0.5);
        // 250주 = 100@10000 + 150@10050 → VWAP 10030 → 충격 0.30%
        assertThat(m.marketImpactPct(List.of(lv(10_000, 100), lv(10_050, 200), lv(10_100, 300)), 250))
                .isCloseTo(0.30, within(0.001));
        // 잔량(600) 초과 1000주 → 전량체결 불가 null
        assertThat(m.marketImpactPct(List.of(lv(10_000, 100), lv(10_050, 200), lv(10_100, 300)), 1_000)).isNull();
    }

    @Test
    void 시장충격_비활성이면_캡_무한대처럼_미적용() {
        ExecutionCostModel m = impactModel(0);   // 0 → impactEnabled false
        assertThat(m.impactEnabled()).isFalse();
    }
}
