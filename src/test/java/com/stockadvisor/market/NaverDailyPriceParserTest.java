package com.stockadvisor.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일봉 소스 파서 — 네트워크 없이 검증(순수 함수).
 *
 * <p>본문 샘플은 2026-08-28 실호출 응답을 그대로 옮긴 것이다. 헤더 행이 <b>작은따옴표</b>이고 데이터 행만
 * 큰따옴표라는 점이 헤더를 거르는 근거이므로, 샘플에서 이 차이를 반드시 보존한다.</p>
 */
class NaverDailyPriceParserTest {

    private static final String SAMPLE = """
             [['날짜', '시가', '고가', '저가', '종가', '거래량', '외국인소진율'],
            \t
            ["20260825", 249000, 258000, 245000, 257000, 21617407, 46.65],
            \t\t
            ["20260826", 256500, 266500, 255500, 261500, 19532523, 46.71],
            ["20260827", 270000, 271000, 262500, 266000, 16829395, 46.75],
            ["20260828", 262500, 266000, 258500, 259750, 5797936, 46.75]
            ]
            """;

    @Test
    @DisplayName("정상 응답을 날짜 오름차순으로 파싱하고 헤더 행은 버린다")
    void 정상_파싱() {
        List<NaverDailyPriceClient.Bar> bars = NaverDailyPriceClient.parse(SAMPLE, "20260828");

        assertThat(bars).hasSize(4);
        assertThat(bars.get(0).businessDate()).isEqualTo("20260825");
        assertThat(bars.get(3).businessDate()).isEqualTo("20260828");

        NaverDailyPriceClient.Bar b = bars.get(1);
        assertThat(b.open()).isEqualTo(256500);
        assertThat(b.high()).isEqualTo(266500);
        assertThat(b.low()).isEqualTo(255500);
        assertThat(b.close()).isEqualTo(261500);
        assertThat(b.volume()).isEqualTo(19532523);
        assertThat(b.frgnHoldPct()).isEqualTo(46.71);
    }

    @Test
    @DisplayName("오늘 부분봉은 maxDate 초과로 제외된다 — 백테스트가 미완성 봉을 보지 않게")
    void 오늘_부분봉_제외() {
        // 장중(10:47) 조회 시 20260828 행의 거래량이 579만으로 왔다(전일 1,683만) = 미완성 봉.
        List<NaverDailyPriceClient.Bar> bars = NaverDailyPriceClient.parse(SAMPLE, "20260827");

        assertThat(bars).hasSize(3);
        assertThat(bars).noneMatch(b -> b.businessDate().equals("20260828"));
    }

    @Test
    @DisplayName("상장폐지 종목 마지막 행의 OHLC 0 이상치는 버린다")
    void 폐지_잔행_제외() {
        // 실측 000030(구 우리은행, 2019 지주전환 폐지)의 마지막 행이 시가 0으로 왔다.
        String body = """
                 [['날짜', '시가', '고가', '저가', '종가', '거래량', '외국인소진율'],
                ["20190211", 15900, 16050, 15850, 16000, 1234567, 27.5],
                ["20190212", 0, 0, 0, 0, 0, 0.0]
                ]
                """;

        List<NaverDailyPriceClient.Bar> bars = NaverDailyPriceClient.parse(body, "20260828");

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).businessDate()).isEqualTo("20190211");
    }

    @Test
    @DisplayName("없는 종목코드는 헤더만 오므로 빈 결과 — 실패 판정은 상태코드가 아니라 행 수로")
    void 없는_종목() {
        // 실측: 999999 조회는 HTTP 200 + 헤더 행 하나만 온다.
        String body = " [['날짜', '시가', '고가', '저가', '종가', '거래량', '외국인소진율']]";

        assertThat(NaverDailyPriceClient.parse(body, "20260828")).isEmpty();
        assertThat(NaverDailyPriceClient.parse("", "20260828")).isEmpty();
        assertThat(NaverDailyPriceClient.parse(null, "20260828")).isEmpty();
    }

    @Test
    @DisplayName("외국인소진율이 없는 행도 가격은 살린다(ETF 등)")
    void 소진율_없는_행() {
        String body = "[\"20260827\", 10000, 10500, 9900, 10200, 555000]";

        List<NaverDailyPriceClient.Bar> bars = NaverDailyPriceClient.parse(body, "20260828");

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).close()).isEqualTo(10200);
        assertThat(bars.get(0).frgnHoldPct()).isNull();
    }
}
