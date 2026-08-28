package com.stockadvisor.service;

import com.stockadvisor.service.TrailingExitSimulator.Exit;
import com.stockadvisor.service.TrailingExitSimulator.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트레일링 청산 규칙(순수 정적): <b>+5% 도달 후 고점 대비 −2%면 청산, 상한 도달 시 종가 청산.</b>
 *
 * <p>가장 중요한 검증은 <b>보수적 규약</b> 둘이다 — ① 오늘 고가로 스톱을 올리기 <b>전에</b> 오늘 저가로 스톱을
 * 판정한다(하루 안의 고가·저가 순서를 모르므로, 반대로 하면 결과가 낙관적으로 부풀려진다)
 * ② 갭 하락은 스톱 가격이 아니라 <b>시가</b>에 체결된다.</p>
 */
class TrailingExitSimulatorTest {

    @Test
    @DisplayName("+5% 도달 후 고점 대비 -2%에서 청산된다")
    void 무장후_트레일_청산() {
        // 100 진입 → 110까지 상승(무장) → 다음날 저가가 스톱(110×0.98=107.8) 아래로
        int[] open  = {100, 106, 109};
        int[] high  = {100, 110, 109};
        int[] low   = {100, 105, 107};
        int[] close = {100, 108, 108};

        Exit x = TrailingExitSimulator.run(open, high, low, close, 0, 2, 5.0, 2.0);

        assertThat(x.reason()).isEqualTo(Reason.TRAIL);
        assertThat(x.armed()).isTrue();
        assertThat(x.exitPrice()).isEqualTo(110 * 0.98);
        assertThat(x.exitIdx()).isEqualTo(2);
    }

    @Test
    @DisplayName("+5%에 도달하지 못하면 트레일이 걸리지 않고 상한까지 보유한다 — 손절이 없다")
    void 무장전_하락은_상한까지_보유() {
        int[] open  = {100, 101, 92};
        int[] high  = {100, 104, 93};   // 최고 +4% — 무장 문턱 미달
        int[] low   = {100, 98, 85};
        int[] close = {100, 100, 88};

        Exit x = TrailingExitSimulator.run(open, high, low, close, 0, 2, 5.0, 2.0);

        assertThat(x.armed()).isFalse();
        assertThat(x.reason()).isEqualTo(Reason.MAX_HOLD);
        assertThat(x.exitPrice()).isEqualTo(88);
        assertThat(x.troughPct()).isEqualTo(-15.0);   // 손절 부재의 대가가 MAE로 드러난다
    }

    @Test
    @DisplayName("갭 하락은 스톱 가격이 아니라 시가에 체결된다 — 빼면 하락장 손실이 과소평가된다")
    void 갭하락은_시가체결() {
        // 110까지 올라 무장(스톱 107.8) → 다음날 시가 100으로 갭 하락
        int[] open  = {100, 106, 100};
        int[] high  = {100, 110, 101};
        int[] low   = {100, 105, 96};
        int[] close = {100, 108, 97};

        Exit x = TrailingExitSimulator.run(open, high, low, close, 0, 2, 5.0, 2.0);

        assertThat(x.reason()).isEqualTo(Reason.GAP_THROUGH_STOP);
        assertThat(x.exitPrice()).isEqualTo(100);          // 107.8이 아니라 시가
        assertThat(x.exitPrice()).isLessThan(110 * 0.98);
    }

    @Test
    @DisplayName("같은 날 무장과 이탈이 겹치면 청산하지 않는다 — 고가가 먼저 왔다고 가정하지 않는(보수적) 규약")
    void 같은날_무장과_이탈은_다음날부터_판정() {
        // 하루에 고가 110(+10%)과 저가 106이 함께 있는 날. 낙관적 규약이라면 그날 110×0.98=107.8에 청산되지만,
        // 순서를 알 수 없으므로 그날은 무장만 하고 청산은 다음날부터 판정한다.
        int[] open  = {100, 108, 108};
        int[] high  = {100, 110, 109};
        int[] low   = {100, 106, 106};
        int[] close = {100, 107, 108};

        Exit x = TrailingExitSimulator.run(open, high, low, close, 0, 1, 5.0, 2.0);

        assertThat(x.reason()).isEqualTo(Reason.MAX_HOLD);
        assertThat(x.armed()).isTrue();
        assertThat(x.exitPrice()).isEqualTo(107);
    }

    @Test
    @DisplayName("무장 문턱(+5%)과 하락폭(-2%)이면 최소 확보 수익은 +2.9%")
    void 최소_확보수익() {
        int[] open  = {100, 104, 104};
        int[] high  = {100, 105, 104};   // 정확히 +5% 도달 → 스톱 105×0.98 = 102.9
        int[] low   = {100, 103, 100};
        int[] close = {100, 105, 101};

        Exit x = TrailingExitSimulator.run(open, high, low, close, 0, 2, 5.0, 2.0);

        assertThat(x.reason()).isEqualTo(Reason.TRAIL);
        assertThat(x.exitPrice()).isCloseTo(102.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("공개된 재무연도 판정 — (Y+1)년 5월부터 (Y+2)년 4월까지 Y가 유효")
    void 유효_사업연도() {
        assertThat(MultidayBacktestService.validBusinessYear(java.time.LocalDate.of(2025, 5, 1))).isEqualTo(2024);
        assertThat(MultidayBacktestService.validBusinessYear(java.time.LocalDate.of(2026, 4, 30))).isEqualTo(2024);
        assertThat(MultidayBacktestService.validBusinessYear(java.time.LocalDate.of(2026, 5, 1))).isEqualTo(2025);
    }
}
