package com.stockadvisor.strategy;

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
}
