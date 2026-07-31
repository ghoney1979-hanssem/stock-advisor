package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 D(지수상대 역추세) 진입 판정 검증.
 * minGap=2.0, maxDrop=12.0, minScore=40, requireRebound=true, meanReversionMinDrop=3.0 기준.
 */
class IndexRelativeStrategyTest {

    private final IndexRelativeStrategy strategy = new IndexRelativeStrategy(props(false));
    private final IndexRelativeStrategy flowStrategy = new IndexRelativeStrategy(props(true));

    /** D 관련 값만 의미, 나머지는 임의 유효값. requireRisingFlow만 변주. */
    private static SignalProperties props(boolean requireRisingFlow) {
        return new SignalProperties(
                20, 2.0, 1.5, 40.0, Duration.ofHours(1),
                5, 0.3, 1.5,
                0.0, 1.0, 8.0,           // volumeLeading: min=0, max=1, inverse-max=8
                3.0, 12.0, 40.0, true,   // meanReversion*: minDrop=3
                2.0, 12.0, 40.0, true, requireRisingFlow, // indexRelative: minGap=2, maxDrop=12, minScore=40, requireRebound, requireRisingFlow
                20, 40.0, 0.0,           // breakout: lookback=20, minScore=40, buffer=0
                1000, "09:00", "15:20", true);
    }

    /** volumeSpike=true 고정, 반등여부·등락률만 변주. */
    private static SignalResult signal(double changeRate, boolean reboundActive) {
        return new SignalResult(3.0, changeRate, 10_000, 1_000_000, true, false, reboundActive, 0, false, false, false);
    }

    private StrategyContext ctx(double changeRate, Double indexChange, boolean rebound, double score) {
        return new StrategyContext("005930", signal(changeRate, rebound), score, RecommendationType.HOLD, indexChange, false, false);
    }

    /** indexMom30(흐름) 실은 컨텍스트 — 흐름 필터 검증용. */
    private StrategyContext ctxFlow(double changeRate, Double indexChange, double score, Double indexMom30) {
        return new StrategyContext("005930", signal(changeRate, false), score, RecommendationType.HOLD, indexChange, false, false, null, indexMom30);
    }

    @Test
    void 지수대비_과소_절대상승이면_반등불요_진입() {
        // 지수 +3.0%, 종목 +0.5% → 잔차 -2.5 ≤ -2.0. 절대론 상승이라 반등확인 불요.
        assertThat(strategy.rejectReason(ctx(0.5, 3.0, false, 50))).isNull();
        assertThat(strategy.shouldEnter(ctx(0.5, 3.0, false, 50))).isTrue();
    }

    @Test
    void 잔차부족이면_GAP() {
        // 지수 +1.0%, 종목 +0.5% → 잔차 -0.5 > -2.0
        assertThat(strategy.rejectReason(ctx(0.5, 1.0, false, 50))).isEqualTo("GAP");
    }

    @Test
    void 지수미조회면_NO_INDEX() {
        assertThat(strategy.rejectReason(ctx(-5.0, null, true, 50))).isEqualTo("NO_INDEX");
    }

    @Test
    void 절대폭락은_TOO_DEEP() {
        // 종목 -15% (≤ -12) → 잔차 크더라도 폭락 제외
        assertThat(strategy.rejectReason(ctx(-15.0, 0.0, true, 50))).isEqualTo("TOO_DEEP");
    }

    @Test
    void 절대하락_반등없으면_NO_REBOUND() {
        // 지수 0%, 종목 -5% → 잔차 -5 ≤ -2, 절대 -5 ≤ -3(minDrop) 인데 반등없음
        assertThat(strategy.rejectReason(ctx(-5.0, 0.0, false, 50))).isEqualTo("NO_REBOUND");
    }

    @Test
    void 절대하락_반등있으면_진입() {
        assertThat(strategy.rejectReason(ctx(-5.0, 0.0, true, 50))).isNull();
    }

    @Test
    void 점수미달이면_SCORE() {
        // 잔차 충족·반등불요(절대상승)인데 점수 30 < 40
        assertThat(strategy.rejectReason(ctx(0.5, 3.0, false, 30))).isEqualTo("SCORE");
    }

    @Test
    void 흐름필터_켜짐_흐름하락이면_FLOW_DOWN() {
        // 지수 +3.0%, 종목 +0.5% → 잔차 -2.5(진입 조건 통과)인데, 흐름 mom30 = -0.5(흐름↓) → 보류
        assertThat(flowStrategy.rejectReason(ctxFlow(0.5, 3.0, 50, -0.5))).isEqualTo("FLOW_DOWN");
    }

    @Test
    void 흐름필터_켜짐_흐름상승이면_진입() {
        // 동일 후보인데 흐름 mom30 = +0.5(흐름↑) → 진입
        assertThat(flowStrategy.rejectReason(ctxFlow(0.5, 3.0, 50, 0.5))).isNull();
    }

    @Test
    void 흐름필터_켜짐_흐름미산출이면_미적용_진입() {
        // 흐름 mom30 = null(개장 ~30분·조회실패) → degrade open, 흐름 무관 진입
        assertThat(flowStrategy.rejectReason(ctxFlow(0.5, 3.0, 50, null))).isNull();
    }

    @Test
    void 흐름필터_꺼짐이_기본_흐름하락이어도_진입() {
        // requireRisingFlow=false(기본)면 흐름↓여도 FLOW_DOWN 미적용(현행 유지)
        assertThat(strategy.rejectReason(ctxFlow(0.5, 3.0, 50, -0.5))).isNull();
    }
}
