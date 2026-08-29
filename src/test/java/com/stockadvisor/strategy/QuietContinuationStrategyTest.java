package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 M(조용한 추세지속): 저거래량 + MA20 위 완만한 이격 + 5일 소폭 강세 + 당일 소폭 상승만 진입.
 * L의 대칭이며, L과 마찬가지로 <b>볼륨 게이트를 통과하면 안 된다</b>.
 */
class QuietContinuationStrategyTest {

    private final QuietContinuationStrategy s =
            new QuietContinuationStrategy(true, 0.5, 0.0, 3.0, 0.0, 5.0, 0.0, 2.0, 40.0);

    private SignalResult signal(double volumeRatio, double changeRate, double maDistPct, double ret5dPct) {
        return new SignalResult(volumeRatio, changeRate, 10_000, 1_000_000,
                false, false, false, 0, false, false, false,
                3.0, -3.0, ret5dPct, 0.0, false, maDistPct, false, 9_900, "20260828");
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
    void 저거래량_MA위_완만_추세면_진입() {
        // 거래량 평균의 0.3배, MA20 +1.5%, 5일 +2%, 당일 +0.8%
        assertThat(s.rejectReason(ctx(0.3, 0.8, 1.5, 2.0))).isNull();
    }

    @Test
    void 거래량이_붙으면_VOLUME_UP() {
        assertThat(s.rejectReason(ctx(0.5, 0.8, 1.5, 2.0))).isEqualTo("VOLUME_UP");   // 경계 포함
        assertThat(s.rejectReason(ctx(2.5, 0.8, 1.5, 2.0))).isEqualTo("VOLUME_UP");
    }

    @Test
    void 이격_밴드를_벗어나면_BELOW_MA_또는_EXTENDED() {
        assertThat(s.rejectReason(ctx(0.3, 0.8, -0.5, 2.0))).isEqualTo("BELOW_MA");   // L의 영역
        assertThat(s.rejectReason(ctx(0.3, 0.8, 4.0, 2.0))).isEqualTo("EXTENDED");    // 이격 과열
    }

    @Test
    void 오일_추세_밴드를_벗어나면_WEAK_5D_또는_RAN_5D() {
        assertThat(s.rejectReason(ctx(0.3, 0.8, 1.5, -1.0))).isEqualTo("WEAK_5D");
        assertThat(s.rejectReason(ctx(0.3, 0.8, 1.5, 7.0))).isEqualTo("RAN_5D");
    }

    @Test
    void 당일_하락중이거나_급등이면_DOWN_DAY_또는_CHASING() {
        assertThat(s.rejectReason(ctx(0.3, -0.3, 1.5, 2.0))).isEqualTo("DOWN_DAY");
        assertThat(s.rejectReason(ctx(0.3, 3.0, 1.5, 2.0))).isEqualTo("CHASING");
    }

    @Test
    void 점수_미달과_인버스는_제외() {
        assertThat(s.rejectReason(ctx(0.3, 0.8, 1.5, 2.0, 30, false))).isEqualTo("SCORE");
        assertThat(s.rejectReason(ctx(0.3, 0.8, 1.5, 2.0, 50, true))).isEqualTo("INVERSE");
    }

    @Test
    void 볼륨게이트_우회와_preScreen은_정체성_조건만_본다() {
        assertThat(s.requiresVolumeSpike()).isFalse();
        assertThat(s.preScreen("005930", signal(0.3, 0.8, 1.5, 2.0))).isTrue();
        assertThat(s.preScreen("005930", signal(0.3, -5.0, 1.5, -9.0))).isTrue();    // 판별 축은 preScreen에 없다
        assertThat(s.preScreen("005930", signal(0.8, 0.8, 1.5, 2.0))).isFalse();     // 거래량
        assertThat(s.preScreen("005930", signal(0.3, 0.8, -1.0, 2.0))).isFalse();    // MA 아래
        assertThat(s.alerts()).isFalse();
        assertThat(s.tracksControl()).isFalse();
    }
}
