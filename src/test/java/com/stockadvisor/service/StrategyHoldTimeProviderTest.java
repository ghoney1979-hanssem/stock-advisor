package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.TradingMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략별 청산 보유시간 — 고정 설정값(2026-09-08, 적응형 자동산출 폐기). 지정 없으면 전역 fallback.
 */
class StrategyHoldTimeProviderTest {

    private static final int FIXED_HOLD = 60;

    private TradingPolicyProperties policy() {
        return new TradingPolicyProperties(true, TradingMode.DRY_RUN, 10.0, 0, 50_000, 10,
                "15:20", FIXED_HOLD, true, List.of(), 3, 5, 0);
    }

    private StrategyHoldTimeProvider providerWithCsv(String csv) {
        StrategyHoldTimeProvider p = new StrategyHoldTimeProvider(policy());
        try {
            java.lang.reflect.Field f = StrategyHoldTimeProvider.class.getDeclaredField("holdMinutesPerStrategyCsv");
            f.setAccessible(true);
            f.set(p, csv);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    @Test
    void 전략별_지정값이_있으면_그값을_반환한다() {
        StrategyHoldTimeProvider p = providerWithCsv("REVERSAL_L:240, RSI_REVERSAL_G:180");

        assertThat(p.holdMinutes("REVERSAL_L")).isEqualTo(240);
        assertThat(p.holdMinutes("RSI_REVERSAL_G")).isEqualTo(180);
    }

    @Test
    void 미지정_전략은_전역_고정값() {
        StrategyHoldTimeProvider p = providerWithCsv("REVERSAL_L:240");

        assertThat(p.holdMinutes("MOMENTUM_A")).isEqualTo(FIXED_HOLD);
    }

    @Test
    void csv_미지정이면_전_전략_전역_고정값() {
        StrategyHoldTimeProvider p = new StrategyHoldTimeProvider(policy());

        assertThat(p.holdMinutes("MEAN_REVERSION_C")).isEqualTo(FIXED_HOLD);
    }

    @Test
    void describe는_지정전략과_fallback전략을_구분해_노출한다() {
        StrategyHoldTimeProvider p = providerWithCsv("MEAN_REVERSION_C:120");

        List<StrategyHoldTimeProvider.HoldInfo> all = p.describe();

        StrategyHoldTimeProvider.HoldInfo c = all.stream()
                .filter(h -> h.strategy().equals("MEAN_REVERSION_C")).findFirst().orElseThrow();
        assertThat(c.fixed()).isTrue();
        assertThat(c.holdMinutes()).isEqualTo(120);

        StrategyHoldTimeProvider.HoldInfo a = all.stream()
                .filter(h -> h.strategy().equals("MOMENTUM_A")).findFirst().orElseThrow();
        assertThat(a.fixed()).isFalse();
        assertThat(a.holdMinutes()).isEqualTo(FIXED_HOLD);
    }

    @Test
    void csv_오타는_무시하고_전역값으로_degrade() {
        // 설정 실수로 보유시간이 0이 되면 진입 즉시 청산되므로, 알 수 없는 값은 종전 동작(전역값)으로 되돌린다.
        assertThat(StrategyHoldTimeProvider.parseHoldMinutes("REVERSAL_L:abc,BAD_ENTRY,MOMENTUM_A:0,:240"))
                .isEmpty();
        assertThat(StrategyHoldTimeProvider.parseHoldMinutes("REVERSAL_L:240, RSI_REVERSAL_G:180 "))
                .containsEntry("REVERSAL_L", 240)
                .containsEntry("RSI_REVERSAL_G", 180);
        assertThat(StrategyHoldTimeProvider.parseHoldMinutes(null)).isEmpty();
        assertThat(StrategyHoldTimeProvider.parseHoldMinutes("")).isEmpty();
    }
}
