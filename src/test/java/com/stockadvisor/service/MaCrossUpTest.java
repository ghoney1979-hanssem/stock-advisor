package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MA20 상향 돌파 판정(순수 함수): 전일 종가 ≤ MA 이고 현재가 > MA면 돌파.
 */
class MaCrossUpTest {

    private List<Long> closes(long first, long rest) {   // [0]=최신(전일), 나머지 동일
        List<Long> c = new ArrayList<>();
        c.add(first);
        for (int i = 1; i < 20; i++) c.add(rest);
        return c;
    }

    @Test
    void 전일MA이하_현재가MA위면_돌파() {
        // MA = (95 + 19×100)/20 = 99.75. 전일 95 ≤ 99.75, 현재가 105 > 99.75 → 돌파
        assertThat(MarketSignalService.computeMaCrossUp(closes(95, 100), 105, 20)).isTrue();
    }

    @Test
    void 현재가_MA이하면_돌파아님() {
        assertThat(MarketSignalService.computeMaCrossUp(closes(100, 100), 99, 20)).isFalse();
    }

    @Test
    void 전일_이미_MA위면_돌파아님() {
        // 전일 110 > MA(100.5) → 이미 위 → 신규 돌파 아님
        assertThat(MarketSignalService.computeMaCrossUp(closes(110, 100), 111, 20)).isFalse();
    }

    @Test
    void 표본부족이면_false() {
        List<Long> few = new ArrayList<>();
        for (int i = 0; i < 10; i++) few.add(100L);
        assertThat(MarketSignalService.computeMaCrossUp(few, 105, 20)).isFalse();
    }
}
