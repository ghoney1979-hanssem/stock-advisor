package com.stockadvisor.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인버스 control→entry 승격: 대조군 행이 진입으로 전환되며 매수가·상태가 갱신되는지 검증.
 */
class TradeOutcomePromotionTest {

    @Test
    void control로_기록된_행이_승격되면_진입이_되고_매수가_갱신() {
        TradeOutcome o = new TradeOutcome("VOLUME_LEADING_B", null, "251340", "20260707", 2475);
        o.markControl("DIRECTION_DOWN");   // 오전: 하락이라 대조군
        assertThat(o.isControl()).isTrue();
        assertThat(o.getRejectReason()).isEqualTo("DIRECTION_DOWN");

        o.promoteFromControl(2600);        // 오후: 급등 전환 → 현재가로 진입 승격

        assertThat(o.isControl()).isFalse();
        assertThat(o.getRejectReason()).isNull();
        assertThat(o.getBuyPrice()).isEqualTo(2600);
    }
}
