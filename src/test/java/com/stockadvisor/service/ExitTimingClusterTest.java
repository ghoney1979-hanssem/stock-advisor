package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청산곡선 마크별 단일일 클러스터 판정 — {@link ExitTimingService#cluster}.
 *
 * <p>이 마크는 곧 {@code StrategyHoldTimeProvider}의 보유시간이자 {@code StrategyPerformanceGate}의
 * 채점 horizon이라, 하루가 만든 허수 마크를 고르면 <b>실제 청산 시점과 게이트 net이 함께</b> 오염된다.</p>
 */
@DisplayName("청산곡선 단일일 클러스터 가드")
class ExitTimingClusterTest {

    /** days: 진입일 -> [건수, net합] */
    private Map<String, double[]> days(Object... kv) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put((String) kv[i], new double[]{((Number) kv[i + 1]).doubleValue(), ((Number) kv[i + 2]).doubleValue()});
        }
        return m;
    }

    @Test
    void 여러날_고르게_분산되면_클러스터_아님() {
        // 3거래일 × 각 10건, net 합이 고르게 양수
        Map<String, double[]> d = days("20260824", 10, 5.0, "20260825", 10, 6.0, "20260826", 10, 4.0);

        ExitTimingService.MarkStat m = ExitTimingService.cluster("90분", 90, 30, 0.5, 55.0, d);

        assertThat(m.clustered()).isFalse();
        assertThat(m.distinctDays()).isEqualTo(3);
        assertThat(m.netExTopDayPct()).isPositive();   // 부호 유지
    }

    @Test
    void 최대기여일_제외시_부호가_뒤집히면_클러스터() {
        // 실측 REVERSAL_L 300분 마크의 구조: 8/25 하루가 net을 통째로 만든다.
        // 전체 net = (0.11*27 + 4.34*46 + 1.51*9) / 82 ≈ +2.62 인데, 8/25를 빼면 +0.46.
        // 여기서는 부호 반전 케이스를 직접 구성한다(8/25 제외 시 음수).
        Map<String, double[]> d = days("20260824", 27, -3.0, "20260825", 46, 200.0, "20260826", 9, -1.0);

        ExitTimingService.MarkStat m = ExitTimingService.cluster("300분", 300, 82, 2.39, 75.6, d);

        assertThat(m.topDay()).isEqualTo("20260825");
        assertThat(m.netExTopDayPct()).isNegative();
        assertThat(m.clustered()).isTrue();
    }

    @Test
    void 단일일_점유율_과대면_클러스터() {
        Map<String, double[]> d = days("20260825", 95, 100.0, "20260826", 5, 4.0);

        ExitTimingService.MarkStat m = ExitTimingService.cluster("90분", 90, 100, 1.04, 60.0, d);

        assertThat(m.maxDaySharePct()).isEqualTo(95.0);
        assertThat(m.clustered()).isTrue();
    }

    @Test
    void 거래일_3일미만이면_클러스터() {
        Map<String, double[]> d = days("20260825", 50, 30.0, "20260826", 50, 20.0);

        ExitTimingService.MarkStat m = ExitTimingService.cluster("90분", 90, 100, 0.5, 55.0, d);

        assertThat(m.distinctDays()).isEqualTo(2);
        assertThat(m.clustered()).isTrue();
    }

    @Test
    void 진입일_미상이면_판정_생략하되_진단부재가_드러난다() {
        // 조인 실패·구 표본 → 판정 불가. "클러스터 아님"과 구분되도록 distinctDays=0 으로 노출.
        ExitTimingService.MarkStat m = ExitTimingService.cluster("90분", 90, 100, 1.0, 55.0, Map.of());

        assertThat(m.clustered()).isFalse();
        assertThat(m.distinctDays()).isZero();
        assertThat(m.netExTopDayPct()).isNull();
        assertThat(m.topDay()).isNull();
    }
}
