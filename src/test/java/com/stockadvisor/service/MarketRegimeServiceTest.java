package com.stockadvisor.service;

import com.stockadvisor.config.properties.MarketRegimeProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.VolatilityLevel;
import com.stockadvisor.market.dto.KisMinuteCandleResponse.Candle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 국면 판정 순수 계산 검증 — 종가 시계열(오래된→최신)만으로 추세·변동성 분류.
 */
class MarketRegimeServiceTest {

    @Test
    void 장중흐름_최근모멘텀_lag별_계산() {
        // 분봉 최신순(index 0=현재). 현재 10,100 / 10분 전(index9) 10,080 / 60분 전(index59) 9,900.
        List<Candle> c = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String close = i == 0 ? "10100" : i == 9 ? "10080" : i == 29 ? "10020" : i == 59 ? "9900" : "10000";
            c.add(new Candle("0900", close, "100"));
        }
        assertThat(MarketRegimeService.momPct(c, 10)).isCloseTo(0.2, within(0.01));    // 근접 흐름 +0.2% (vs 10,080)
        assertThat(MarketRegimeService.momPct(c, 30)).isCloseTo(0.8, within(0.01));    // 반시간 흐름 +0.8% (vs 10,020)
        assertThat(MarketRegimeService.momPct(c, 60)).isCloseTo(2.02, within(0.01));   // 1시간 흐름 +2.02% (vs 9,900)
    }

    @Test
    void 장중흐름_표본부족이면_null() {
        assertThat(MarketRegimeService.momPct(List.of(new Candle("0900", "10000", "1")), 10)).isNull();
        assertThat(MarketRegimeService.momPct(new ArrayList<>(), 10)).isNull();
    }

    private MarketRegimeProperties props() {
        // ma=10, slope=3, vol=10, 고변동≥2.0%, 저변동<1.0%
        return new MarketRegimeProperties("069500", "229200", 10, 3, 10, 2.0, 1.0, 60);
    }

    /** start 에서 매일 stepPct(%)씩 변하는 종가 n개. */
    private List<Double> series(double start, double stepPct, int n) {
        List<Double> out = new ArrayList<>();
        double v = start;
        for (int i = 0; i < n; i++) {
            out.add(v);
            v = v * (1 + stepPct / 100.0);
        }
        return out;
    }

    @Test
    void 꾸준히_상승하면_강세_저변동() {
        // 매일 +0.5% → 종가>MA, MA 기울기>0, 변동성 거의 0
        var r = MarketRegimeService.computeRegime("KOSPI", "069500", series(100, 0.5, 30), "20260629", props());

        assertThat(r.available()).isTrue();
        assertThat(r.trend()).isEqualTo(MarketTrend.BULL);
        assertThat(r.maSlopePct()).isPositive();
        assertThat(r.volatility()).isEqualTo(VolatilityLevel.LOW);   // 등락폭 일정 → 표준편차 작음
    }

    @Test
    void 꾸준히_하락하면_약세() {
        var r = MarketRegimeService.computeRegime("KOSDAQ", "229200", series(100, -0.5, 30), "20260629", props());

        assertThat(r.trend()).isEqualTo(MarketTrend.BEAR);
        assertThat(r.maSlopePct()).isNegative();
    }

    @Test
    void 급등락_반복이면_고변동() {
        // ±3% 지그재그 → 표준편차 큼
        List<Double> z = new ArrayList<>();
        double v = 100;
        for (int i = 0; i < 30; i++) {
            z.add(v);
            v = v * (1 + (i % 2 == 0 ? 3.0 : -3.0) / 100.0);
        }
        var r = MarketRegimeService.computeRegime("KOSPI", "069500", z, "20260629", props());

        assertThat(r.volatility()).isEqualTo(VolatilityLevel.HIGH);
        assertThat(r.realizedVolPct()).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void 데이터_부족하면_unavailable_중립() {
        // maPeriod(10) 미만 → 판정 불가
        var r = MarketRegimeService.computeRegime("KOSPI", "069500", series(100, 0.5, 6), "20260629", props());

        assertThat(r.available()).isFalse();
        assertThat(r.trend()).isEqualTo(MarketTrend.NEUTRAL);
        assertThat(r.volatility()).isEqualTo(VolatilityLevel.MID);
    }

    @Test
    void 전체국면_종합_부호합산() {
        // 강세+강세=강세, 강세+약세=중립(상쇄), 약세+중립=약세, 한쪽 null이면 다른쪽
        assertThat(MarketRegimeService.combineTrend(MarketTrend.BULL, MarketTrend.BULL)).isEqualTo(MarketTrend.BULL);
        assertThat(MarketRegimeService.combineTrend(MarketTrend.BULL, MarketTrend.BEAR)).isEqualTo(MarketTrend.NEUTRAL);
        assertThat(MarketRegimeService.combineTrend(MarketTrend.BEAR, MarketTrend.NEUTRAL)).isEqualTo(MarketTrend.BEAR);
        assertThat(MarketRegimeService.combineTrend(MarketTrend.BULL, null)).isEqualTo(MarketTrend.BULL);
        assertThat(MarketRegimeService.combineTrend(null, null)).isNull();
    }

    @Test
    void 횡보면_중립_저변동() {
        // 종가 일정 → 종가=MA, MA 기울기 0(>0 아님) → 중립, 일간수익률 0 → 저변동
        var r = MarketRegimeService.computeRegime("KOSPI", "069500", series(100, 0.0, 30), "20260629", props());

        assertThat(r.trend()).isEqualTo(MarketTrend.NEUTRAL);
        assertThat(r.maSlopePct()).isEqualTo(0.0);
        assertThat(r.volatility()).isEqualTo(VolatilityLevel.LOW);
    }

    // ── intraday 보정(2026-07-16) — 순수 판정 ──

    @org.junit.jupiter.api.Test
    void 당일_폭락이면_라벨_강등_2퍼센트_1단계_4퍼센트_2단계() {
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BULL, -2.5, null, false, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.NEUTRAL);
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BULL, -6.0, null, false, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BEAR);   // 2단계 강등(7/16 실측 상황)
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.NEUTRAL, -2.5, null, false, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BEAR);
    }

    @org.junit.jupiter.api.Test
    void 승격은_지수와_breadth_합의가_있어야() {
        // 지수 +3%여도 breadth 합의(≥60%·신선) 없으면 유지 — 7/10 불트랩 반례 반영(승격 보수)
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BEAR, 3.0, 45.0, true, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BEAR);
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BEAR, 3.0, 75.0, false, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BEAR);   // breadth 미신선
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BEAR, 3.0, 75.0, true, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.NEUTRAL);   // 합의 충족 → 1단계만
    }

    @org.junit.jupiter.api.Test
    void 지수미상_또는_비활성이면_원본유지() {
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BULL, null, 90.0, true, 2.0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BULL);
        assertThat(MarketRegimeService.adjustTrend(com.stockadvisor.domain.MarketTrend.BULL, -6.0, null, false, 0, 2.0))
                .isEqualTo(com.stockadvisor.domain.MarketTrend.BULL);   // demote 0 = 비활성
    }

    @Test
    void 반등일_판정_급등이고_기저_비강세면_true() {
        // 7/15·7/22 재현: 당일 +2% 이상 급등인데 MA3 라벨이 아직 중립/약세 = V자 초입 → 순추세 보류
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.NEUTRAL, 2.4, 2.0)).isTrue();
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.BEAR, 7.0, 2.0)).isTrue();
    }

    @Test
    void 반등일_판정_안정_강세장이면_false() {
        // 기저 라벨이 이미 BULL = 정착된 강세장의 강한 날 — 순추세 정상 매매
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.BULL, 3.0, 2.0)).isFalse();
    }

    @Test
    void 반등일_판정_급등_미달이거나_데이터없으면_false() {
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.NEUTRAL, 1.9, 2.0)).isFalse();
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.NEUTRAL, null, 2.0)).isFalse();
        assertThat(MarketRegimeService.isReboundDay(MarketTrend.NEUTRAL, 5.0, 0)).isFalse();   // 0=비활성
    }

    // ── 라벨 승격 디바운스(2026-08-13) — 강등 즉시 / 승격은 지속 확인 ──
    private static final java.time.Instant T0 = java.time.Instant.parse("2026-08-13T00:10:00Z");

    private MarketRegimeService.TrendHold stable(MarketTrend t) {
        return new MarketRegimeService.TrendHold(t, null, null);
    }

    @Test
    void 승격은_지속되어야_확정되고_그전에는_기존라벨_유지() {
        // 중립 확정 상태에서 강세가 관측되기 시작 → 30분 지속돼야 강세로 확정(그 전엔 중립 유지)
        MarketRegimeService.TrendHold s = stable(MarketTrend.NEUTRAL);
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0, 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.NEUTRAL);          // 후보 관측 시작 — 아직 중립
        assertThat(s.pending()).isEqualTo(MarketTrend.BULL);

        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0.plusSeconds(29 * 60), 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.NEUTRAL);          // 29분 — 아직 미확정

        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0.plusSeconds(30 * 60), 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.BULL);             // 30분 지속 → 승격 확정
        assertThat(s.pending()).isNull();
    }

    @Test
    void 승격_대기중_후보가_사라지면_대기해제() {
        // 잠깐 강세로 튀었다가 중립으로 돌아오면 대기 리셋 — 다시 튀어도 처음부터 30분 세야 한다.
        MarketRegimeService.TrendHold s = stable(MarketTrend.NEUTRAL);
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0, 30);
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.NEUTRAL, T0.plusSeconds(60), 30);
        assertThat(s.pending()).isNull();
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0.plusSeconds(120), 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.NEUTRAL);          // 재관측 — 다시 대기 시작
        // 최초 관측(T0)이 아니라 재관측(+120s) 기준이라 T0+30분에도 아직 미확정
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BULL, T0.plusSeconds(30 * 60), 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.NEUTRAL);
    }

    @Test
    void 강등은_디바운스_없이_즉시_반영() {
        // 비대칭 원칙: 리스크 축소는 지연시키지 않는다(intraday 보정과 동일 사상).
        assertThat(MarketRegimeService.stabilizeTrend(stable(MarketTrend.BULL), MarketTrend.NEUTRAL, T0, 30).stable())
                .isEqualTo(MarketTrend.NEUTRAL);
        assertThat(MarketRegimeService.stabilizeTrend(stable(MarketTrend.BULL), MarketTrend.BEAR, T0, 30).stable())
                .isEqualTo(MarketTrend.BEAR);
        // 승격 대기 중에 강등이 오면 대기도 함께 해제
        MarketRegimeService.TrendHold s = MarketRegimeService.stabilizeTrend(stable(MarketTrend.NEUTRAL), MarketTrend.BULL, T0, 30);
        s = MarketRegimeService.stabilizeTrend(s, MarketTrend.BEAR, T0.plusSeconds(60), 30);
        assertThat(s.stable()).isEqualTo(MarketTrend.BEAR);
        assertThat(s.pending()).isNull();
    }

    @Test
    void 디바운스_비활성이거나_최초관측이면_그대로_통과() {
        assertThat(MarketRegimeService.stabilizeTrend(stable(MarketTrend.NEUTRAL), MarketTrend.BULL, T0, 0).stable())
                .isEqualTo(MarketTrend.BULL);      // 0=비활성(종전 동작)
        assertThat(MarketRegimeService.stabilizeTrend(null, MarketTrend.BULL, T0, 30).stable())
                .isEqualTo(MarketTrend.BULL);      // 최초 관측 — 기준 라벨이 없으므로 즉시 채택
    }

    /** 지수 갭%(K "지수 통째 갭업일 제외"의 입력) — 시가/전일종가에서 계산. */
    @Test
    void 지수_갭_계산() {
        // 2026-08-14 KOSPI 재현: 전일 종가 107,000 → 시가 109,800 ≈ +2.6%
        assertThat(MarketRegimeService.gapPct(109_800.0, 107_000.0)).isCloseTo(2.617, within(0.01));
        assertThat(MarketRegimeService.gapPct(99_000.0, 100_000.0)).isCloseTo(-1.0, within(1e-9));  // 갭다운
        assertThat(MarketRegimeService.gapPct(null, 100_000.0)).isNull();     // 시가 미상
        assertThat(MarketRegimeService.gapPct(100_000.0, null)).isNull();     // 전일종가 미상
        assertThat(MarketRegimeService.gapPct(100_000.0, 0.0)).isNull();      // 0 방어
    }
}
