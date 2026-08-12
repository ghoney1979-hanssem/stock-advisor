package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** horizon 통일 공유 resolver — 종가 horizon 매핑·스윙 horizon·exit 근접마크 선택 검증. */
class ExitHorizonPriceResolverTest {

    private final StrategyHoldTimeProvider hold = mock(StrategyHoldTimeProvider.class);
    private final OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);

    private ExitHorizonPriceResolver resolver() {
        return new ExitHorizonPriceResolver(hold, sampleRepo, "MEAN_REVERSION_C", "nextClose");
    }

    private TradeOutcome outcome(long id) {
        TradeOutcome o = new TradeOutcome("INDEX_RELATIVE_D", null, "005930", "20260810", 10_000);
        ReflectionTestUtils.setField(o, "id", id);
        o.setPriceClose(10_500L);
        o.setPriceNextClose(10_800L);
        return o;
    }

    @Test
    void resultPrice_horizon_매핑() {
        TradeOutcome o = outcome(1);
        assertThat(ExitHorizonPriceResolver.resultPrice(o, "close")).isEqualTo(10_500L);
        assertThat(ExitHorizonPriceResolver.resultPrice(o, "nextClose")).isEqualTo(10_800L);
        assertThat(ExitHorizonPriceResolver.resultPrice(o, null)).isEqualTo(10_500L);
    }

    @Test
    void horizonFor_스윙은_swingHorizon_그외는_passthrough() {
        assertThat(resolver().horizonFor("MEAN_REVERSION_C", "exit")).isEqualTo("nextClose");   // 스윙
        assertThat(resolver().horizonFor("INDEX_RELATIVE_D", "exit")).isEqualTo("exit");         // 인트라데이
        assertThat(resolver().horizonFor("INDEX_RELATIVE_D", "close")).isEqualTo("close");
    }

    @Test
    void priceFor_exit는_권장청산마크에_가장_가까운_마크가격_선택() {
        when(hold.holdMinutes(eq("INDEX_RELATIVE_D"))).thenReturn(45);
        // outcome 1: 마크 44(가까움,dist1)·48(dist3) → 44의 100 선택. outcome 2: 60만(dist15,허용내) → 200. outcome 3: 마크 없음 → null.
        when(sampleRepo.findByStrategyAndMarkMinutesBetween(eq("INDEX_RELATIVE_D"), anyInt(), anyInt())).thenReturn(List.of(
                sample(1L, 44, 100), sample(1L, 48, 110), sample(2L, 60, 200)));

        Function<TradeOutcome, Long> px = resolver().priceFor("INDEX_RELATIVE_D", "exit");
        assertThat(px.apply(outcome(1))).isEqualTo(100L);
        assertThat(px.apply(outcome(2))).isEqualTo(200L);
        assertThat(px.apply(outcome(3))).isNull();   // 마크 미수집 → 제외(fail-closed)
    }

    private OutcomeSample sample(long outcomeId, int mark, long price) {
        return new OutcomeSample(outcomeId, "INDEX_RELATIVE_D", 10_000, mark, price);
    }
}
