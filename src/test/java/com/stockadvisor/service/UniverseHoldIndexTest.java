package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 유니버스 단순보유 벤치마크 인덱스(순수) — DB 없이 검증. */
class UniverseHoldIndexTest {

    private static Object[] row(String date, int k, double ret, long n) {
        return new Object[]{date, k, ret, n};
    }

    @Test
    void 진입일과_보유거래일수로_조회한다() {
        UniverseHoldIndex idx = UniverseHoldIndex.of(List.of(
                row("20260720", 1, 0.57, 1023),
                row("20260720", 15, 10.88, 1023),
                row("20260721", 15, 11.77, 992)), 50);

        assertThat(idx.available()).isTrue();
        assertThat(idx.hold("20260720", 15).getAsDouble()).isCloseTo(10.88, within(1e-9));
        assertThat(idx.hold("20260721", 15).getAsDouble()).isCloseTo(11.77, within(1e-9));
        assertThat(idx.hold("20260720", 5).isPresent()).isFalse();     // 그 k는 없음
        assertThat(idx.hold("20260722", 15).isPresent()).isFalse();    // 그 날짜는 없음
        assertThat(idx.hold(null, 15).isPresent()).isFalse();
        assertThat(idx.hold("20260720", 0).isPresent()).isFalse();     // k=0은 보유가 아니다
    }

    @Test
    void 종목수_미달_집계는_벤치마크로_쓰지_않는다() {
        // 몇 종목짜리 평균은 "시장"이 아니라 잡음이다 — 그걸 반사실로 쓰면 초과수익이 통째로 허수가 된다.
        UniverseHoldIndex idx = UniverseHoldIndex.of(List.of(
                row("20260720", 15, 10.88, 1023),
                row("20260721", 15, 99.0, 3)), 50);

        assertThat(idx.hold("20260720", 15).isPresent()).isTrue();
        assertThat(idx.hold("20260721", 15).isPresent()).isFalse();
        assertThat(idx.rows()).isEqualTo(1);
    }

    @Test
    void 미가용이면_모든_조회가_비어_degrade된다() {
        UniverseHoldIndex idx = UniverseHoldIndex.unavailable();
        assertThat(idx.available()).isFalse();
        assertThat(idx.hold("20260720", 15).isPresent()).isFalse();
        assertThat(idx.dates()).isEmpty();
    }

    @Test
    void 깨진_행은_건너뛴다() {
        UniverseHoldIndex idx = UniverseHoldIndex.of(java.util.Arrays.asList(
                null,
                new Object[]{"20260720"},
                new Object[]{null, 15, 1.0, 999L},
                new Object[]{"20260720", 15, null, 999L},
                row("20260720", 15, 10.88, 1023)), 50);

        assertThat(idx.rows()).isEqualTo(1);
        assertThat(idx.hold("20260720", 15).getAsDouble()).isCloseTo(10.88, within(1e-9));
    }

    @Test
    void 벤치마크가_덮는_최대_보유일() {
        UniverseHoldIndex idx = UniverseHoldIndex.of(List.of(
                row("20260925", 1, 0.5, 900), row("20260925", 3, 1.2, 900), row("20260925", 2, 0.8, 900)), 50);
        assertThat(idx.maxK("20260925")).isEqualTo(3);
        assertThat(idx.maxK("20260926")).isZero();   // 그 날짜 없음
        assertThat(idx.maxK(null)).isZero();
    }

    @Test
    void 마크가_벤치마크보다_앞서면_경로를_벤치마크_날짜까지_자른다() {
        // 16:3x 오늘 마크 적재 ~ 캐시 재빌드 사이: 마크는 D+4까지, 벤치마크는 D+3까지.
        // 자르지 않으면 미결 경로의 보유일(4)이 벤치마크 밖이라 표본이 통째로 빠졌다(2026-09-30 아티팩트).
        List<PositionExitService.DayBar> bars = List.of(
                new PositionExitService.DayBar(1, 100, null, null, null),
                new PositionExitService.DayBar(2, 101, null, null, null),
                new PositionExitService.DayBar(3, 102, null, null, null),
                new PositionExitService.DayBar(4, 110, null, null, null));
        List<PositionExitService.DayBar> out = StrategyPerformanceGate.trimToBenchmark(bars, 3);
        assertThat(out).extracting(PositionExitService.DayBar::day).containsExactly(1, 2, 3);
        // 벤치마크가 없는 진입일(maxK=0)은 손대지 않는다 — 어차피 hold()가 empty라 종전대로 제외된다.
        assertThat(StrategyPerformanceGate.trimToBenchmark(bars, 0)).hasSize(4);
        assertThat(StrategyPerformanceGate.trimToBenchmark(null, 3)).isNull();
    }
}
