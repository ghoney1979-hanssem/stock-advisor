package com.stockadvisor.market.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체결강도 추출(순수) — 최신 유효 행의 tday_rltv. 진입 태깅의 근거 값.
 */
class KisCcnlResponseTest {

    private KisCcnlResponse resp(List<KisCcnlResponse.Ccnl> out) {
        return new KisCcnlResponse("0", "ok", out);
    }

    @Test
    void 최신_행의_체결강도() {
        var r = resp(List.of(
                new KisCcnlResponse.Ccnl("130501", "1200", "143.25"),   // 최신
                new KisCcnlResponse.Ccnl("130455", "800", "141.10")));
        assertThat(r.latestStrength()).isEqualTo(143.25);
    }

    @Test
    void 파싱불가_행은_건너뛰고_다음_행() {
        var r = resp(List.of(
                new KisCcnlResponse.Ccnl("130501", "1200", ""),
                new KisCcnlResponse.Ccnl("130455", "800", "98.7")));
        assertThat(r.latestStrength()).isEqualTo(98.7);
    }

    @Test
    void 유효행_없으면_null() {
        assertThat(resp(List.of()).latestStrength()).isNull();
        assertThat(new KisCcnlResponse("0", "ok", null).latestStrength()).isNull();
    }
}
