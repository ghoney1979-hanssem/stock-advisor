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
}
