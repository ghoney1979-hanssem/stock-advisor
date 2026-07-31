package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 스윙 청산 선택 — fail-closed: 트레일이 익일보유보다 margin 넘게 낫고 트리거 충분할 때만 채택, 아니면 0(보유).
 */
class SwingExitProviderTest {

    private final SwingTrailAnalysisService analysis = mock(SwingTrailAnalysisService.class);

    private SwingExitProvider svc(List<SwingTrailAnalysisService.StrategySwingTrail> rows) {
        lenient().when(analysis.analyze()).thenReturn(rows);
        return new SwingExitProvider(analysis, true, 20, 0.2, 30);
    }

    private SwingTrailAnalysisService.MethodStat m(String label, int pct, int triggered, double net) {
        return new SwingTrailAnalysisService.MethodStat(label, pct, 150, triggered, net, 60.0);
    }

    private SwingTrailAnalysisService.StrategySwingTrail c(SwingTrailAnalysisService.MethodStat... methods) {
        return new SwingTrailAnalysisService.StrategySwingTrail("MEAN_REVERSION_C", List.of(methods), "");
    }

    @Test
    void 트레일이_보유보다_충분히_나으면_채택() {
        SwingExitProvider p = svc(List.of(c(
                m("익일보유", 0, 0, 5.0),
                m("트레일 3%", 3, 25, 5.3),
                m("트레일 5%", 5, 30, 6.0),   // 최선 + margin·트리거 충족
                m("트레일 7%", 7, 30, 5.5))));
        assertThat(p.trailPct("MEAN_REVERSION_C")).isEqualTo(5.0);
    }

    @Test
    void 트레일이_보유보다_안나으면_보유() {
        SwingExitProvider p = svc(List.of(c(
                m("익일보유", 0, 0, 6.5),
                m("트레일 3%", 3, 30, 5.0),
                m("트레일 5%", 5, 30, 5.8))));   // 다 보유보다 나쁨
        assertThat(p.trailPct("MEAN_REVERSION_C")).isEqualTo(0.0);   // 익일보유 유지
    }

    @Test
    void 트리거_표본_부족이면_보유_failclosed() {
        SwingExitProvider p = svc(List.of(c(
                m("익일보유", 0, 0, 5.0),
                m("트레일 5%", 5, 10, 7.0))));   // net 높지만 트리거 10<20
        assertThat(p.trailPct("MEAN_REVERSION_C")).isEqualTo(0.0);   // fail-closed
    }

    @Test
    void margin_미달이면_보유() {
        SwingExitProvider p = svc(List.of(c(
                m("익일보유", 0, 0, 5.0),
                m("트레일 5%", 5, 30, 5.1))));   // +0.1 < margin 0.2
        assertThat(p.trailPct("MEAN_REVERSION_C")).isEqualTo(0.0);
    }
}
