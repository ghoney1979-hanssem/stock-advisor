package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 가치 축 순수 계산 — PBR·이익수익률·주식수 파싱. */
class ValueSweepTest {

    @Test
    void PBR은_시총_나누기_자본이고_자본잠식은_null() {
        assertThat(ValueSweepService.valuation(2_000.0, 1_000L, true)).isCloseTo(2.0, within(1e-9));
        assertThat(ValueSweepService.valuation(2_000.0, 0L, true)).isNull();     // 자본 0
        assertThat(ValueSweepService.valuation(2_000.0, -500L, true)).isNull();  // 자본잠식 — 분모 음수면 순위가 뒤집힌다
        assertThat(ValueSweepService.valuation(0.0, 1_000L, true)).isNull();
    }

    @Test
    void 이익수익률은_적자를_음수로_남긴다() {
        assertThat(ValueSweepService.valuation(10_000.0, 500L, false)).isCloseTo(5.0, within(1e-9));
        assertThat(ValueSweepService.valuation(10_000.0, -500L, false)).isCloseTo(-5.0, within(1e-9));  // 적자는 자연히 하위
    }

    @Test
    void KIS_숫자문자열_파싱() {
        assertThat(ShareInfoBackfillService.parseLong("5,969,782,550")).isEqualTo(5_969_782_550L);
        assertThat(ShareInfoBackfillService.parseLong("100")).isEqualTo(100L);
        assertThat(ShareInfoBackfillService.parseLong("0")).isNull();      // 무액면·미제공은 null
        assertThat(ShareInfoBackfillService.parseLong("")).isNull();
        assertThat(ShareInfoBackfillService.parseLong(null)).isNull();
    }
    @Test
    void 주식수_복원은_현_상장주식수를_자본금_변화로_스케일한다() {
        // 삼성전자형: 자본금÷액면가(8.98B)는 이익소각·우선주로 부풀지만, 상장 5.85B × (자본금 변화 1.0) = 5.85B
        assertThat(ValueSweepService.reconstructShares(5_846_278_608L, 897_514_000_000L, 897_514_000_000L, 100L))
                .isEqualTo(5_846_278_608L);
        // 과거 자본금이 절반이었으면(그 뒤 유상증자) 주식수도 절반
        assertThat(ValueSweepService.reconstructShares(1_000L, 200L, 100L, 5000L)).isEqualTo(500L);
        // 상장주식수 미상 → 자본금÷액면가 fallback
        assertThat(ValueSweepService.reconstructShares(null, null, 1_000_000L, 500L)).isEqualTo(2_000L);
        assertThat(ValueSweepService.reconstructShares(null, null, 1_000_000L, null)).isNull();
    }

}
