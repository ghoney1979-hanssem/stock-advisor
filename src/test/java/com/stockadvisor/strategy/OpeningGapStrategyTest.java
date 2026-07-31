package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 K 개장갭 — 갭업 유지 + 비약세면 진입, 갭부족/과대/약세/페이드/저점수/창밖 제외.
 */
class OpeningGapStrategyTest {

    // enabled, minGap 2, maxGap 10, minScore 40, window 09:00~09:30
    private final OpeningGapStrategy s = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30");
    private static final LocalTime IN = LocalTime.of(9, 10);   // 개장 창 내

    /** changeRate·gapPct·recScore·trend 로 ctx 구성. */
    private StrategyContext ctx(double changeRate, double gapPct, double score, String trend) {
        SignalResult sig = new SignalResult(0, changeRate, 1000, 0, false, false, false, 0,
                false, false, false, 0, 0, 0, gapPct);
        return new StrategyContext("005930", sig, score, RecommendationType.BUY, null, false, false, trend);
    }

    @Test
    void 갭업유지_비약세면_진입() {
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BULL"), IN)).isNull();       // 갭+3, 현재+3.5(유지), 강세, 점수50
        assertThat(s.reject(ctx(3.0, 3.0, 50, "NEUTRAL"), IN)).isNull();    // 중립도 허용
    }

    @Test
    void 갭_부족_과대_제외() {
        assertThat(s.reject(ctx(1.5, 1.0, 50, "BULL"), IN)).isEqualTo("NO_GAP");       // 갭 1<2
        assertThat(s.reject(ctx(12.0, 12.0, 50, "BULL"), IN)).isEqualTo("GAP_TOO_BIG"); // 갭 12>10
    }

    @Test
    void 약세장_페이드_저점수_제외() {
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BEAR"), IN)).isEqualTo("REGIME_BEAR");   // 약세장 갭업
        assertThat(s.reject(ctx(2.0, 3.0, 50, "BULL"), IN)).isEqualTo("FADING");        // 현재+2 < 갭+3(시가 이탈)
        assertThat(s.reject(ctx(3.5, 3.0, 30, "BULL"), IN)).isEqualTo("SCORE");         // 점수 30<40
    }

    @Test
    void 개장창_밖이면_제외() {
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BULL"), LocalTime.of(10, 0))).isEqualTo("OUT_OF_WINDOW");
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BULL"), LocalTime.of(8, 55))).isEqualTo("OUT_OF_WINDOW");
    }
}
