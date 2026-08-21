package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 L(눌림 반전): 20일선 아래 눌림 + 저거래량 + 최근 5일 약세만 진입.
 * 기존 전략들이 사는 것(급증·돌파·갭업)의 거울상이라 <b>볼륨 게이트를 통과하면 안 된다</b>.
 */
class ReversalStrategyTest {

    private final ReversalStrategy s =
            new ReversalStrategy(true, 1.0, 3.0, 10.0, -5.0, 2.0, 5.0, 40.0);

    /** volumeRatio·changeRate·maDistPct·ret5dPct를 지정한 신호. */
    private SignalResult signal(double volumeRatio, double changeRate, double maDistPct, double ret5dPct) {
        return new SignalResult(volumeRatio, changeRate, 10_000, 1_000_000,
                false, false, false, 0, false, false, false,
                3.0, -12.0, ret5dPct, 0.0, false, maDistPct, false, 9_900, "20260820");
    }

    private StrategyContext ctx(double volumeRatio, double changeRate, double maDistPct, double ret5dPct) {
        return ctx(volumeRatio, changeRate, maDistPct, ret5dPct, 50, false);
    }

    private StrategyContext ctx(double volumeRatio, double changeRate, double maDistPct, double ret5dPct,
                                double score, boolean inverse) {
        return new StrategyContext("005930", signal(volumeRatio, changeRate, maDistPct, ret5dPct),
                score, RecommendationType.HOLD, null, inverse, false);
    }

    @Test
    void 눌림_저거래량_약세면_진입() {
        // 20일선 −5% 아래, 거래량 평균의 0.6배, 최근 5일 −8%, 당일 −1%
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, -8.0))).isNull();
    }

    @Test
    void 거래량이_붙으면_VOLUME_UP() {
        // 이 전략의 정체성 — 급증은 나머지 전략의 영역이다
        assertThat(s.rejectReason(ctx(1.5, -1.0, -5.0, -8.0))).isEqualTo("VOLUME_UP");
        assertThat(s.rejectReason(ctx(1.0, -1.0, -5.0, -8.0))).isEqualTo("VOLUME_UP");   // 경계 포함
    }

    @Test
    void 이격_구간을_벗어나면_NOT_PULLBACK_또는_BROKEN_TREND() {
        assertThat(s.rejectReason(ctx(0.6, -1.0, -1.0, -8.0))).isEqualTo("NOT_PULLBACK");   // 얕음
        assertThat(s.rejectReason(ctx(0.6, -1.0, 2.0, -8.0))).isEqualTo("NOT_PULLBACK");    // 20일선 위
        assertThat(s.rejectReason(ctx(0.6, -1.0, -15.0, -8.0))).isEqualTo("BROKEN_TREND");  // 추세붕괴
    }

    @Test
    void 최근_5일이_약세가_아니면_NOT_WEAK() {
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, 3.0))).isEqualTo("NOT_WEAK");
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, -2.0))).isEqualTo("NOT_WEAK");   // -5% 문턱 미달
    }

    @Test
    void 당일_급등추격과_폭락은_각각_CHASING_TOO_DEEP() {
        assertThat(s.rejectReason(ctx(0.6, 3.0, -5.0, -8.0))).isEqualTo("CHASING");
        assertThat(s.rejectReason(ctx(0.6, -7.0, -5.0, -8.0))).isEqualTo("TOO_DEEP");
    }

    @Test
    void 인버스와_점수미달과_비활성은_제외() {
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, -8.0, 50, true))).isEqualTo("INVERSE");
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, -8.0, 30, false))).isEqualTo("SCORE");
        ReversalStrategy off = new ReversalStrategy(false, 1.0, 3.0, 10.0, -5.0, 2.0, 5.0, 40.0);
        assertThat(off.rejectReason(ctx(0.6, -1.0, -5.0, -8.0))).isEqualTo("DISABLED");
    }

    @Test
    void 볼륨게이트를_요구하지_않고_섀도우이며_사전필터가_눌림_저거래량만_통과시킨다() {
        assertThat(s.requiresVolumeSpike()).isFalse();   // ⚠️ 통과하면 전제가 깨진다
        assertThat(s.alerts()).isFalse();                // 실주문 0, Discord 미발송
        assertThat(s.tracksControl()).isFalse();         // 분모는 UniverseSnapshot

        assertThat(s.preScreen("005930", signal(0.6, -1.0, -5.0, -8.0))).isTrue();
        assertThat(s.preScreen("005930", signal(2.0, -1.0, -5.0, -8.0))).isFalse();   // 급증
        assertThat(s.preScreen("005930", signal(0.6, -1.0, 1.0, -8.0))).isFalse();    // 20일선 위
    }
}
