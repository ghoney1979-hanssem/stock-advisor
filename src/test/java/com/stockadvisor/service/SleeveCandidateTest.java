package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 슬리브 후보 판정(순수 정적).
 *
 * <p>가장 중요한 검증은 <b>유동성·가격 필터가 실제로 작동하는가</b>다 — 백테스트에서 이걸 빼자
 * "단기 반전 연 49% 초과"라는 허수가 나왔고(동전주 호가 튐), 넣자 −0.45%로 붕괴했다.
 * 이 필터가 백테스트와 라이브에서 <b>같은 기준</b>으로 걸려야 포워드 기록이 백테스트와 비교 가능하다.</p>
 */
class SleeveCandidateTest {

    /** {@code SleeveService.Recent}는 private record라 리플렉션으로 만든다(순수 판정 함수만 테스트하기 위함). */
    private static Object recent(int[] dates, int[] high, int[] close, long[] volume) throws Exception {
        Class<?> c = Class.forName("com.stockadvisor.service.SleeveService$Recent");
        Constructor<?> ctor = c.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(dates, high, close, volume);
    }

    /** n일치 시계열: 고가는 peak까지 올랐다가, 마지막 종가는 lastClose. 거래량은 균일. */
    private static Object series(int n, int peak, int lastClose, long volume) throws Exception {
        int[] d = new int[n], h = new int[n], c = new int[n];
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            d[i] = 20250000 + i;
            h[i] = (i == n / 2) ? peak : lastClose;      // 중간에 52주 고가
            c[i] = lastClose;
            v[i] = volume;
        }
        return recent(d, h, c, v);
    }

    private static SleeveService.Candidate call(Object r, long minPrice, long minTurnover) throws Exception {
        var m = SleeveService.class.getDeclaredMethod("candidate", String.class,
                Class.forName("com.stockadvisor.service.SleeveService$Recent"), long.class, long.class);
        m.setAccessible(true);
        return (SleeveService.Candidate) m.invoke(null, "005930", r, minPrice, minTurnover);
    }

    @Test
    @DisplayName("52주 고가 대비 비율이 축 값 — 1.0에 가까울수록 신고가 근접")
    void 축값_계산() throws Exception {
        // 고가 20,000 · 현재 종가 18,000 → 0.9
        SleeveService.Candidate c = call(series(250, 20000, 18000, 100_000), 1000, 500_000_000L);

        assertThat(c).isNotNull();
        assertThat(c.axisValue()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(c.price()).isEqualTo(18000);
    }

    @Test
    @DisplayName("가격 하한 미달은 제외 — 동전주의 호가 튐이 신호로 위장하는 것을 막는다")
    void 저가주_제외() throws Exception {
        // 종가 500원: 거래대금은 충분해도 가격 하한(1,000)에 걸린다
        assertThat(call(series(250, 600, 500, 5_000_000), 1000, 500_000_000L)).isNull();
        // 하한을 끄면 통과 — 필터가 실제로 작동함을 대조로 확인
        assertThat(call(series(250, 600, 500, 5_000_000), 0, 0)).isNotNull();
    }

    @Test
    @DisplayName("거래대금 하한 미달은 제외 — 백테스트와 라이브가 같은 기준이라야 비교 가능하다")
    void 저유동성_제외() throws Exception {
        // 18,000원 × 1,000주 = 1,800만원/일 → 5억 미달
        assertThat(call(series(250, 20000, 18000, 1_000), 1000, 500_000_000L)).isNull();
        // 18,000원 × 100,000주 = 18억 → 통과
        assertThat(call(series(250, 20000, 18000, 100_000), 1000, 500_000_000L)).isNotNull();
    }

    @Test
    @DisplayName("1년치 미만은 52주 고가를 말할 수 없으므로 제외")
    void 데이터_부족_제외() throws Exception {
        assertThat(call(series(150, 20000, 18000, 100_000), 1000, 500_000_000L)).isNull();
        assertThat(call(series(250, 20000, 18000, 100_000), 1000, 500_000_000L)).isNotNull();
    }
}
