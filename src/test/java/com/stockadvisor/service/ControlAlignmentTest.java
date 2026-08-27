package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대조군 기간 정렬 — {@link ControlAnalysisService#overlapWindow}.
 *
 * <p>대조군 사유는 도입 시점이 제각각이라 <b>진입군보다 늦게 시작</b>한다. 정렬 없이 빼면 edge가
 * "조건 차이"가 아니라 <b>기간 차이</b>를 잰다 — 실측 2026-08-27 REVERSAL_L에서 ENTERED(4거래일,
 * 8/25 급등일 포함) vs NOT_WEAK(1거래일)을 그대로 빼 +1.85%p로 보고했으나, 같은 날로 맞추면 +0.69%p였다.</p>
 */
@DisplayName("대조군 기간 정렬")
class ControlAlignmentTest {

    private Set<String> days(String... d) {
        return new LinkedHashSet<>(java.util.Arrays.asList(d));
    }

    @Test
    void 늦게_시작한_대조군은_겹치는_구간으로_잘린다() {
        // 진입군 8/24~8/27, 대조군 8/27 하루 → 겹치는 구간은 8/27뿐.
        String[] w = ControlAnalysisService.overlapWindow(
                days("20260827"), days("20260824", "20260825", "20260826", "20260827"));

        assertThat(w).containsExactly("20260827", "20260827");
    }

    @Test
    void 양쪽이_같은_기간이면_그대로() {
        String[] w = ControlAnalysisService.overlapWindow(
                days("20260824", "20260827"), days("20260824", "20260825", "20260827"));

        assertThat(w).containsExactly("20260824", "20260827");
    }

    @Test
    void 겹치는_구간이_없으면_null() {
        String[] w = ControlAnalysisService.overlapWindow(
                days("20260701", "20260702"), days("20260824", "20260827"));

        assertThat(w).isNull();
    }

    @Test
    void 한쪽이_비면_null() {
        assertThat(ControlAnalysisService.overlapWindow(days(), days("20260827"))).isNull();
        assertThat(ControlAnalysisService.overlapWindow(days("20260827"), days())).isNull();
    }

    @Test
    void 구간은_교집합이_아니라_범위다() {
        // 그룹마다 신호가 나는 날이 달라 교집합을 쓰면 표본이 과하게 깎인다.
        // 막으려는 건 "한쪽이 통째로 다른 기간"이지 "중간 날짜가 비는 것"이 아니다.
        String[] w = ControlAnalysisService.overlapWindow(
                days("20260824", "20260827"), days("20260825", "20260826"));

        assertThat(w).containsExactly("20260825", "20260826");
    }
}
