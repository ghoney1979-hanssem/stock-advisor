package com.stockadvisor.market.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수급 비중 추출(순수) — 소급 태깅의 근거 값.
 *
 * <p>여기서 가장 중요한 건 <b>기준일이 진입일보다 반드시 앞선다</b>는 것이다.
 * 소급 태깅은 미래를 보기가 너무 쉬워서, 당일 행을 집으면 feature 전체가 무효가 된다.</p>
 */
class KisInvestorResponseTest {

    /** 외국인 순매수, 기관 순매수, 3주체 매수수량(=거래량 근사)이 각 100씩. */
    private KisInvestorResponse.Daily row(String date, long frgnNet, long orgnNet, long volEach) {
        return new KisInvestorResponse.Daily(date,
                String.valueOf(frgnNet), String.valueOf(orgnNet),
                String.valueOf(volEach), String.valueOf(volEach), String.valueOf(volEach));
    }

    @Test
    void 진입일_직전_거래일을_고른다() {
        var rows = List.of(
                row("20260821", 300, 0, 1000),    // 진입일 당일 — 써선 안 됨(look-ahead)
                row("20260820", 150, 90, 1000),   // ← 이게 정답
                row("20260819", 30, 0, 1000));

        var f = KisInvestorResponse.priorTo(rows, "20260821");

        assertThat(f).isNotNull();
        assertThat(f.basisDate()).isEqualTo("20260820");
        assertThat(f.frgnRatioPct()).isEqualTo(150.0 / 3000 * 100);   // 분모=3주체 매수수량 합
        assertThat(f.orgnRatioPct()).isEqualTo(90.0 / 3000 * 100);
    }

    @Test
    void 진입일_당일_행만_있으면_null() {
        // 당일 행으로 대체하면 안 된다 — 채우지 못하는 게 옳다(잘못 채우면 조용히 오염된다).
        var rows = List.of(row("20260821", 300, 100, 1000));
        assertThat(KisInvestorResponse.priorTo(rows, "20260821")).isNull();
    }

    @Test
    void 이력_창보다_오래된_진입은_null() {
        // API가 ~30거래일만 주므로 그보다 오래된 진입은 못 채운다 → 분석에서 자동 제외.
        var rows = List.of(row("20260821", 300, 100, 1000), row("20260820", 150, 90, 1000));
        assertThat(KisInvestorResponse.priorTo(rows, "20260701")).isNull();
    }

    @Test
    void 순매도면_음수() {
        var rows = List.of(row("20260820", -600, -300, 1000));
        var f = KisInvestorResponse.priorTo(rows, "20260821");
        assertThat(f.frgnRatioPct()).isEqualTo(-20.0);
        assertThat(f.orgnRatioPct()).isEqualTo(-10.0);
    }

    @Test
    void 거래량이_0이면_null() {
        // 거래정지·신규상장 등 — 0으로 나누지 않고 결측으로 둔다.
        var rows = List.of(row("20260820", 0, 0, 0));
        assertThat(KisInvestorResponse.priorTo(rows, "20260821")).isNull();
    }

    @Test
    void 행_순서가_뒤섞여도_가장_가까운_직전일을_고른다() {
        // 응답 순서에 의존하지 않는다(최신순으로 온다고 가정하지 않음).
        var rows = List.of(
                row("20260814", 10, 10, 1000),
                row("20260820", 150, 90, 1000),
                row("20260818", 20, 20, 1000));
        assertThat(KisInvestorResponse.priorTo(rows, "20260821").basisDate()).isEqualTo("20260820");
    }

    @Test
    void 결측_응답은_null() {
        assertThat(KisInvestorResponse.priorTo(null, "20260821")).isNull();
        assertThat(KisInvestorResponse.priorTo(List.of(), "20260821")).isNull();
    }
}
