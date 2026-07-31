package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * C 일봉 백테스트 코어(runBars) 검증 — 눌림목+거래량 조건 진입 포착 및 D+N 수익 집계.
 */
class BacktestServiceTest {

    /** [close, volume] 바 생성 헬퍼. */
    private double[] bar(double close, double vol) { return new double[]{close, vol}; }

    @Test
    void 눌림목_거래량급증_진입후_익일수익_집계() {
        // volLookback=5, 앞 5일 평평한 평균거래량(1000), 6번째 날 -5% 하락 + 거래량 3000(3배) → 진입
        // 이후 D+1 +4%, D+2 +6%, D+3 +2% 회복
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));   // idx 0~4: 평균거래량 산정용
        bars.add(bar(9_500, 3_000));   // idx5: -5%(전일 10000 대비) + 거래량 3배 → 진입
        bars.add(bar(9_880, 1_000));   // idx6 = D+1: (9880-9500)/9500=+4.0%
        bars.add(bar(10_070, 1_000));  // idx7 = D+2: +6.0%
        bars.add(bar(9_690, 1_000));   // idx8 = D+3: +2.0%

        // 비용 0 으로 두고 gross 검증
        BacktestService.Partial p = BacktestService.runBars(bars, 3.0, 12.0, 2.0, 5, buy -> 0.0);

        assertThat(p.entries()).isEqualTo(1);
        assertThat(p.cnt()[0]).isEqualTo(1);                 // D+1 표본 1
        assertThat(p.sum()[0]).isCloseTo(4.0, within(0.01)); // D+1 +4%
        assertThat(p.sum()[1]).isCloseTo(6.0, within(0.01)); // D+2 +6%
        assertThat(p.sum()[2]).isCloseTo(2.0, within(0.01)); // D+3 +2%
        assertThat(p.wins()[0]).isEqualTo(1);
    }

    @Test
    void 하락폭_범위밖이면_미진입() {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(9_900, 3_000));   // -1% (minDrop 3% 미달) → 진입 X
        bars.add(bar(10_000, 1_000));
        BacktestService.Partial p = BacktestService.runBars(bars, 3.0, 12.0, 2.0, 5, buy -> 0.0);
        assertThat(p.entries()).isEqualTo(0);
    }

    @Test
    void 폭락_maxDrop초과면_미진입() {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(8_500, 3_000));   // -15% (maxDrop 12% 초과) → 제외(정리매매 등)
        bars.add(bar(8_700, 1_000));
        BacktestService.Partial p = BacktestService.runBars(bars, 3.0, 12.0, 2.0, 5, buy -> 0.0);
        assertThat(p.entries()).isEqualTo(0);
    }

    @Test
    void 거래량_미급증이면_미진입() {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(9_500, 1_500));   // -5%지만 거래량 1.5배 (<2배) → 진입 X
        bars.add(bar(9_700, 1_000));
        BacktestService.Partial p = BacktestService.runBars(bars, 3.0, 12.0, 2.0, 5, buy -> 0.0);
        assertThat(p.entries()).isEqualTo(0);
    }

    @Test
    void B밴드_횡보_거래량급증_진입() {
        // B: 등락률 0~+1% 횡보 + 거래량 급증. +0.5% 날 거래량 3배 → 진입, D+1 +3%
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(10_050, 3_000));   // +0.5% (0~1% 밴드) + 거래량 3배 → 진입
        bars.add(bar(10_351, 1_000));   // D+1 ≈ +3.0%
        BacktestService.Partial p = BacktestService.runBarsBand(bars, 0.0, 1.0, 2.0, 5, buy -> 0.0);
        assertThat(p.entries()).isEqualTo(1);
        assertThat(p.sum()[0]).isCloseTo(3.0, within(0.02));
    }

    @Test
    void B밴드_하락이면_미진입() {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(9_950, 3_000));   // -0.5% (밴드 0~1% 밖, 하락) → 진입 X
        bars.add(bar(10_000, 1_000));
        BacktestService.Partial p = BacktestService.runBarsBand(bars, 0.0, 1.0, 2.0, 5, buy -> 0.0);
        assertThat(p.entries()).isEqualTo(0);
    }

    @Test
    void 비용차감_net반영() {
        List<double[]> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(10_000, 1_000));
        bars.add(bar(9_500, 3_000));
        bars.add(bar(9_880, 1_000));   // D+1 gross +4%
        BacktestService.Partial p = BacktestService.runBars(bars, 3.0, 12.0, 2.0, 5, buy -> 0.5);  // 비용 0.5%
        assertThat(p.sum()[0]).isCloseTo(3.5, within(0.01));   // 4.0 - 0.5
    }
}
