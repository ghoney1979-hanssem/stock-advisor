package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 N(깊은 눌림): L의 밴드 바로 바깥(이격 −10~−25)만 대상.
 *
 * <p>핵심 검증은 <b>L과 밴드만 다르고 나머지 조건은 같다</b>는 것 — 그래야 두 전략의 성과 차이가
 * "L의 밴드를 넓혀야 하나"의 답이 된다. 밴드 경계에서 L과 N이 정확히 맞물리는지도 함께 본다.</p>
 */
class DeepPullbackStrategyTest {

    private final DeepPullbackStrategy s =
            new DeepPullbackStrategy(true, 1.0, 10.0, 25.0, -5.0, 2.0, 5.0, 40.0);

    /** 같은 파라미터의 L — 밴드 경계가 맞물리는지 대조용. */
    private final ReversalStrategy l =
            new ReversalStrategy(true, 1.0, 3.0, 10.0, -5.0, 2.0, 5.0, 40.0);

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
    void 깊은눌림_저거래량_약세면_진입() {
        // 20일선 −15% 아래, 거래량 평균의 0.6배, 최근 5일 −8%, 당일 −1%
        assertThat(s.rejectReason(ctx(0.6, -1.0, -15.0, -8.0))).isNull();
    }

    @Test
    void L의_밴드는_대상이_아니다() {
        // −5%는 L의 영역 → N은 TOO_SHALLOW로 제외(중복 진입 방지)
        assertThat(s.rejectReason(ctx(0.6, -1.0, -5.0, -8.0))).isEqualTo("TOO_SHALLOW");
    }

    @Test
    void 두_전략의_밴드가_경계에서_맞물린다() {
        // 이격 −10 근처: 한쪽만 받아야 하고 사이에 빈 구간이 없어야 한다.
        // −9.9%는 L만, −10.1%는 N만.
        assertThat(l.rejectReason(ctx(0.6, -1.0, -9.9, -8.0))).isNull();
        assertThat(s.rejectReason(ctx(0.6, -1.0, -9.9, -8.0))).isEqualTo("TOO_SHALLOW");

        assertThat(l.rejectReason(ctx(0.6, -1.0, -10.1, -8.0))).isEqualTo("BROKEN_TREND");
        assertThat(s.rejectReason(ctx(0.6, -1.0, -10.1, -8.0))).isNull();
    }

    @Test
    void 구조적_붕괴는_제외() {
        // −30%는 "되돌릴 하락"이 아니라 관리종목·연속 하한가 계열 → COLLAPSED
        assertThat(s.rejectReason(ctx(0.6, -1.0, -30.0, -8.0))).isEqualTo("COLLAPSED");
    }

    @Test
    void 거래량_급증이면_정체성_위배로_제외() {
        // 볼륨 게이트를 통과하는 종목은 이 계열의 전제(관심받지 않은 종목)가 깨진다
        assertThat(s.rejectReason(ctx(1.5, -1.0, -15.0, -8.0))).isEqualTo("VOLUME_UP");
        assertThat(s.requiresVolumeSpike()).isFalse();
    }

    @Test
    void 판별사유_최근5일_강세_당일급등_폭락_저점수() {
        assertThat(s.rejectReason(ctx(0.6, -1.0, -15.0, 3.0))).isEqualTo("NOT_WEAK");    // 5일 +3% = 약세 아님
        assertThat(s.rejectReason(ctx(0.6, 5.0, -15.0, -8.0))).isEqualTo("CHASING");     // 당일 +5% 추격
        assertThat(s.rejectReason(ctx(0.6, -7.0, -15.0, -8.0))).isEqualTo("TOO_DEEP");   // 당일 −7% 칼날
        assertThat(s.rejectReason(ctx(0.6, -1.0, -15.0, -8.0, 30, false))).isEqualTo("SCORE");
    }

    @Test
    void 인버스와_비활성은_제외() {
        assertThat(s.rejectReason(ctx(0.6, -1.0, -15.0, -8.0, 50, true))).isEqualTo("INVERSE");

        DeepPullbackStrategy off = new DeepPullbackStrategy(false, 1.0, 10.0, 25.0, -5.0, 2.0, 5.0, 40.0);
        assertThat(off.rejectReason(ctx(0.6, -1.0, -15.0, -8.0))).isEqualTo("DISABLED");
    }

    @Test
    void preScreen은_깊은눌림_저거래량만_통과() {
        assertThat(s.preScreen("005930", signal(0.6, -1.0, -15.0, -8.0))).isTrue();
        assertThat(s.preScreen("005930", signal(0.6, -1.0, -5.0, -8.0))).isFalse();   // L 영역
        assertThat(s.preScreen("005930", signal(0.6, -1.0, -30.0, -8.0))).isFalse();  // 붕괴 구간
        assertThat(s.preScreen("005930", signal(1.5, -1.0, -15.0, -8.0))).isFalse();  // 급증
    }

    @Test
    void 섀도우_기본값_확인() {
        assertThat(s.alerts()).isFalse();        // Discord 미발송
        assertThat(s.tracksControl()).isFalse(); // 일괄 기록 대신 판별사유만 강제 기록(deepPullbackControl)
        assertThat(s.name()).isEqualTo("DEEP_PULLBACK_N");
    }
}
