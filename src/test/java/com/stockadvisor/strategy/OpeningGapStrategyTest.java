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

    // enabled, minGap 2, maxGap 10, minScore 40, window 09:00~09:30, 지수갭 필터 0=비활성(기존 케이스 무영향)
    private final OpeningGapStrategy s = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 0, "BULL,NEUTRAL");
    private static final LocalTime IN = LocalTime.of(9, 10);   // 개장 창 내

    /** changeRate·gapPct·recScore·trend 로 ctx 구성. */
    private StrategyContext ctx(double changeRate, double gapPct, double score, String trend) {
        return ctx(changeRate, gapPct, score, trend, null);
    }

    /** 지수 갭%까지 지정. */
    private StrategyContext ctx(double changeRate, double gapPct, double score, String trend, Double indexGapPct) {
        SignalResult sig = new SignalResult(0, changeRate, 1000, 0, false, false, false, 0,
                false, false, false, 0, 0, 0, gapPct);
        return new StrategyContext("005930", sig, score, RecommendationType.BUY, null, false, false,
                trend, null, indexGapPct);
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

    // ── 지수 통째 갭업일 제외(2026-08-14, K 7건 -120,050원 계기) ──────────
    @Test
    void 지수_통째_갭업일이면_INDEX_GAP_DAY로_보류() {
        // 지수갭 상한 1.5% 활성
        OpeningGapStrategy g = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 1.5, "BULL,NEUTRAL");
        // 8/14 재현: 지수가 +2.6% 갭업한 날의 종목 갭업 → 보류
        assertThat(g.reject(ctx(3.5, 3.0, 50, "BULL", 2.6), IN)).isEqualTo("INDEX_GAP_DAY");
        // 지수가 조용히 열린 날(+0.4%)의 종목 갭업은 종목 고유 촉매 → 정상 진입
        assertThat(g.reject(ctx(3.5, 3.0, 50, "BULL", 0.4), IN)).isNull();
        // 경계(정확히 상한) — 포함해서 보류
        assertThat(g.reject(ctx(3.5, 3.0, 50, "BULL", 1.5), IN)).isEqualTo("INDEX_GAP_DAY");
    }

    @Test
    void 지수갭_미상이거나_비활성이면_필터_미적용() {
        OpeningGapStrategy g = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 1.5, "BULL,NEUTRAL");
        assertThat(g.reject(ctx(3.5, 3.0, 50, "BULL", null), IN)).isNull();   // 장전·휴장·조회실패 → degrade open
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BULL", 2.6), IN)).isNull();    // s는 상한 0=비활성
    }

    @Test
    void 지수갭업일_판정_순수함수() {
        assertThat(OpeningGapStrategy.indexGapDay(2.6, 1.5)).isTrue();
        assertThat(OpeningGapStrategy.indexGapDay(0.4, 1.5)).isFalse();
        assertThat(OpeningGapStrategy.indexGapDay(-1.0, 1.5)).isFalse();   // 갭다운일은 무관
        assertThat(OpeningGapStrategy.indexGapDay(null, 1.5)).isFalse();   // 미상 → degrade open
        assertThat(OpeningGapStrategy.indexGapDay(9.9, 0)).isFalse();      // 비활성
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

    @Test
    void 허용국면_밖이면_국면별_사유로_보류된다() {
        // 기본값(BULL,NEUTRAL) — 종전 동작 그대로: 약세장만 제외
        assertThat(s.reject(ctx(3.5, 3.0, 50, "BEAR", null), IN)).isEqualTo("REGIME_BEAR");
        assertThat(s.reject(ctx(3.5, 3.0, 50, "NEUTRAL", null), IN)).isNull();

        // BULL 한정(2026-08-21 prod) — 중립국면도 제외되고, 사유가 REGIME_BEAR과 구분된다
        OpeningGapStrategy bullOnly = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 0, "BULL");
        assertThat(bullOnly.reject(ctx(3.5, 3.0, 50, "NEUTRAL", null), IN)).isEqualTo("REGIME_NEUTRAL");
        assertThat(bullOnly.reject(ctx(3.5, 3.0, 50, "BEAR", null), IN)).isEqualTo("REGIME_BEAR");
        assertThat(bullOnly.reject(ctx(3.5, 3.0, 50, "BULL", null), IN)).isNull();
    }

    @Test
    void 국면_미상이면_degrade_open이고_빈_목록은_제약없음() {
        OpeningGapStrategy bullOnly = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 0, "BULL");
        assertThat(bullOnly.reject(ctx(3.5, 3.0, 50, null, null), IN)).isNull();   // 국면 미상 → 통과

        OpeningGapStrategy noLimit = new OpeningGapStrategy(true, 2.0, 10.0, 40.0, "09:30", 0, "");
        assertThat(noLimit.reject(ctx(3.5, 3.0, 50, "BEAR", null), IN)).isNull();  // 빈 목록 = 제약 없음
    }
}
