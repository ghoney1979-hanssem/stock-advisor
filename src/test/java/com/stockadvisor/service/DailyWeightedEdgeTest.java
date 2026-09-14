package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일별 가중 edge — pooled 가 날짜 구성 차이에 왜곡되는 것을 보정하는지, 부호 규약이 두 서비스에 맞는지.
 */
class DailyWeightedEdgeTest {

    private static Map<String, double[]> days(Object... kv) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put((String) kv[i], new double[]{((Number) kv[i + 1]).doubleValue(), ((Number) kv[i + 2]).doubleValue()});
        }
        return m;
    }

    @Test
    void 날짜구성이_같으면_pooled와_일치한다() {
        // 두 날 모두 양쪽 표본이 1건씩 — 가중이 개입할 여지가 없다.
        Map<String, double[]> ent = days("20260901", 1, 2.0, "20260902", 1, -1.0);   // 평균 +0.5
        Map<String, double[]> ctl = days("20260901", 1, 1.0, "20260902", 1, -2.0);   // 평균 -0.5
        DailyWeightedEdge.Result r = DailyWeightedEdge.of(ent, ctl, true);
        assertThat(r.weightedPct()).isEqualTo(1.0);
        assertThat(r.equalPct()).isEqualTo(1.0);
        assertThat(r.daysBoth()).isEqualTo(2);
    }

    @Test
    void 진입이_특정일에_몰리면_pooled와_갈린다() {
        // 9/1(시장이 나쁜 날)에 진입 9건 몰림, 9/2(좋은 날)엔 1건.
        // pooled: 진입평균 (9*-2 + 1*10)/10 = -0.8 · 대조군평균 (1*-3 + 9*9)/10 = +7.8 → edge -8.6
        // 일별가중: 9/1 (-2 -(-3))=+1 (w9) · 9/2 (10-9)=+1 (w1) → +1.0 — 날짜 구성 차이가 상쇄된다.
        Map<String, double[]> ent = days("20260901", 9, -18.0, "20260902", 1, 10.0);
        Map<String, double[]> ctl = days("20260901", 1, -3.0, "20260902", 9, 81.0);
        DailyWeightedEdge.Result r = DailyWeightedEdge.of(ent, ctl, true);
        assertThat(r.weightedPct()).isEqualTo(1.0);
        assertThat(r.equalPct()).isEqualTo(1.0);
        double pooled = (-18.0 + 10.0) / 10 - (-3.0 + 81.0) / 10;
        assertThat(pooled).isLessThan(-8.0);      // pooled 는 -8.6 — 부호까지 반대다
    }

    @Test
    void 부호규약_control_analysis는_거른게_나으면_양수() {
        Map<String, double[]> ent = days("20260901", 2, -2.0);      // 진입 -1.0
        Map<String, double[]> rej = days("20260901", 2, 2.0);       // 거른 것 +1.0
        assertThat(DailyWeightedEdge.of(ent, rej, false).weightedPct()).isEqualTo(2.0);
        assertThat(DailyWeightedEdge.of(ent, rej, true).weightedPct()).isEqualTo(-2.0);
    }

    @Test
    void 한쪽만_있는_날은_버리고_커버리지로_드러낸다() {
        Map<String, double[]> ent = days("20260901", 1, 1.0, "20260902", 1, 5.0, "20260903", 1, 9.0);
        Map<String, double[]> ctl = days("20260902", 1, 3.0);       // 9/2 만 대조군 있음
        DailyWeightedEdge.Result r = DailyWeightedEdge.of(ent, ctl, true);
        assertThat(r.weightedPct()).isEqualTo(2.0);                 // 9/2 만으로 계산
        assertThat(r.daysBoth()).isEqualTo(1);
        assertThat(r.daysEntered()).isEqualTo(3);                   // 3일 중 1일만 봤음이 드러난다
    }

    @Test
    void 겹치는_날이_없거나_입력이_비면_null() {
        assertThat(DailyWeightedEdge.of(days("20260901", 1, 1.0), days("20260902", 1, 1.0), true).weightedPct()).isNull();
        assertThat(DailyWeightedEdge.of(days(), days(), true).weightedPct()).isNull();
        assertThat(DailyWeightedEdge.of(null, days("20260901", 1, 1.0), true)).isEqualTo(DailyWeightedEdge.EMPTY);
    }
}
