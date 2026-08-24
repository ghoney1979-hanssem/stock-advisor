package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전략 I(인버스=지수약세): 인버스 코드 + 대응 지수 당일 약세(-minDrop 이하)일 때만 진입. 볼륨 무관.
 */
class InverseIndexStrategyTest {

    private InverseIndexStrategy strategy(KisApiClient kis) {
        return new InverseIndexStrategy(kis, true, 1.0, 4.0, "114800:0001,251340:1001");
    }

    private SignalResult signal() {
        return new SignalResult(0.5, 0.0, 2500, 1_000_000, false, false, false, 0, false, false, false);   // volumeSpike=false여도 무관
    }

    private StrategyContext ctx(String code, boolean inverse) {
        return new StrategyContext(code, signal(), 100.0, RecommendationType.BUY, null, inverse, false);
    }

    @Test
    void 지수_약세면_인버스_진입() {
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);   // 코스닥 -2.3% (약세)
        assertThat(strategy(kis).rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 지수_약세아니면_INDEX_NOT_WEAK() {
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-0.4);   // -0.4% > -1.0 → 약세 아님
        assertThat(strategy(kis).rejectReason(ctx("251340", true))).isEqualTo("INDEX_NOT_WEAK");
    }

    @Test
    void 지수_약세여도_회복중이면_INDEX_RECOVERING() {
        // 갭다운 후 회복 중(-2.3%지만 최근 10분 +0.5%) — 인버스 고점매수 회피(반등하는 칼날의 거울상)
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(0.5, 0.8, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("INDEX_RECOVERING");
    }

    @Test
    void 순간_음전이어도_30분_회복중이면_INDEX_RECOVERING() {
        // 2026-07-16 휩쏘 재현: 회복장(mom30 +0.8)에서 mom10 순간 음전(-0.1)만 보고 재진입 →
        // 고점매수 후 반등에 손절 반복. 순간·지속 모두 하락/보합이어야 진입.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.1, 0.8, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("INDEX_RECOVERING");
    }

    @Test
    void 반등일_fade가_청산선_아래까지_내려오면_진입() {
        // 2026-07-22 확대창(상한 -1% → +2%)은 살아 있다 — 단 2026-08-24부터 청산선(-recovery 0.5%)이 하한이다.
        // 아침 +2.8% 찍고 -0.8%까지 밀림: 평상일 상한(-1%) 위라 확대창이 있어야 통과하고, 청산선(-0.5%) 아래라
        // 사자마자 팔리지 않는다 = 확대창이 실제로 열어주는 유효 구간.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(2.8, -0.8);   // 스캔 1회차 고점 → 2회차 fade
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.3, -0.6, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

        s.rejectReason(ctx("251340", true));   // 1회차(+2.8%) — 당일 고점 기록
        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 반등일_fade_확인을_통과해도_지수가_청산선_위면_EXIT_ACTIVE() {
        // 2026-08-24 실측 재현(251340 3사이클 1~2분 보유 전패): 코스닥 고점 +2.37% → 진입 +1.28%.
        // 고점 대비 1.09%p라 fade 확인(1.0%p)은 통과하지만, 그 레벨은 이미 청산 트리거(지수 > -0.5%)라
        // 다음 청산 틱에 "지수회복"으로 팔린다 = 왕복비용만 내는 진입. 진입 단계에서 막아야 한다.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(2.37, 1.28);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.3, -0.6, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);

        s.rejectReason(ctx("251340", true));   // 1회차(+2.37%) — 당일 고점 기록
        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("EXIT_ACTIVE");
    }

    @Test
    void 겹침차단_비활성이면_종전대로_플러스권_fade_진입() {
        // SIGNAL_INVERSE_BLOCK_WHEN_EXIT_ACTIVE=false → 2026-07-22~08-20 동작 그대로(원복 경로 보장).
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(2.8, 1.5);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.3, -0.6, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setBlockWhenExitActive(false);

        s.rejectReason(ctx("251340", true));
        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 겹침판정은_청산_레벨분기와_부등호가_같다() {
        // PositionExitService.inverseExitReason 의 레벨 분기(chg > -recovery → 청산)와 정확히 대칭.
        // 경계(chg == -recovery)는 청산이 발사되지 않으므로 진입 허용이어야 한다.
        assertThat(InverseIndexStrategy.exitAlreadyActive(-0.49, 0.5)).isTrue();
        assertThat(InverseIndexStrategy.exitAlreadyActive(-0.50, 0.5)).isFalse();   // 경계 = 허용
        assertThat(InverseIndexStrategy.exitAlreadyActive(-0.51, 0.5)).isFalse();
        assertThat(InverseIndexStrategy.exitAlreadyActive(1.28, 0)).isFalse();      // 0=비활성
    }

    @Test
    void 반등일이어도_고점_근처면_NOT_FADING() {
        // 2026-08-20 실측 재현: 코스닥이 하루 종일 +1.7~2.3%에 머물렀는데 확대창(-0.5%~+2%)이 열려 있어
        // 진입 즉시 청산 트리거(지수 > -0.5%)에 걸리는 왕복이 11차까지 반복(-3,711원).
        // 고점 +2.3% 대비 현재 +1.84% = 0.46%p차 → fade 미진행이므로 보류돼야 한다.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(2.3, 1.84);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);

        s.rejectReason(ctx("251340", true));   // 1회차(+2.3%) — 당일 고점 기록
        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("NOT_FADING");
    }

    @Test
    void 반등일_fade_미확인이어도_진짜_약세면_진입() {
        // fade 확인 요건은 '확대창에 기대야만 통과할 후보'에만 적용 — 평상일 상한(-1%) 아래로 이미 꺾인
        // 진짜 약세는 확대창과 무관하므로 막으면 안 된다(회귀 방지).
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);   // 고점도 -2.3 → fade 0%p지만 진짜 약세
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);

        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void fade_확인_비활성이면_종전동작() {
        // fadeConfirmPct=0 → 2026-07-22 도입분 그대로(반등일이면 고점 근처여도 확대창 통과).
        // ⚠️ 값이 -0.8%인 이유: 확대창 통과만 보려는 테스트라 청산선(-0.5%) 겹침 가드에 걸리지 않는 구간을 쓴다
        //    (평상일 상한 -1% 위 = 확대창 없이는 못 통과, 청산선 아래 = EXIT_ACTIVE 아님).
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-0.8);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setFadeConfirmPct(0);

        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 평상일_플러스권은_INDEX_NOT_WEAK() {
        // 반등일 아니면 상한 -1% 그대로 — 강세장 눌림(+1.5%) 진입 안 함(휩쏘 회피)
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(1.5);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(false);
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);

        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("INDEX_NOT_WEAK");
    }

    @Test
    void 지수_낙폭_과대면_INDEX_TOO_DEEP() {
        // 2026-07-20 실측: 지수 -4% 진행 후 진입 4건 전패(인버스 고점매수) — 폭락 초입만 허용
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-4.5);
        assertThat(strategy(kis).rejectReason(ctx("251340", true))).isEqualTo("INDEX_TOO_DEEP");
    }

    @Test
    void 지수_약세이고_하락중이면_진입() {
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.4, -1.1, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 흐름_미가용이면_기존동작으로_진입() {
        // 장초 분봉 부족 등 — degrade open(당일 약세만으로 진입, 필터가 데이터 실패로 막지 않음)
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(-2.3);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(null, null, null, false));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

        assertThat(s.rejectReason(ctx("251340", true))).isNull();
    }

    @Test
    void 인버스코드_아니면_NOT_INVERSE() {
        KisApiClient kis = mock(KisApiClient.class);
        assertThat(strategy(kis).rejectReason(ctx("005930", false))).isEqualTo("NOT_INVERSE");
    }

    @Test
    void 볼륨_요구_안함() {
        assertThat(strategy(mock(KisApiClient.class)).requiresVolumeSpike()).isFalse();
    }

    @Test
    void 비활성이면_DISABLED() {
        InverseIndexStrategy s = new InverseIndexStrategy(mock(KisApiClient.class), false, 1.0, 4.0, "114800:0001,251340:1001");
        assertThat(s.rejectReason(ctx("251340", true))).isEqualTo("DISABLED");
    }
}
