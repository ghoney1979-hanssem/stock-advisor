package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.market.dto.KisDailyPriceResponse.DailyPrice;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 멀티데이(2-3주) 일봉 종가 경로 수집(Phase 1, 측정 전용) 단위 테스트.
 * 벽시계 대신 전달된 now(마감 후)로 판정하므로 결정적.
 */
class TradeFollowUpServiceMultidayTest {

    private final TradeOutcomeRepository outcomeRepo = mock(TradeOutcomeRepository.class);
    private final OutcomeSampleRepository sampleRepo = mock(OutcomeSampleRepository.class);
    private final OutcomeDailyMarkRepository dailyRepo = mock(OutcomeDailyMarkRepository.class);
    private final KisApiClient kis = mock(KisApiClient.class);

    // 마감 후(18:00 KST) — afterClose 성립. 과거(2020) 거래일 rows는 항상 확정(isFinalized true).
    private static final Instant AFTER_CLOSE = Instant.parse("2020-01-06T09:00:00Z"); // = 18:00 Asia/Seoul

    private TradeFollowUpService service(int maxHoldDays) {
        return new TradeFollowUpService(outcomeRepo, sampleRepo, dailyRepo, kis,
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30),
                new int[]{5, 10}, new int[]{5},
                "MEAN_REVERSION_C",                                   // 스윙
                "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J", // 멀티데이
                maxHoldDays);
    }

    private static DailyPrice day(String date, String close) {
        return new DailyPrice(date, close, null, null, null, null, null);
    }

    /** 최신일이 먼저 오는 KIS 일봉 배열(D0..D+3). */
    private void stubDaily() {
        List<DailyPrice> rows = List.of(
                day("20200107", "1300"),   // D+3
                day("20200106", "1200"),   // D+2
                day("20200103", "1100"),   // D+1
                day("20200102", "1000"));  // D0(alertDate)
        when(kis.fetchDailyPrices(any())).thenReturn(new KisDailyPriceResponse("0", "ok", rows));
    }

    @Test
    void 멀티데이_전략은_D0부터_D_maxHold까지_일봉종가를_수집한다() {
        stubDaily();
        lenient().when(dailyRepo.existsByOutcomeIdAndBusinessDate(any(), any())).thenReturn(false);
        lenient().when(dailyRepo.existsByOutcomeIdAndMarkDays(any(), anyInt())).thenReturn(false);

        TradeOutcome o = new TradeOutcome("INDEX_RELATIVE_D", null, "005930", "20200102", 1000);
        o.setEntryMarket("KOSPI");

        service(3).process(o, AFTER_CLOSE);

        ArgumentCaptor<OutcomeDailyMark> cap = ArgumentCaptor.forClass(OutcomeDailyMark.class);
        verify(dailyRepo, times(4)).save(cap.capture());   // D0,D+1,D+2,D+3
        assertThat(cap.getAllValues())
                .extracting(OutcomeDailyMark::getMarkDays, OutcomeDailyMark::getClosePrice)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(0, 1000L),
                        org.assertj.core.groups.Tuple.tuple(1, 1100L),
                        org.assertj.core.groups.Tuple.tuple(2, 1200L),
                        org.assertj.core.groups.Tuple.tuple(3, 1300L));
    }

    @Test
    void 비멀티데이_전략은_일봉마크를_수집하지_않는다() {
        stubDaily();
        TradeOutcome o = new TradeOutcome("VOLUME_LEADING_B", null, "005930", "20200102", 1000);
        o.setEntryMarket("KOSPI");

        service(3).process(o, AFTER_CLOSE);

        verify(dailyRepo, never()).save(any());
    }

    @Test
    void D_maxHold까지_수집되면_추적이_완료처리된다() {
        stubDaily();
        lenient().when(dailyRepo.existsByOutcomeIdAndBusinessDate(any(), any())).thenReturn(false);
        // 마지막 거래일(D+3)이 이미 수집된 상태 → 종료조건 성립
        lenient().when(dailyRepo.existsByOutcomeIdAndMarkDays(any(), anyInt())).thenReturn(false);
        when(dailyRepo.existsByOutcomeIdAndMarkDays(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(true);

        TradeOutcome o = new TradeOutcome("VALUE_REVERSAL_J", null, "005930", "20200102", 1000);
        o.setEntryMarket("KOSPI");

        service(3).process(o, AFTER_CLOSE);

        assertThat(o.isCompleted()).isTrue();
    }
}
