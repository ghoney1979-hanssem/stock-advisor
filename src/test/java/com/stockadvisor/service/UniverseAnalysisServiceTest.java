package com.stockadvisor.service;

import com.stockadvisor.domain.UniverseSnapshot;
import com.stockadvisor.repository.UniverseSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 유니버스 횡단면 분석 — base rate 대비 lift, 단일일 클러스터 제외, 시간분할, 미채점 표본 제외.
 */
class UniverseAnalysisServiceTest {

    private final UniverseSnapshotRepository repo = mock(UniverseSnapshotRepository.class);

    private UniverseAnalysisService svc(List<UniverseSnapshot> rows) {
        when(repo.findAll()).thenReturn(rows);
        ExecutionCostModel cost = mock(ExecutionCostModel.class);
        lenient().when(cost.estimateRoundTripSlippagePct(anyLong())).thenReturn(0.0);
        return new UniverseAnalysisService(repo, cost, 0.0);   // 비용 0 → net = gross로 검증 단순화
    }

    /** 종가수익 closePct%인 스냅샷 한 줄. volumeSpike로 두 모집단을 가른다. */
    private UniverseSnapshot snap(String date, String code, boolean spike, double closePct) {
        long price = 10_000;
        UniverseSnapshot s = new UniverseSnapshot(date, "09:30", code, "KOSPI", price);
        s.setFeatures(1.0, 0.5, spike ? 3.0 : 0.8, spike, 3.0, -5.0, 1.0, 1.0, false, false, false);
        s.setPriceClose(Math.round(price * (1 + closePct / 100.0)));
        return s;
    }

    private static final String[] DAYS = {"20260818", "20260819", "20260820", "20260821"};

    @Test
    void 저가주와_하루_30퍼센트_초과_이동은_채점에서_제외되고_caveat로_노출된다() {
        // 2026-08-29 실측 재현: 1,000원 미만 4종목의 +67~+150% 이동이 ATR<2 bucket을 +3.81%로 부풀렸다.
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(snap(DAYS[i % 4], "A" + i, true, 1.0));
        UniverseSnapshot penny = new UniverseSnapshot("20260820", "09:30", "PENNY", "KOSPI", 500);
        penny.setFeatures(0, 0, 0.8, false, 0.5, -5, 1, 1, false, false, false);
        penny.setPriceClose(1250);                                            // +150% — 저가주
        UniverseSnapshot jump = snap("20260820", "JUMP", true, 45.0);         // 정상가지만 ±30% 초과
        rows.add(penny); rows.add(jump);

        UniverseAnalysisService.UniverseReport r =
                svc(rows).analyze(30, "close", null, null, 20, "20260818", null);

        assertThat(r.scored()).isEqualTo(40);
        assertThat(r.baseNetPct()).isCloseTo(1.0, within(1e-6));             // 허수 두 행이 base를 흔들지 않는다
        assertThat(r.caveats()).anyMatch(c -> c.contains("2행은 채점 제외"));
    }

    @Test
    void 자격_판정은_순수함수다() {
        assertThat(UniverseAnalysisService.excludedFromUniverse(500, 600, 1000)).isTrue();      // 저가주
        assertThat(UniverseAnalysisService.excludedFromUniverse(10_000, 13_100, 1000)).isTrue(); // +31%
        assertThat(UniverseAnalysisService.excludedFromUniverse(10_000, 6_900, 1000)).isTrue();  // −31%
        assertThat(UniverseAnalysisService.excludedFromUniverse(10_000, 12_900, 1000)).isFalse(); // +29% 상한가 근접은 정상
        assertThat(UniverseAnalysisService.excludedFromUniverse(1000, 1000, 1000)).isFalse();    // 경계 포함
    }

    @Test
    void 급증_모집단의_lift를_전체_base_rate_대비로_계산한다() {
        // 이 분석의 존재 이유: "거래량 급증 스크리닝이 실제로 나은가"는 급증 밖 표본이 있어야만 물을 수 있다.
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(snap(DAYS[i % 4], "A" + i, true, 2.0));    // 급증 +2%
        for (int i = 0; i < 300; i++) rows.add(snap(DAYS[i % 4], "B" + i, false, -1.0));  // 미급증 −1%

        UniverseAnalysisService.UniverseReport r =
                svc(rows).analyze(30, "close", null, null, 20, "20260818", null);

        // base = (100×2 + 300×−1)/400 = −0.25%
        assertThat(r.baseNetPct()).isCloseTo(-0.25, within(1e-6));
        assertThat(r.scored()).isEqualTo(400);

        UniverseAnalysisService.FeatureSlice spike = r.features().stream()
                .filter(f -> f.feature().equals("거래량급증")).findFirst().orElseThrow();
        UniverseAnalysisService.Bucket hit = spike.buckets().stream()
                .filter(b -> b.range().startsWith("급증")).findFirst().orElseThrow();
        UniverseAnalysisService.Bucket miss = spike.buckets().stream()
                .filter(b -> b.range().equals("미급증")).findFirst().orElseThrow();

        assertThat(hit.netAvgPct()).isCloseTo(2.0, within(1e-6));
        assertThat(hit.liftNetPct()).isCloseTo(2.25, within(1e-6));   // 2.0 − (−0.25)
        assertThat(hit.sharePct()).isCloseTo(25.0, within(1e-6));     // 유니버스의 1/4
        assertThat(miss.liftNetPct()).isCloseTo(-0.75, within(1e-6));
        assertThat(r.highlights()).anyMatch(b -> b.range().startsWith("급증"));
    }

    @Test
    void 단일일_클러스터_bucket은_highlights에서_제외된다() {
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) rows.add(snap(DAYS[i % 4], "A" + i, false, 0.0));   // 기준선
        for (int i = 0; i < 60; i++) rows.add(snap("20260818", "S" + i, true, 5.0));     // 하루에 몰린 대박

        UniverseAnalysisService.UniverseReport r =
                svc(rows).analyze(30, "close", null, null, 20, "20260818", null);

        UniverseAnalysisService.Bucket hit = r.features().stream()
                .filter(f -> f.feature().equals("거래량급증")).findFirst().orElseThrow()
                .buckets().stream().filter(b -> b.range().startsWith("급증")).findFirst().orElseThrow();

        assertThat(hit.maxDaySharePct()).isCloseTo(100.0, within(1e-6));
        assertThat(hit.clustered()).isTrue();
        assertThat(hit.liftNetPct()).isPositive();                       // 수치는 좋아 보여도
        assertThat(r.highlights()).noneMatch(b -> b.range().startsWith("급증"));   // 랭킹엔 안 올림
    }

    @Test
    void since_until로_전후반을_갈라_lift_부호_반전을_잡아낸다() {
        // 전반엔 급증이 이기고 후반엔 지는 불안정 신호 — 합치면 상쇄돼 lift 0으로 보인다.
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(snap(DAYS[i % 2], "A" + i, true, 3.0));           // 전반 급증 +3%
        for (int i = 0; i < 30; i++) rows.add(snap(DAYS[2 + i % 2], "B" + i, true, -3.0));      // 후반 급증 −3%
        for (int i = 0; i < 60; i++) rows.add(snap(DAYS[i % 4], "C" + i, false, 0.0));          // 미급증 0%

        UniverseAnalysisService svc = svc(rows);
        assertThat(spikeNet(svc.analyze(30, "close", null, null, 20, "20260818", null)))
                .isCloseTo(0.0, within(1e-6));
        assertThat(spikeNet(svc.analyze(30, "close", null, null, 20, "20260818", "20260819")))
                .isCloseTo(3.0, within(1e-6));
        assertThat(spikeNet(svc.analyze(30, "close", null, null, 20, "20260820", null)))
                .isCloseTo(-3.0, within(1e-6));
    }

    @Test
    void 사후타깃_미수집분은_채점에서_제외되고_rows와_scored가_구분된다() {
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(snap(DAYS[i % 4], "A" + i, true, 1.0));
        for (int i = 0; i < 25; i++) {   // 종가 미수집(오늘 수집분) — 채점 불가
            UniverseSnapshot s = new UniverseSnapshot("20260821", "09:30", "P" + i, "KOSPI", 10_000);
            s.setFeatures(1, 0, 3, true, 3, -5, 1, 1, false, false, false);
            rows.add(s);
        }

        UniverseAnalysisService.UniverseReport r =
                svc(rows).analyze(30, "close", null, null, 20, "20260818", null);

        assertThat(r.rows()).isEqualTo(65);      // 필터 통과 전체
        assertThat(r.scored()).isEqualTo(40);    // 그중 채점 가능분
        assertThat(r.baseNetPct()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void 표본_적은_구간은_거래일수_경고를_응답에_싣는다() {
        List<UniverseSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(snap(DAYS[i % 4], "A" + i, true, 1.0));

        UniverseAnalysisService.UniverseReport r =
                svc(rows).analyze(30, "close", null, null, 20, "20260818", null);

        assertThat(r.distinctDays()).isEqualTo(4);
        assertThat(r.caveats()).anyMatch(c -> c.contains("거래일 4일뿐"));
        // 종가 편향 구간(20260819 이전)을 포함하면 그 경고도 함께
        assertThat(r.caveats()).anyMatch(c -> c.contains(UniverseAnalysisService.CLOSE_FIX_DATE));
    }

    /** 거래량급증 bucket의 net(테스트 축약). */
    private double spikeNet(UniverseAnalysisService.UniverseReport r) {
        return r.features().stream().filter(f -> f.feature().equals("거래량급증")).findFirst().orElseThrow()
                .buckets().stream().filter(b -> b.range().startsWith("급증")).findFirst().orElseThrow()
                .netAvgPct();
    }
}
