package com.stockadvisor.service;

import com.stockadvisor.domain.FinancialFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축소판 F-Score(순수 정적).
 *
 * <p>가장 중요한 검증은 <b>판정 불가 항목을 미충족으로 치지 않는다</b>는 것이다 — 금융업은 유동자산/유동부채가
 * 아예 없는데 0으로 처리하면 업종 전체가 하위 버킷으로 밀려 <b>스코어가 업종 더미변수로 변질</b>된다.</p>
 */
class FinancialScoreTest {

    /** 7개 기준을 전부 충족하는 회사(모든 지표가 전년 대비 개선). */
    private static FinancialFact allGood() {
        return new FinancialFact("005930", "00126380", "2024", "CFS",
                // 당기: 매출 1200, 영업이익 240(20%), 순이익 200, 자산 1000, 부채 300, 자본 700,
                //       유동자산 600, 유동부채 200(유동비율 3.0), 자본금 100
                1200, 240, 200, 1000, 300, 700, 600, 200, 100,
                // 전기: 매출 1000, 영업이익 150(15%), 순이익 100, 자산 1000, 부채 400, 자본 600,
                //       유동자산 500, 유동부채 250(유동비율 2.0), 자본금 100
                1000, 150, 100, 1000, 400, 600, 500, 250, 100);
    }

    @Test
    @DisplayName("전 지표 개선이면 7점 만점, 판정 항목도 7개")
    void 만점() {
        FinancialScore.Result r = FinancialScore.of(allGood());

        assertThat(r.score()).isEqualTo(7);
        assertThat(r.evaluated()).isEqualTo(7);
        assertThat(r.detail()).containsOnly(true);
    }

    @Test
    @DisplayName("전 지표 악화 + 적자면 0점 — 판정은 7개 다 됐다")
    void 최저점() {
        FinancialFact bad = new FinancialFact("000000", "x", "2024", "CFS",
                800, -50, -100, 1000, 700, 300, 300, 400, 200,
                1000, 100, 100, 1000, 400, 600, 500, 250, 100);

        FinancialScore.Result r = FinancialScore.of(bad);

        assertThat(r.score()).isZero();
        assertThat(r.evaluated()).isEqualTo(7);
    }

    @Test
    @DisplayName("금융업처럼 유동자산·유동부채가 없으면 그 기준은 '판정 불가' — 미충족(0)으로 치지 않는다")
    void 유동비율_판정불가는_감점이_아니다() {
        FinancialFact bank = new FinancialFact("105560", "y", "2024", "CFS",
                1200, 240, 200, 1000, 300, 700, /* 유동자산 */ 0, /* 유동부채 */ 0, 100,
                1000, 150, 100, 1000, 400, 600, 0, 0, 100);

        FinancialScore.Result r = FinancialScore.of(bank);

        // 유동비율만 빠지므로 6/6 — 만점 회사와 '충족률'이 같아야 한다(하위로 밀리면 안 된다)
        assertThat(r.evaluated()).isEqualTo(6);
        assertThat(r.score()).isEqualTo(6);
    }

    @Test
    @DisplayName("레버리지는 자본이 아니라 자산 분모로 본다 — 자본잠식에서 부채비율이 발산해 순위가 뒤집히는 것 방지")
    void 자본잠식에서도_레버리지_판정이_성립한다() {
        // 자본총계 음수(자본잠식)지만 자산 대비 부채는 개선(0.9 → 0.8)
        FinancialFact impaired = new FinancialFact("000001", "z", "2024", "CFS",
                1000, 10, 5, 1000, 800, -50, 400, 300, 100,
                1000, 10, 5, 1000, 900, -20, 400, 300, 100);

        FinancialScore.Result r = FinancialScore.of(impaired);

        assertThat(r.detail()[2]).isTrue();          // 레버리지 감소 판정 성립
        assertThat(r.evaluated()).isEqualTo(7);
    }

    @Test
    @DisplayName("전기 데이터가 통째로 없으면 변화 기준 5개가 판정 불가로 빠진다")
    void 전기_결손() {
        FinancialFact firstYear = new FinancialFact("000002", "w", "2024", "CFS",
                1200, 240, 200, 1000, 300, 700, 600, 200, 100,
                0, 0, 0, 0, 0, 0, 0, 0, 0);

        FinancialScore.Result r = FinancialScore.of(firstYear);

        // ROA>0 하나만 판정 가능 → minEvaluated 필터가 이런 행을 걸러낸다
        assertThat(r.evaluated()).isEqualTo(1);
        assertThat(r.score()).isEqualTo(1);
    }

    @Test
    @DisplayName("신주발행(자본금 증가)은 미충족")
    void 신주발행() {
        FinancialFact diluted = new FinancialFact("000003", "v", "2024", "CFS",
                1200, 240, 200, 1000, 300, 700, 600, 200, /* 자본금 */ 150,
                1000, 150, 100, 1000, 400, 600, 500, 250, 100);

        FinancialScore.Result r = FinancialScore.of(diluted);

        assertThat(r.detail()[4]).isFalse();
        assertThat(r.score()).isEqualTo(6);
        assertThat(r.evaluated()).isEqualTo(7);
    }
}
