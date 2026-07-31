package com.stockadvisor.service;

import com.stockadvisor.config.properties.ExitMethodProperties;
import com.stockadvisor.domain.ExitMethodType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 적응형 청산방식 선택: 표본 충분하면 추천 방식, 부족/비활성/미산출이면 시간기반(TIME).
 */
class ExitMethodProviderTest {

    private ExitMethodProvider provider(boolean enabled, int minSamples, List<ExitStrategyService.BestExit> rec) {
        ExitStrategyService ess = mock(ExitStrategyService.class);
        when(ess.recommend()).thenReturn(rec);
        return new ExitMethodProvider(ess, new ExitMethodProperties(enabled, minSamples, 30, 3));
    }

    private ExitStrategyService.BestExit be(String s, ExitMethodType t, double p, int n) {
        return new ExitStrategyService.BestExit(s, t, p, 0, n);
    }

    @Test
    void 표본충분_추천방식_채택() {
        ExitMethodProvider p = provider(true, 30, List.of(be("VOLUME_LEADING_B", ExitMethodType.TRAILING, 2.0, 50)));

        ExitStrategyService.BestExit m = p.methodFor("VOLUME_LEADING_B");

        assertThat(m.type()).isEqualTo(ExitMethodType.TRAILING);
        assertThat(m.param()).isEqualTo(2.0);
    }

    @Test
    void 표본부족이면_TIME_fallback() {
        ExitMethodProvider p = provider(true, 30, List.of(be("MEAN_REVERSION_C", ExitMethodType.VWAP, 0, 10)));

        assertThat(p.methodFor("MEAN_REVERSION_C").type()).isEqualTo(ExitMethodType.TIME);   // 10 < 30
    }

    @Test
    void 미산출_전략은_TIME() {
        ExitMethodProvider p = provider(true, 30, List.of(be("VOLUME_LEADING_B", ExitMethodType.TRAILING, 2.0, 50)));

        assertThat(p.methodFor("MOMENTUM_A").type()).isEqualTo(ExitMethodType.TIME);
    }

    @Test
    void 비활성이면_분석없이_TIME() {
        ExitStrategyService ess = mock(ExitStrategyService.class);
        ExitMethodProvider p = new ExitMethodProvider(ess, new ExitMethodProperties(false, 30, 30, 3));

        assertThat(p.methodFor("VOLUME_LEADING_B").type()).isEqualTo(ExitMethodType.TIME);
        verify(ess, never()).recommend();
    }
}
