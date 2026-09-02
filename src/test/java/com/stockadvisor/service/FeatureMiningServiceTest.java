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

    /**
     * 오늘 기준 상대 날짜(yyyyMMdd) — <b>절대 날짜를 상대 룩백(lookbackDays)과 함께 쓰면 테스트가 시한부가 된다.</b>
     *
     * <p>실제로 두 번 깨졌다: 2026-08-31에 클러스터 검증용 행이, 2026-09-02에 나머지 세 테스트의 표본이
     * cutoff(오늘−90일) 밖으로 밀려 bucket 자체가 사라졌다(NoSuchElementException).</p>
     */
    private static String dAgo(int daysAgo) {
        return java.time.LocalDate.now().minusDays(daysAgo)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
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
        // ⚠️ 날짜는 오늘 기준 상대값이어야 한다 — 종전엔 20260601~06을 하드코딩해 두고 lookbackDays=90으로
        // 조회했더니, 2026-08-31에 cutoff(오늘−90일)가 20260602를 넘어서면서 단일일 클러스터용 20260601 행이
        // 통째로 창 밖으로 밀려 `<2` bucket이 사라졌다(NoSuchElementException). 절대 날짜를 상대 룩백과
        // 함께 쓰면 테스트가 시한부가 된다.
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        java.time.LocalDate today = java.time.LocalDate.now();
        // 거래량배수 8~15 구간: net +2%, 5거래일 분산(비클러스터)
        String[] dates = new String[5];
        for (int d = 0; d < 5; d++) dates[d] = today.minusDays(d + 1L).format(fmt);
        for (int i = 0; i < 25; i++) rows.add(vr(10, dates[i % 5], i, 2.0));
        // 거래량배수 <2 구간: net -1%, 한 거래일 전부(단일일 클러스터)
        String clusterDay = today.minusDays(6).format(fmt);
        for (int i = 0; i < 25; i++) rows.add(vr(1, clusterDay, 100 + i, -1.0));
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
        String[] dates = {dAgo(6), dAgo(5), dAgo(4), dAgo(3), dAgo(2)};
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
        assertThat(b.controlTotalN()).isEqualTo(25);
        assertThat(b.controlCoveragePct()).isCloseTo(100.0, within(1e-6));
        assertThat(b.controlNetPct()).isCloseTo(-1.0, within(1e-6)); // 미진입
        assertThat(b.edgeVsControlPct()).isCloseTo(3.0, within(1e-6)); // 진입이 +3%p 우위
    }

    /**
     * 🐞 2026-08-14 실측 버그 회귀: 대조군 커버리지가 낮으면(exit horizon에서 대조군 exit 마크가 거의 없음)
     * 그 편향 부분집합으로 계산한 edge가 부호까지 뒤집힌다(ret5d%&lt;-5: exit −2.51%p ↔ close +0.13%p).
     * → 커버리지 미달이면 controlNet·edge는 null이고, 커버리지 자체가 응답에 실려야 한다.
     */
    @Test
    void 대조군_커버리지_미달이면_edge를_내지_않고_커버리지를_노출한다() {
        List<TradeOutcome> rows = new ArrayList<>();
        String[] dates = {dAgo(6), dAgo(5), dAgo(4), dAgo(3), dAgo(2)};
        for (int i = 0; i < 25; i++) rows.add(vr(10, dates[i % 5], i, 2.0));            // 진입 25건
        for (int i = 0; i < 25; i++) {                                                   // 대조군 25건 중
            TradeOutcome c = vr(10, dates[i % 5], 200 + i, -1.0);
            c.markControl("SCORE");
            // 20건은 해당 horizon 가격 미수집 → 커버리지 5/25 = 20% (대조군 exit 마크 부재 상황 재현)
            if (i >= 5) org.springframework.test.util.ReflectionTestUtils.setField(c, "priceClose", null);
            rows.add(c);
        }
        when(repo.findByAlertDateGreaterThanEqual(any())).thenReturn(rows);

        FeatureMiningService.Bucket b = svc().mine(90, "close", null, null, 20, 80.0, true)
                .features().stream().filter(f -> f.feature().equals("거래량배수")).findFirst().orElseThrow()
                .buckets().stream().filter(x -> x.range().equals("8~15")).findFirst().orElseThrow();

        assertThat(b.netAvgPct()).isCloseTo(2.0, within(1e-6));   // 진입 통계는 그대로
        assertThat(b.controlN()).isEqualTo(5);                    // 가격 있는 대조군만
        assertThat(b.controlTotalN()).isEqualTo(25);
        assertThat(b.controlCoveragePct()).isCloseTo(20.0, within(1e-6));
        assertThat(b.controlNetPct()).isNull();                   // 편향 부분집합 → 계산 안 함
        assertThat(b.edgeVsControlPct()).isNull();
    }

    @Test
    void since_until로_진입일_구간을_잘라_시간분할_검증이_가능하다() {
        // 전반(06-02~06-03)은 +2%, 후반(06-05~06-06)은 -2% — 전 구간을 합치면 0%로 보여
        // "엣지 없음"이 되지만, 갈라 보면 부호가 뒤집히는 불안정 pocket임이 드러난다.
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(vr(10, i % 2 == 0 ? dAgo(6) : dAgo(5), i, 2.0));
        for (int i = 0; i < 12; i++) rows.add(vr(10, i % 2 == 0 ? dAgo(3) : dAgo(2), 50 + i, -2.0));
        when(repo.findByAlertDateGreaterThanEqual(any())).thenReturn(rows);

        assertThat(net(svc().mine(90, "close", null, null, 20, 80.0, false))).isCloseTo(0.0, within(1e-6));
        // 전반만
        assertThat(net(svc().mine(90, "close", null, null, 10, 80.0, false, null, dAgo(5))))
                .isCloseTo(2.0, within(1e-6));
        // 후반만
        assertThat(net(svc().mine(90, "close", null, null, 10, 80.0, false, dAgo(3), null)))
                .isCloseTo(-2.0, within(1e-6));
    }

    @Test
    void since가_주어지면_lookbackDays_cutoff보다_우선한다() {
        List<TradeOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(vr(10, dAgo(5), i, 1.0));
        when(repo.findByAlertDateGreaterThanEqual(any())).thenReturn(rows);

        FeatureMiningService.MiningReport r = svc().mine(90, "close", null, null, 10, 80.0, false, dAgo(6), null);
        assertThat(r.since()).isEqualTo(dAgo(6));   // cutoff가 since로 대체돼 응답에 노출
        assertThat(r.until()).isNull();
    }

    /** 거래량배수 8~15 bucket의 진입 net(테스트 전용 축약). */
    private double net(FeatureMiningService.MiningReport r) {
        return r.features().stream().filter(f -> f.feature().equals("거래량배수")).findFirst().orElseThrow()
                .buckets().stream().filter(b -> b.range().equals("8~15")).findFirst().orElseThrow()
                .netAvgPct();
    }

    @Test
    void 겹치는_거래일_구간만_edge에_쓴다() {
        // 진입군은 20260701~20260825, 대조군은 20260825 하루만 태깅된 축(체결강도가 실제로 이 상태였다)
        assertThat(FeatureMiningService.overlapWindow(
                List.of("20260701", "20260825"), List.of("20260825")))
                .containsExactly("20260825", "20260825");
        // 완전히 겹치면 그대로
        assertThat(FeatureMiningService.overlapWindow(
                List.of("20260701", "20260825"), List.of("20260702", "20260820")))
                .containsExactly("20260702", "20260820");
        // 구간이 어긋나 겹치지 않으면 null(=edge 산출 불가)
        assertThat(FeatureMiningService.overlapWindow(
                List.of("20260701", "20260710"), List.of("20260801"))).isNull();
        // 한쪽이 비면 null
        assertThat(FeatureMiningService.overlapWindow(List.of(), List.of("20260825"))).isNull();
        assertThat(FeatureMiningService.overlapWindow(List.of("20260825"), List.of())).isNull();
    }
}
