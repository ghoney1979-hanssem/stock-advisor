package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 장중흐름 소급 — 지수경로 앵커 보간(at) 검증. (초 단위 시각, 지수등락%)
 */
class FlowBacktagServiceTest {

    // 15분(900s) 간격 앵커: 0→0.0, 900→-1.0, 1800→-2.0 (지수 하락 궤적)
    private final List<double[]> anchors = List.of(
            new double[]{0, 0.0}, new double[]{900, -1.0}, new double[]{1800, -2.0});
    private static final double TOL = 20 * 60;   // ±20분

    @Test
    void 정확앵커시각이면_그값() {
        assertThat(FlowBacktagService.at(anchors, 900, TOL)).isCloseTo(-1.0, within(0.001));
    }

    @Test
    void 브래킷_선형보간() {
        // 450초 = 0(0.0)~900(-1.0) 사이 절반 → -0.5
        assertThat(FlowBacktagService.at(anchors, 450, TOL)).isCloseTo(-0.5, within(0.001));
    }

    @Test
    void 범위밖_tol이내면_최근값_밖이면_null() {
        // 1800 이후 1200초 이내(3000) → 최근값 -2.0
        assertThat(FlowBacktagService.at(anchors, 3000, TOL)).isCloseTo(-2.0, within(0.001));
        // 1800에서 3600초 뒤(5400) → tol 밖 → null
        assertThat(FlowBacktagService.at(anchors, 5400, TOL)).isNull();
        // 첫 앵커 이전이라도 tol 이내(-600)면 첫값 0.0
        assertThat(FlowBacktagService.at(anchors, -600, TOL)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void 앵커간극_과대면_보간불가_null() {
        // 앵커 2개가 3600초(60분) 벌어짐 → 2×tol(2400s) 초과 → 중간(1800) 보간 null
        List<double[]> sparse = List.of(new double[]{0, 0.0}, new double[]{3600, -3.0});
        assertThat(FlowBacktagService.at(sparse, 1800, TOL)).isNull();
    }
}
