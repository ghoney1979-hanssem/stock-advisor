package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 A 흐름↓ 스킵(2026-08-24) — flow-analysis lag30·lag60 양쪽에서 흐름↓ -1.85%/-1.87%(n=41)
 * vs 흐름↑ -0.35%(n=42). G·J와 같은 방향이나 표본이 훨씬 얇다.
 */
class MomentumStrategyTest {

    @Test
    void 흐름하락이면_FLOW_DOWN() {
        assertThat(MomentumStrategy.flowReject(-0.5, true)).isEqualTo("FLOW_DOWN");
    }

    @Test
    void 흐름상승_보합이면_통과() {
        assertThat(MomentumStrategy.flowReject(0.5, true)).isNull();
        assertThat(MomentumStrategy.flowReject(0.0, true)).isNull();   // 보합은 흐름↑ 버킷(mom30>=0)과 정합
    }

    @Test
    void 흐름미산출이면_degrade_open() {
        // 개장 ~30분·조회실패 — 데이터 실패로 매매를 막지 않는다
        assertThat(MomentumStrategy.flowReject(null, true)).isNull();
    }

    @Test
    void 비활성이면_흐름하락이어도_통과() {
        assertThat(MomentumStrategy.flowReject(-0.5, false)).isNull();
    }

    // ── 분봉 신선도 knob(2026-09-02) — control-analysis NOT_FRESH 차단분이 진입분보다 +0.52%p 나았다 ──

    private StrategyContext ctx(boolean volumeSpike, boolean freshActive, double changeRate) {
        SignalResult sig = new SignalResult(3.0, changeRate, 10_000, 1_000_000,
                volumeSpike, freshActive, false, 0, false, false, false,
                3.0, -12.0, 0.0, 0.0, false, 0.0, false, 9_900, "20260901");
        return new StrategyContext("005930", sig, 50, RecommendationType.HOLD, null, false, false);
    }

    @Test
    void 신선도_요구시엔_freshActive_하나로_판정한다() {
        assertThat(MomentumStrategy.baseReject(ctx(true, true, 2.0), true, 1.5)).isNull();
        assertThat(MomentumStrategy.baseReject(ctx(true, false, 2.0), true, 1.5)).isEqualTo("NOT_FRESH");
    }

    @Test
    void 신선도를_꺼도_거래량급증과_상승은_계속_요구한다() {
        // ⚠️ 핵심 — freshActive는 (분봉신선 AND 급증 AND 상승)이라, 그냥 무시하면 A의 정체성인 '상승'까지 사라진다.
        assertThat(MomentumStrategy.baseReject(ctx(true, false, 2.0), false, 1.5)).isNull();      // 신선도만 해제
        assertThat(MomentumStrategy.baseReject(ctx(false, false, 2.0), false, 1.5)).isEqualTo("NO_VOLUME");
        assertThat(MomentumStrategy.baseReject(ctx(true, false, 0.5), false, 1.5)).isEqualTo("NOT_RISING");
        assertThat(MomentumStrategy.baseReject(ctx(true, false, -3.0), false, 1.5)).isEqualTo("NOT_RISING");
    }

    @Test
    void 상승_경계는_minChangeRate_포함() {
        assertThat(MomentumStrategy.baseReject(ctx(true, false, 1.5), false, 1.5)).isNull();
    }
}
