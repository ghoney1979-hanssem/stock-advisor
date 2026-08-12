package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Feature-space 마이닝 — bin 라벨링·단일일 점유율 코어 + 클러스터 pocket 제외 검증. */
class FeatureMiningServiceTest {

    private final TradeOutcomeRepository repo = mock(TradeOutcomeRepository.class);
    private final ExecutionCostModel cost = mock(ExecutionCostModel.class);

    private FeatureMiningService svc() {
        when(cost.estimateRoundTripSlippagePct(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0.0);
        // close horizon 검증 — resolver의 exit 경로(holdTime/sample)는 안 탐. 실 resolver + 목 deps.
        ExitHorizonPriceResolver resolver = new ExitHorizonPriceResolver(
                mock(StrategyHoldTimeProvider.class), mock(com.stockadvisor.repository.OutcomeSampleRepository.class),
                "MEAN_REVERSION_C", "nextClose");
        return new FeatureMiningService(repo, cost, resolver, 0.0);   // 비용 0 → net=gross로 검증 단순화
    }

    @Test
    void binLabel_경계_라벨링() {
        double[] e = {2, 4, 8};
        assertThat(FeatureMiningService.binLabel(1, e)).isEqualTo("<2");
        assertThat(FeatureMiningService.binLabel(3, e)).isEqualTo("2~4");
        assertThat(FeatureMiningService.binLabel(5, e)).isEqualTo("4~8");
        assertThat(FeatureMiningService.binLabel(9, e)).isEqualTo("≥8");
    }

    @Test
    void maxSharePct_최대_단일일_점유율() {
        assertThat(FeatureMiningService.maxSharePct(Map.of("a", 8, "b", 2), 10)).isCloseTo(80.0, within(1e-6));
        assertThat(FeatureMiningService.maxSharePct(Map.of(), 0)).isEqualTo(0);
    }

    private TradeOutcome vr(double volRatio, String date, int i, double retPct) {
        long buy = 10_000;
        TradeOutcome o = new TradeOutcome("SQUEEZE_BREAKOUT_H", null, "00593" + i, date, buy);
        org.springframework.test.util.ReflectionTestUtils.setField(o, "id", (long) i);   // netByOutcome 키(prod는 JPA id)
        o.setPriceClose(Math.round(buy * (1 + retPct / 100.0)));
        o.recordEntryFeatures(0, volRatio, 50, 10, 1, "KOSPI", 5000, "화학", null);
        return o;
    }

    @Test
    void 단일일_클러스터_pocket은_highlights에서_제외되고_다일_수익pocket은_표시() {
        List<TradeOutcome> rows = new ArrayList<>();
        // 거래량배수 8~15 구간: net +2%, 5거래일 분산(비클러스터)
        String[] dates = {"20260602", "20260603", "20260604", "20260605", "20260606"};
        for (int i = 0; i < 25; i++) rows.add(vr(10, dates[i % 5], i, 2.0));
        // 거래량배수 <2 구간: net -1%, 한 거래일 전부(단일일 클러스터)
        for (int i = 0; i < 25; i++) rows.add(vr(1, "20260601", 100 + i, -1.0));
        when(repo.findByAlertDateGreaterThanEqual(any())).thenReturn(rows);

        FeatureMiningService.MiningReport r = svc().mine(90, "close", null, null, 20, 80.0, false);

        // 거래량배수 feature의 두 bucket 확인
        FeatureMiningService.FeatureMining vrFeat = r.features().stream()
                .filter(f -> f.feature().equals("거래량배수")).findFirst().orElseThrow();
        FeatureMiningService.Bucket hi = vrFeat.buckets().stream()
                .filter(b -> b.range().equals("8~15")).findFirst().orElseThrow();
        FeatureMiningService.Bucket lo = vrFeat.buckets().stream()
                .filter(b -> b.range().equals("<2")).findFirst().orElseThrow();

        assertThat(hi.netAvgPct()).isCloseTo(2.0, within(1e-6));
        assertThat(hi.clustered()).isFalse();
        assertThat(hi.distinctDays()).isEqualTo(5);
        assertThat(lo.clustered()).isTrue();          // 단일일 → 클러스터
        assertThat(lo.maxDaySharePct()).isCloseTo(100.0, within(1e-6));

        // highlights엔 비클러스터 수익 pocket(8~15)만, 클러스터(<2)는 없음
        assertThat(r.highlights()).anyMatch(b -> b.feature().equals("거래량배수") && b.range().equals("8~15"));
        assertThat(r.highlights()).noneMatch(b -> b.range().equals("<2"));
    }

    @Test
    void 진입_대조군_비교_edge_계산() {
        List<TradeOutcome> rows = new ArrayList<>();
        String[] dates = {"20260602", "20260603", "20260604", "20260605", "20260606"};
        // 진입(8~15): net +2%
        for (int i = 0; i < 25; i++) rows.add(vr(10, dates[i % 5], i, 2.0));
        // 같은 8~15 pocket의 대조군(미진입): net -1%
        for (int i = 0; i < 25; i++) {
            TradeOutcome c = vr(10, dates[i % 5], 200 + i, -1.0);
            c.markControl("SCORE");
            rows.add(c);
        }
        when(repo.findByAlertDateGreaterThanEqual(any())).thenReturn(rows);

        FeatureMiningService.MiningReport r = svc().mine(90, "close", null, null, 20, 80.0, true);
        FeatureMiningService.Bucket b = r.features().stream()
                .filter(f -> f.feature().equals("거래량배수")).findFirst().orElseThrow()
                .buckets().stream().filter(x -> x.range().equals("8~15")).findFirst().orElseThrow();

        assertThat(b.netAvgPct()).isCloseTo(2.0, within(1e-6));      // 진입
        assertThat(b.controlN()).isEqualTo(25);
        assertThat(b.controlNetPct()).isCloseTo(-1.0, within(1e-6)); // 미진입
        assertThat(b.edgeVsControlPct()).isCloseTo(3.0, within(1e-6)); // 진입이 +3%p 우위
    }
}
