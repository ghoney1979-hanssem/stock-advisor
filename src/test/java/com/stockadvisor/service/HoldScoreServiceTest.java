package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보유 점수 합의 판정(순수). 서비스 본체는 조회 전용(청산 미연결) — 7/23 what-if 결론 참조.
 */
class HoldScoreServiceTest {

    @Test
    void 평가신호_부족이면_판정불가() {
        assertThat(HoldScoreService.verdictOf(2, 2)).isEqualTo("판정불가(신호 부족)");
    }

    @Test
    void 과반_이상이면_보유근거_강함() {
        assertThat(HoldScoreService.verdictOf(5, 7)).isEqualTo("보유 근거 강함");
        assertThat(HoldScoreService.verdictOf(6, 6)).isEqualTo("보유 근거 강함");
    }

    @Test
    void 중간과_소수는_중립_약함() {
        assertThat(HoldScoreService.verdictOf(3, 6)).isEqualTo("중립");
        assertThat(HoldScoreService.verdictOf(1, 6)).isEqualTo("보유 근거 약함");
    }
}
