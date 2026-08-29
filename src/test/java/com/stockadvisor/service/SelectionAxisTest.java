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
    @Test
    @DisplayName("MA_SPREAD_50_200: 단기선이 장기선 위면 양수, 아래면 음수, 이력 부족은 null")
    void 골든크로스_상태() {
        // 300일: 앞 250일 100원 횡보 → 마지막 50일 200원. MA50=200, MA200=(150×100+50×200)/200=125 → +60%
        int[] c = new int[300]; int[] h = new int[300]; long[] v = new long[300];
        for (int i = 0; i < 300; i++) { c[i] = i < 250 ? 100 : 200; h[i] = c[i]; v[i] = 1000; }
        Double up = SelectionAxis.MA_SPREAD_50_200.value(c, h, v, 299, 279, 239, 179, 49);
        assertThat(up).isCloseTo(60.0, org.assertj.core.data.Offset.offset(1e-9));

        for (int i = 250; i < 300; i++) c[i] = 50;   // 거울상 — 데드크로스 상태
        assertThat(SelectionAxis.MA_SPREAD_50_200.value(c, h, v, 299, 279, 239, 179, 49)).isNegative();

        assertThat(SelectionAxis.MA_SPREAD_50_200.value(c, h, v, 150, 130, 90, 30, 0)).isNull();   // 200일 미만
    }

    @Test
    @DisplayName("GC_RECENCY: 마지막 비골든일 다음 날이 크로스 — 경과 거래일을 돌려주고, 데드 상태면 null")
    void 골든크로스_신선도() {
        int[] c = new int[300]; int[] h = new int[300]; long[] v = new long[300];
        for (int i = 0; i < 300; i++) { c[i] = i < 250 ? 100 : 200; h[i] = c[i]; v[i] = 1000; }
        // 250일부터 200원: MA50이 MA200을 넘는 날 t* 가 존재하고, 그날 이후 경과일이 값이어야 한다.
        Double rec = SelectionAxis.GC_RECENCY.value(c, h, v, 299, 279, 239, 179, 49);
        assertThat(rec).isNotNull();
        assertThat(rec).isBetween(1.0, 50.0);
        // 하루 뒤(idx 298 기준)보다 정확히 1 크다 — look-ahead 없이 과거만 본다는 검증
        Double prev = SelectionAxis.GC_RECENCY.value(c, h, v, 298, 278, 238, 178, 48);
        assertThat(rec - prev).isEqualTo(1.0);

        for (int i = 250; i < 300; i++) c[i] = 50;   // 데드크로스 상태
        assertThat(SelectionAxis.GC_RECENCY.value(c, h, v, 299, 279, 239, 179, 49)).isNull();

        for (int i = 0; i < 300; i++) c[i] = 100 + i;   // 12개월 내내 골든(꾸준한 상승) → 이벤트 아님
        assertThat(SelectionAxis.GC_RECENCY.value(c, h, v, 299, 279, 239, 179, 49)).isNull();
    }

    @Test
    @DisplayName("FRGN_CHG: 지분율 %p 변화 — 끝점이 0(미보유·미제공)이면 null, 시계열 없으면 null")
    void 외국인_지분율_변화() {
        double[] frgn = {10.0, 10.5, 12.0, 11.0, 13.5};
        assertThat(SelectionAxis.FRGN_CHG_1M.value(close, high, vol, frgn, 4, 3, 2, 1, 0)).isEqualTo(2.5);   // 11.0→13.5
        assertThat(SelectionAxis.FRGN_CHG_3M.value(close, high, vol, frgn, 4, 3, 2, 1, 0)).isEqualTo(1.5);   // 12.0→13.5
        assertThat(SelectionAxis.FRGN_CHG_1M.value(close, high, vol, new double[]{0, 0, 0, 0, 5.0}, 4, 3, 2, 1, 0)).isNull();
        assertThat(SelectionAxis.FRGN_CHG_1M.value(close, high, vol, 4, 3, 2, 1, 0)).isNull();               // 구 시그니처 = frgn 없음
        assertThat(SelectionAxis.RET_1M.value(close, high, vol, frgn, 4, 3, 2, 1, 0)).isEqualTo(-50.0);       // 다른 축은 불변
    }

}
