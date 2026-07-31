package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RSI(14) 및 과매도 상향돌파 판정(순수 함수).
 */
class RsiCrossUpTest {

    /** 최신순 하락 추세: [100,110,...,240] — 최신(=[0])이 가장 낮음 → 전부 하락 → RSI≈0(과매도). */
    private List<Long> declining() {
        List<Long> c = new ArrayList<>();
        for (int i = 0; i < 15; i++) c.add(100L + i * 10);
        return c;
    }

    @Test
    void 전부_상승이면_RSI_100() {
        List<Long> up = new ArrayList<>();
        for (int i = 0; i < 15; i++) up.add(240L - i * 10);   // 최신순: 240,230,...100 (최신 최고=상승)
        assertThat(MarketSignalService.computeRsi(up, 14)).isEqualTo(100.0);
    }

    @Test
    void 표본_부족이면_null() {
        List<Long> few = new ArrayList<>();
        for (int i = 0; i < 10; i++) few.add(100L);
        assertThat(MarketSignalService.computeRsi(few, 14)).isNull();
    }

    @Test
    void 과매도서_큰반등이면_상향돌파() {
        // 어제까지 RSI≈0(≤30), 현재가 200으로 큰 반등 → 현재 RSI≈43(>30) → 돌파
        assertThat(MarketSignalService.computeRsiCrossUp(declining(), 200, 14, 30)).isTrue();
    }

    @Test
    void 반등_미미하면_돌파아님() {
        assertThat(MarketSignalService.computeRsiCrossUp(declining(), 95, 14, 30)).isFalse();
    }
}
