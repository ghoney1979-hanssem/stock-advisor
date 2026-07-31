package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.domain.VolatilityLevel;
import com.stockadvisor.notification.DiscordNotifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장 시작 알림 메시지: 시초 국면(시장별)·태세·게이트 통과(fallback·화이트리스트 마커)를 담는지 검증.
 */
class MarketOpenNotifierTest {

    private TradingPolicyProperties policy(List<String> whitelist) {
        return new TradingPolicyProperties(true, TradingMode.LIVE, 10.0, 0, 50_000, 10,
                "15:20", 60, false, whitelist, 3, 5, 0);
    }

    private MarketOpenNotifier notifier(MarketRegimeService regime, StrategyPerformanceGate gate,
                                        TradingPolicyProperties policy) {
        return new MarketOpenNotifier(regime, gate, policy, mock(DiscordNotifier.class));
    }

    @Test
    void 메시지에_국면과_게이트통과_전략이_담긴다() {
        MarketRegimeService regime = mock(MarketRegimeService.class);
        when(regime.all()).thenReturn(List.of(
                new MarketRegimeService.MarketRegime("KOSPI", "069500", MarketTrend.BULL, VolatilityLevel.LOW,
                        100.0, 98.0, 0.5, 0.8, 3, "20260703", true),
                new MarketRegimeService.MarketRegime("KOSDAQ", "229200", MarketTrend.BEAR, VolatilityLevel.HIGH,
                        50.0, 52.0, -0.4, 2.3, 3, "20260703", true)));

        StrategyPerformanceGate gate = mock(StrategyPerformanceGate.class);
        when(gate.evaluateAll()).thenReturn(List.of(
                // KOSPI: B 통과(화이트리스트 포함), E fallback 통과(화이트리스트 미포함)
                new StrategyPerformanceGate.GateDecision("VOLUME_LEADING_B", true, "통과", 160, 0.65, "BULL", "KOSPI", false),
                new StrategyPerformanceGate.GateDecision("BREAKOUT_E", true, "fallback통과", 55, 0.9, "BULL", "KOSPI", true),
                // KOSDAQ: C 차단
                new StrategyPerformanceGate.GateDecision("MEAN_REVERSION_C", false, "성과 미달", 39, -1.3, "BEAR", "KOSDAQ", false)));

        MarketOpenNotifier n = notifier(regime, gate, policy(List.of("VOLUME_LEADING_B")));
        String msg = n.buildMessage();

        assertThat(msg).contains("장 시작");
        assertThat(msg).contains("KOSPI 강세·저변동");
        assertThat(msg).contains("KOSDAQ 약세·고변동");
        assertThat(msg).contains("모드 LIVE");
        assertThat(msg).contains("[정적 화이트리스트] VOLUME_LEADING_B");
        // KOSPI 게이트 통과: B(화이트리스트 → 마커 없음), E(fallback축소 + 화이트리스트 미포함 → *)
        assertThat(msg).contains("VOLUME_LEADING_B");
        assertThat(msg).contains("BREAKOUT_E(fallback축소)*");
        // KOSDAQ은 통과 없음 → -
        assertThat(msg).contains("KOSDAQ: -");
        assertThat(msg).contains("투자권유가 아닙니다");
    }
}
