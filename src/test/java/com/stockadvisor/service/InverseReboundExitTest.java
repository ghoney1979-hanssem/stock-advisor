package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인버스 반등 청산 판정 — 모멘텀 반전 AND 장중 저점 대비 레벨 회복을 함께 요구(2026-08-18).
 *
 * <p>배경(실측): KOSDAQ −3.52%(장중저점 −4.23%)일에 청산 6건의 사유가 전부 {@code mom30 +0.31~+0.48%}였고,
 * 251340이 2,335→2,400으로 오르는 내내 7왕복 −4,521원(비용만 지불)을 냈다. 임계의 낙폭 비례 상향
 * (rebound-day-scale)은 '지금 등락률'(청산 시점 −2%대)을 기준으로 삼아 기저 0.30%에 머물러 작동하지 않았다.</p>
 */
class InverseReboundExitTest {

    private static final double THR = 0.30;      // 기저 반등 임계
    private static final double MIN_RECOVERY = 1.0;   // 요구 회복폭(%p)

    @Test
    void 모멘텀_반전이어도_저점대비_회복이_없으면_보유() {
        // 8/18 13:33 재현: mom30 +0.31%지만 지수 −2.20%, 당일저점 −2.50% → 회복 0.30%p < 1.0%p
        assertThat(PositionExitService.inverseReboundExit(0.31, THR, -2.20, -2.50, MIN_RECOVERY)).isFalse();
        // 같은 날 14:00: mom30 +0.48%로 더 커져도 레벨이 안 올라왔으면 여전히 보유
        assertThat(PositionExitService.inverseReboundExit(0.48, THR, -2.20, -2.50, MIN_RECOVERY)).isFalse();
    }

    @Test
    void 저점대비_충분히_회복했으면_청산() {
        // 지수가 저점 −4.23%에서 −3.00%로 1.23%p 회복 + 모멘텀 반전 → 약세 명제 소멸
        assertThat(PositionExitService.inverseReboundExit(0.35, THR, -3.00, -4.23, MIN_RECOVERY)).isTrue();
    }

    @Test
    void 모멘텀_미달이면_회복폭과_무관하게_보유() {
        assertThat(PositionExitService.inverseReboundExit(0.10, THR, -1.00, -4.23, MIN_RECOVERY)).isFalse();
    }

    @Test
    void 비활성이거나_지수_미상이면_모멘텀만으로_판정_degrade() {
        assertThat(PositionExitService.inverseReboundExit(0.31, THR, -2.20, -2.50, 0)).isTrue();     // 0=비활성
        assertThat(PositionExitService.inverseReboundExit(0.31, THR, null, -2.50, MIN_RECOVERY)).isTrue();  // 등락률 미상
        assertThat(PositionExitService.inverseReboundExit(0.31, THR, -2.20, null, MIN_RECOVERY)).isTrue();  // 저점 미상
    }

    @Test
    void 모멘텀_미산출이면_보유_개장직후_mom30_없음() {
        assertThat(PositionExitService.inverseReboundExit(null, THR, -3.00, -4.23, MIN_RECOVERY)).isFalse();
    }
}
