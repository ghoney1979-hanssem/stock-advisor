package com.stockadvisor.service;

import com.stockadvisor.domain.DailyPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준일 탐색(순수 정적) — <b>look-ahead 차단이 이 분석 유효성의 전부</b>라 여기만 따로 검증한다.
 *
 * <p>사업보고서는 사업연도 종료 후 90일 이내(3월 말) 제출이므로 Y년 재무를 Y년 중에 쓰면 미래를 보는 것이다.
 * 기준일을 (Y+1)년 5월로 잡고, 그 <b>이후</b> 첫 거래일을 쓴다(이전 거래일을 쓰면 안 된다).</p>
 */
class FinancialSpreadLookaheadTest {

    private static DailyPrice bar(String date, long close) {
        return new DailyPrice("005930", date, close, close, close, close, 1000, null);
    }

    private static final List<DailyPrice> PRICES = List.of(
            bar("20240429", 100),
            bar("20240430", 101),
            // 5/1은 근로자의 날 휴장 → 첫 거래일은 5/2여야 한다
            bar("20240502", 105),
            bar("20240503", 106),
            bar("20250502", 130));

    @Test
    @DisplayName("기준일이 휴장이면 그 '이후' 첫 거래일을 쓴다 — 직전 거래일을 쓰면 미래 정보가 아니라 과거로 새는 것")
    void 휴장이면_이후_첫거래일() {
        Integer idx = FinancialSpreadAnalysisService.firstOnOrAfter(PRICES, "20240501");

        assertThat(idx).isNotNull();
        assertThat(PRICES.get(idx).getBusinessDate()).isEqualTo("20240502");
    }

    @Test
    @DisplayName("기준일이 거래일이면 그 날을 그대로 쓴다(경계 포함)")
    void 거래일이면_그날() {
        Integer idx = FinancialSpreadAnalysisService.firstOnOrAfter(PRICES, "20240502");

        assertThat(PRICES.get(idx).getBusinessDate()).isEqualTo("20240502");
    }

    @Test
    @DisplayName("horizon 끝이 데이터 밖이면 null — 미래 수익률을 추정하지 않고 표본에서 뺀다")
    void 데이터_밖이면_제외() {
        assertThat(FinancialSpreadAnalysisService.firstOnOrAfter(PRICES, "20260101")).isNull();
    }

    @Test
    @DisplayName("모든 데이터가 기준일 이후면 가장 이른 거래일")
    void 전부_이후면_처음() {
        Integer idx = FinancialSpreadAnalysisService.firstOnOrAfter(PRICES, "20200101");

        assertThat(PRICES.get(idx).getBusinessDate()).isEqualTo("20240429");
    }
}
