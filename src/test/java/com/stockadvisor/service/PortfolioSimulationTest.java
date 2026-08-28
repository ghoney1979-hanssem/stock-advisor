package com.stockadvisor.service;

import com.stockadvisor.service.FinancialSpreadAnalysisService.PortfolioSim;
import com.stockadvisor.service.FinancialSpreadAnalysisService.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상위 N종목 동일가중 시뮬(순수 정적).
 *
 * <p>버킷 평균은 "고득점이 나은가"만 답한다. 실제로 운용할 구성은 <b>매년 상위 30종목</b>이므로 따로 재야 한다 —
 * F-Score는 0~7 정수라 <b>필터일 뿐 랭킹이 아니고</b>, 30개로 좁히려면 2차 정렬 축이 반드시 필요하기 때문이다.</p>
 */
class PortfolioSimulationTest {

    private static Row row(String year, int score, double fwd, String code, Double trailing) {
        return new Row(year, score, fwd, code, trailing);
    }

    /**
     * 2024년: 고득점(6점) 4종목이 있고 직전 낙폭이 큰 쪽이 이후 더 잘 갔다.
     * 저득점(2점) 1종목은 필터에서 빠져야 한다.
     */
    private static Map<String, List<Row>> oneYear() {
        return Map.of("2024", List.of(
                row("2024", 6, 30.0, "AAA", -40.0),   // 가장 많이 떨어졌던 종목 → 이후 +30
                row("2024", 6, 20.0, "BBB", -20.0),
                row("2024", 6, 0.0, "CCC", 10.0),
                row("2024", 6, -10.0, "DDD", 50.0),   // 가장 많이 올랐던 종목 → 이후 -10
                row("2024", 2, 100.0, "EEE", -60.0))); // 저득점 — 성적이 좋아도 편입되면 안 된다
    }

    @Test
    @DisplayName("FALLEN은 같은 F-Score 풀에서 직전 낙폭이 큰 순으로 N개를 뽑는다")
    void 낙폭순_선정() {
        PortfolioSim sim = FinancialSpreadAnalysisService.simulate(oneYear(), 2, 6, "FALLEN");

        assertThat(sim.byYear()).hasSize(1);
        var y = sim.byYear().get(0);
        assertThat(y.picked()).isEqualTo(2);
        assertThat(y.avgReturnPct()).isEqualTo(25.0);        // AAA(+30), BBB(+20)
        assertThat(y.universeAvgPct()).isEqualTo(28.0);      // 5종목 전체 평균(저득점 포함)
        assertThat(y.excessPct()).isEqualTo(-3.0);
        assertThat(y.medianTrailingPct()).isEqualTo(-30.0);
    }

    @Test
    @DisplayName("RISEN은 같은 풀에서 방향만 뒤집은 내장 대조군 — FALLEN과 비교해야 전제가 검증된다")
    void 대조군은_상승순() {
        PortfolioSim control = FinancialSpreadAnalysisService.simulate(oneYear(), 2, 6, "RISEN");

        var y = control.byYear().get(0);
        assertThat(y.avgReturnPct()).isEqualTo(-5.0);        // DDD(-10), CCC(0)
        assertThat(control.avgExcessPct()).isEqualTo(-33.0); // 대조군이 훨씬 나쁘다 = 전제 지지
    }

    @Test
    @DisplayName("scoreMin 미달 종목은 성적이 아무리 좋아도 편입되지 않는다")
    void 점수_필터() {
        PortfolioSim sim = FinancialSpreadAnalysisService.simulate(oneYear(), 30, 6, "FALLEN");

        // 6점 4종목만 — 100% 수익난 2점짜리(EEE)는 빠진다
        assertThat(sim.byYear().get(0).picked()).isEqualTo(4);
        assertThat(sim.byYear().get(0).avgReturnPct()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("직전 수익률이 없는 종목은 2차 정렬이 불가하므로 선정에서 제외된다")
    void 직전수익률_결손은_제외() {
        Map<String, List<Row>> data = Map.of("2024", List.of(
                row("2024", 6, 30.0, "AAA", null),
                row("2024", 6, 20.0, "BBB", -20.0)));

        PortfolioSim sim = FinancialSpreadAnalysisService.simulate(data, 30, 6, "FALLEN");

        assertThat(sim.byYear().get(0).picked()).isEqualTo(1);
        assertThat(sim.byYear().get(0).avgReturnPct()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("연도별 초과수익 부호를 센다 — 전체 평균이 양수여도 1~2년이 만든 것이면 채택 금지")
    void 연도별_부호_집계() {
        Map<String, List<Row>> twoYears = Map.of(
                "2023", List.of(row("2023", 6, 10.0, "AAA", -30.0), row("2023", 3, 0.0, "BBB", -10.0)),
                "2024", List.of(row("2024", 6, -10.0, "CCC", -30.0), row("2024", 3, 0.0, "DDD", -10.0)));

        PortfolioSim sim = FinancialSpreadAnalysisService.simulate(twoYears, 30, 6, "FALLEN");

        assertThat(sim.yearsTotal()).isEqualTo(2);
        assertThat(sim.yearsPositive()).isEqualTo(1);   // 2023만 초과 양수 → 일관성 없음
    }
}
