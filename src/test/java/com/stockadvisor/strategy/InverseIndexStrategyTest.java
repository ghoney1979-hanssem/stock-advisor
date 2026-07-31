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
    void 반등일이면_플러스권_fade_진입_허용() {
        // 2026-07-22 확대: REBOUND_DAY 성립일(당일 고점 +2%↑ & 기저 비강세)엔 상한 -1% → +2%.
        // 아침 +2.4% 찍고 +1.5%로 꺾여 내려오는 중(mom10/30 동시 하락) → fade 진입.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchIndexChangeRate("1001")).thenReturn(1.5);
        com.stockadvisor.service.MarketRegimeService regime = mock(com.stockadvisor.service.MarketRegimeService.class);
        when(regime.isReboundDay("KOSDAQ", 2.0)).thenReturn(true);
        when(regime.intradayFlow("KOSDAQ")).thenReturn(
                new com.stockadvisor.service.MarketRegimeService.IntradayFlow(-0.3, -0.6, null, true));
        InverseIndexStrategy s = strategy(kis);
        s.setRegimeService(regime);
        s.setRequireFalling(true);

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
