package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선정 축 계산(순수 정적).
 *
 * <p>핵심 검증은 <b>look-ahead 부재</b>와 <b>RET_12_1이 직전 1개월을 실제로 제외하는가</b>다 —
 * 후자가 이 축의 정체이고(단기 반전 오염 제거), 안 빼면 그냥 RET_12M이 된다.</p>
 */
class SelectionAxisTest {

    // idx:     0    1    2    3    4
    private final int[] close = {100, 120, 150, 200, 100};
    private final int[] high  = {110, 130, 160, 220, 105};
    private final long[] vol  = {1000, 1000, 1000, 5000, 5000};

    @Test
    @DisplayName("RET_12_1은 직전 1개월을 제외한다 — 이게 이 축의 정체다")
    void 모멘텀_12_1은_직전1개월_제외() {
        // i12m=0(100), i1m=3(200), idx=4(100)
        // 12-1 이면 100 → 200 = +100%. 직전 1개월(200→100 = -50%)은 빠져야 한다.
        Double v = SelectionAxis.RET_12_1.value(close, high, vol, 4, 3, 2, 1, 0);

        assertThat(v).isEqualTo(100.0);

        // 대조: 같은 구간을 12개월 통째로 보면 100 → 100 = 0%
        Double full = SelectionAxis.RET_6M.value(close, high, vol, 4, 3, 2, 1, 0);
        assertThat(full).isNotEqualTo(v);
    }

    @Test
    @DisplayName("RET_1M은 직전 1개월 수익률 — 단기 반전 가설의 창")
    void 단기_수익률() {
        assertThat(SelectionAxis.RET_1M.value(close, high, vol, 4, 3, 2, 1, 0)).isEqualTo(-50.0);
    }

    @Test
    @DisplayName("HIGH_52W는 현재가 ÷ 기간 최고가 — 신고가 근접도")
    void 신고가_근접도() {
        // 최고 고가 220, 현재 종가 100 → 0.4545...
        Double v = SelectionAxis.HIGH_52W.value(close, high, vol, 4, 3, 2, 1, 0);

        assertThat(v).isCloseTo(100.0 / 220.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("계산 불가(구간 데이터 부족)면 null — 그 종목은 해당 코호트에서 제외된다")
    void 데이터_부족은_null() {
        assertThat(SelectionAxis.RET_12_1.value(close, high, vol, 4, 3, 2, 1, -1)).isNull();
        assertThat(SelectionAxis.RET_1M.value(close, high, vol, 4, -1, 2, 1, 0)).isNull();
        // 표본이 20 미만이면 변동성은 판정 불가
        assertThat(SelectionAxis.VOL_6M.value(close, high, vol, 4, 3, 2, 1, 0)).isNull();
    }

    @Test
    @DisplayName("VOLUME_TREND는 최근 1개월 평균거래량 ÷ 직전 기간 평균 — 관심도 변화")
    void 거래량_추세() {
        int[] c = new int[40];
        int[] h = new int[40];
        long[] v = new long[40];
        for (int i = 0; i < 40; i++) { c[i] = 100; h[i] = 100; v[i] = i >= 30 ? 3000 : 1000; }

        // idx=39, i1m=29, i6m=0 → 최근(30..39)=3000, 기저(0..29)=1000 → 3.0배
        Double t = SelectionAxis.VOLUME_TREND.value(c, h, v, 39, 29, 20, 0, 0);

        assertThat(t).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
