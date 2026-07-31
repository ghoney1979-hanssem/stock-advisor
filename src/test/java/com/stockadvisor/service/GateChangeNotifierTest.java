package com.stockadvisor.service;

import com.stockadvisor.notification.DiscordNotifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 게이트 변화 알림: baseline은 무알림, 전이(열림/닫힘/fallback↔정상)만 통지.
 */
class GateChangeNotifierTest {

    private StrategyPerformanceGate.GateDecision dec(String strategy, String market, boolean allowed, boolean fallback) {
        return new StrategyPerformanceGate.GateDecision(strategy, allowed, "r", 0, null, null, market, fallback);
    }

    @Test
    void computeChanges_전이만_설명으로_반환() {
        GateChangeNotifier n = new GateChangeNotifier(mock(StrategyPerformanceGate.class), mock(DiscordNotifier.class));

        Map<String, String> prev = Map.of(
                "KOSPI:VOLUME_LEADING_B", GateChangeNotifier.LIVE,
                "KOSDAQ:MEAN_REVERSION_C", GateChangeNotifier.LIVE,
                "KOSPI:BREAKOUT_E", GateChangeNotifier.CLOSED);
        Map<String, String> cur = Map.of(
                "KOSPI:VOLUME_LEADING_B", GateChangeNotifier.FALLBACK,   // 정상 → fallback축소 🔁
                "KOSDAQ:MEAN_REVERSION_C", GateChangeNotifier.CLOSED,    // 진입가능 → 차단 🔴
                "KOSPI:BREAKOUT_E", GateChangeNotifier.LIVE);            // 차단 → 진입가능 🟢

        List<String> changes = n.computeChanges(prev, cur);

        assertThat(changes).hasSize(3);
        assertThat(changes).anyMatch(s -> s.contains("BREAKOUT_E") && s.contains("🟢") && s.contains("차단 → 진입가능"));
        assertThat(changes).anyMatch(s -> s.contains("MEAN_REVERSION_C") && s.contains("🔴") && s.contains("→ 차단"));
        assertThat(changes).anyMatch(s -> s.contains("VOLUME_LEADING_B") && s.contains("🔁") && s.contains("fallback축소"));
    }

    @Test
    void 변화없으면_빈목록() {
        GateChangeNotifier n = new GateChangeNotifier(mock(StrategyPerformanceGate.class), mock(DiscordNotifier.class));
        Map<String, String> same = Map.of("KOSPI:VOLUME_LEADING_B", GateChangeNotifier.LIVE);
        assertThat(n.computeChanges(same, same)).isEmpty();
    }

    @Test
    void baseline은_무알림_그다음_변화만_통지() {
        StrategyPerformanceGate gate = mock(StrategyPerformanceGate.class);
        DiscordNotifier discord = mock(DiscordNotifier.class);
        GateChangeNotifier n = new GateChangeNotifier(gate, discord);

        // 1차: baseline — B만 LIVE
        when(gate.evaluateAll()).thenReturn(List.of(
                dec("VOLUME_LEADING_B", "KOSPI", true, false),
                dec("BREAKOUT_E", "KOSPI", false, false)));
        n.checkAndNotify();
        verify(discord, never()).send(anyString());   // baseline 무알림

        // 2차: E가 새로 열림 → 알림 1회
        when(gate.evaluateAll()).thenReturn(List.of(
                dec("VOLUME_LEADING_B", "KOSPI", true, false),
                dec("BREAKOUT_E", "KOSPI", true, true)));   // 차단 → fallback축소
        n.checkAndNotify();
        verify(discord, times(1)).send(anyString());

        // 3차: 변화 없음 → 추가 알림 없음
        n.checkAndNotify();
        verify(discord, times(1)).send(anyString());
    }
}
