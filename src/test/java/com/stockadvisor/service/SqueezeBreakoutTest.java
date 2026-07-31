package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NR7 변동성 수축 돌파 판정(순수 함수): 전일이 최근 7일 중 변동폭 최소 + 현재가 > 전일 고가.
 */
class SqueezeBreakoutTest {

    // 최신순. 전일([0])이 좁고(고100/저99=범위1), 나머지 6일은 넓음(범위10).
    private List<Long> highs() {
        List<Long> h = new ArrayList<>();
        h.add(100L);                                   // 전일 고가
        for (int i = 0; i < 6; i++) h.add(110L);
        return h;
    }
    private List<Long> lows() {
        List<Long> l = new ArrayList<>();
        l.add(99L);                                    // 전일 저가 → 범위 1(최소)
        for (int i = 0; i < 6; i++) l.add(100L);       // 범위 10
        return l;
    }

    @Test
    void 수축_후_전일고가_돌파면_true() {
        assertThat(MarketSignalService.computeSqueezeBreakout(highs(), lows(), 101, 7)).isTrue();  // 101 > 전일고 100
    }

    @Test
    void 돌파_안하면_false() {
        assertThat(MarketSignalService.computeSqueezeBreakout(highs(), lows(), 100, 7)).isFalse(); // 100 = 전일고, 돌파 아님
    }

    @Test
    void 전일이_최소범위_아니면_false() {
        List<Long> h = highs();
        List<Long> l = lows();
        l.set(1, 109L);   // 1일전 범위 110-109=1 (전일과 동률이나 더 좁지 않음)… 더 좁게: l.set(2, ...)
        l.set(2, 109L);   // 2일전 범위 1 < 전일 1? 같음. 더 확실히 좁게:
        h.set(3, 110L); l.set(3, 110L);   // 3일전 범위 0 < 전일 1 → 전일이 최소 아님
        assertThat(MarketSignalService.computeSqueezeBreakout(h, l, 101, 7)).isFalse();
    }

    @Test
    void 표본부족이면_false() {
        List<Long> h = new ArrayList<>(); List<Long> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) { h.add(110L); l.add(100L); }
        assertThat(MarketSignalService.computeSqueezeBreakout(h, l, 111, 7)).isFalse();
    }
}
