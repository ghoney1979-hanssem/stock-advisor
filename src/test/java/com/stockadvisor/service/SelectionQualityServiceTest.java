package com.stockadvisor.service;

import com.stockadvisor.repository.SelectionPreregRepository;
import com.stockadvisor.service.SelectionQualityService.Row;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectionQualityServiceTest {

    private static Row row(String strategy, String day, double feat, Double x10) {
        Map<String, Double> f = new HashMap<>();
        f.put("cap", feat);
        return new Row(0, strategy, false, "", day, f, new Double[]{x10, x10, x10});
    }

    @Test
    void 초과수익은_종가수익에서_같은날_유니버스를_뺀다_하나라도_없으면_null() {
        assertThat(SelectionQualityService.excess(100.0, 110.0, 4.0)).isCloseTo(6.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(SelectionQualityService.excess(100.0, null, 4.0)).isNull();   // 미도래
        assertThat(SelectionQualityService.excess(100.0, 110.0, null)).isNull();  // 반사실 미상 — 0으로 추정하지 않는다
    }

    @Test
    void 복합점수는_고정임계로_합산하고_미태깅은_0점() {
        Map<String, Double> f = new HashMap<>();
        f.put("vr", 9.0);      // ≥8
        f.put("chg", 4.9);     // <5
        f.put("nc", 3.0);      // ≥3
        f.put("fr", 1.5);
        f.put("og", 0.5);      // 합 2.0 ≥2
        // ex 미태깅 → 0점
        assertThat(SelectionQualityService.crowdScore(f)).isEqualTo(3.0);
        assertThat(SelectionQualityService.crowdScore(new HashMap<>())).isZero();
    }

    @Test
    void 같은_전략_같은_날끼리만_매칭한다() {
        List<Row> lo = List.of(row("L", "20260901", 1, 1.0), row("G", "20260902", 1, 0.0));
        List<Row> hi = List.of(row("L", "20260901", 9, 4.0), row("L", "20260902", 9, 9.0));
        Map<String, Double> dd = SelectionQualityService.dayDiffs(lo, hi, 10);
        // G·0902와 L·0902는 전략이 달라 매칭되지 않는다 — 날짜만 맞추면 전략 간 차이가 feature 효과로 둔갑한다.
        assertThat(dd).containsOnlyKeys("L|20260901");
        assertThat(dd.get("L|20260901")).isEqualTo(3.0);
    }

    private static Map<String, Double> days(double... v) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i++) m.put(String.format("L|202609%02d", i + 1), v[i]);
        return m;
    }

    @Test
    void 판정_통과는_부호_크기_LOO_과반을_모두_요구한다() {
        var pass = SelectionQualityService.judge(days(-3, -2, -4, -1, -3, -2, -5, -2), -5.9, 8);
        assertThat(pass.verdict()).isEqualTo("PASS");

        // 부호는 같지만 크기가 표본 내 1/3(1.97) 미만
        assertThat(SelectionQualityService.judge(days(-1, -1, -1, -1, -1, -1, -1, -1), -5.9, 8).verdict()).isEqualTo("FAIL");
        // 하루가 만든 평균 — 그날을 빼면 부호가 뒤집힌다(LOO)
        assertThat(SelectionQualityService.judge(days(-40, 1, 1, 1, 1, 1, 1, 1), -5.9, 8).verdict()).isEqualTo("FAIL");
        // 매칭 거래일 부족
        var hold = SelectionQualityService.judge(days(-3, -3, -3), -5.9, 8);
        assertThat(hold.verdict()).isEqualTo("판정보류");
        assertThat(hold.matchedDays()).isEqualTo(3);
    }

    @Test
    void 분해_stable은_세_지평과_LOO가_모두_같은_방향일때만() {
        List<Row> rows = new ArrayList<>();
        for (int d = 1; d <= 4; d++) {
            String day = String.format("202609%02d", d);
            rows.add(row("L", day, 1, 2.0));
            rows.add(row("L", day, 9, -1.0 - d));
        }
        var s = SelectionQualityService.spread("L", "cap", rows, 1, 9, 10);
        assertThat(s.matchedDays()).isEqualTo(4);
        assertThat(s.diffX10()).isNegative();
        assertThat(s.stable()).isTrue();
    }

    @Test
    void 등록은_이름_중복과_표본_겹침을_거부한다() {
        SelectionPreregRepository repo = mock(SelectionPreregRepository.class);
        when(repo.existsByName("dup")).thenReturn(true);
        var svc = new SelectionQualityService(mock(JdbcTemplate.class), repo, List.of("114800"), 1000, 500_000_000);

        assertThatThrownBy(() -> svc.register(new SelectionQualityService.RegisterRequest(
                "dup", "L", "cap", 10, 1.0, 2.0, -5.0, "20260620", "20260915", "20261007", 8, "")))
                .hasMessageContaining("수정할 수 없다");
        assertThatThrownBy(() -> svc.register(new SelectionQualityService.RegisterRequest(
                "overlap", "L", "cap", 10, 1.0, 2.0, -5.0, "20260620", "20261010", "20261007", 8, "")))
                .hasMessageContaining("겹침");
        assertThatThrownBy(() -> svc.register(new SelectionQualityService.RegisterRequest(
                "badfeat", "L", "nope", 10, 1.0, 2.0, -5.0, "20260620", "20260915", "20261007", 8, "")))
                .hasMessageContaining("feature");
    }
}
