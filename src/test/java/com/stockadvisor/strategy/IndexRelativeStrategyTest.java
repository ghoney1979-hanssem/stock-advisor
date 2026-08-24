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

    private static final String ALL_REGIMES = "BULL,NEUTRAL,BEAR";   // 종전 동작(제약 없음)

    private final IndexRelativeStrategy strategy = new IndexRelativeStrategy(props(false), ALL_REGIMES);
    private final IndexRelativeStrategy flowStrategy = new IndexRelativeStrategy(props(true), ALL_REGIMES);
    /** prod 설정 — BEAR 제외(2026-08-24). */
    private final IndexRelativeStrategy regimeStrategy = new IndexRelativeStrategy(props(false), "BULL,NEUTRAL");

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

    // ── 국면 제한(2026-08-24) — BEAR edge -0.48%p(대조군 n=1,712) vs BULL +0.62 / NEUTRAL +1.51 ──

    private StrategyContext ctxRegime(String entryTrend) {
        return new StrategyContext("005930", signal(0.5, false), 50, RecommendationType.HOLD,
                3.0, false, false, entryTrend, null);
    }

    @Test
    void 허용국면_밖이면_REGIME_국면으로_보류() {
        assertThat(regimeStrategy.rejectReason(ctxRegime("BEAR"))).isEqualTo("REGIME_BEAR");
    }

    @Test
    void 허용국면_안이면_진입() {
        assertThat(regimeStrategy.rejectReason(ctxRegime("BULL"))).isNull();
        assertThat(regimeStrategy.rejectReason(ctxRegime("NEUTRAL"))).isNull();
    }

    @Test
    void 국면_미상이면_degrade_open으로_진입() {
        // 국면 산출 실패(null)로 매매를 막지 않는다 — K와 동일 원칙
        assertThat(regimeStrategy.rejectReason(ctxRegime(null))).isNull();
    }

    @Test
    void 허용국면_목록이_전체면_종전동작() {
        // 코드 기본값(BULL,NEUTRAL,BEAR) = 제약 없음 → BEAR도 통과(원복 경로 보장)
        assertThat(strategy.rejectReason(ctxRegime("BEAR"))).isNull();
    }

    @Test
    void 국면판정은_다른_D조건을_다_통과한_뒤에_걸린다() {
        // 판정 위치 검증: 잔차 부족(GAP)으로 어차피 탈락할 후보는 REGIME_이 아니라 GAP으로 남아야
        // 기존 사유 통계가 오염되지 않고, REGIME_BEAR 대조군이 "D 조건 다 만족했으나 BEAR"가 된다.
        StrategyContext weakGap = new StrategyContext("005930", signal(2.5, false), 50,
                RecommendationType.HOLD, 3.0, false, false, "BEAR", null);
        assertThat(regimeStrategy.rejectReason(weakGap)).isEqualTo("GAP");
    }
}
