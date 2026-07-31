package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 업종 중앙값 산출 검증(순수 함수).
 */
class SectorValuationServiceTest {

    @Test
    void 홀수_중앙값() {
        assertThat(SectorValuationService.median(List.of(10.0, 30.0, 20.0))).isEqualTo(20.0);
    }

    @Test
    void 짝수_중앙값_평균() {
        assertThat(SectorValuationService.median(List.of(10.0, 20.0, 30.0, 40.0))).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void 빈리스트_0() {
        assertThat(SectorValuationService.median(List.of())).isEqualTo(0.0);
    }

    @Test
    void 정렬_무관() {
        assertThat(SectorValuationService.median(List.of(5.0, 1.0, 9.0, 3.0, 7.0))).isEqualTo(5.0);
    }
}
