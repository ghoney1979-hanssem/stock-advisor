package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.repository.DailyPriceRepository;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import com.stockadvisor.service.MultidayExitAnalysisService.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 멀티데이 청산 트리거 시뮬 순수 코어 단위 테스트(Phase 2). cost=0으로 gross 검증. */
class MultidayExitAnalysisServiceTest {

    private static Path path(long buy, int[] days, long[] closes, boolean complete) {
        return new Path(buy, days, closes, complete);
    }

    @Test
    void holdToDay_N일_종가에_청산_없으면_제외() {
        Path p = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 1100, 1200, 900}, true);
        assertThat(MultidayExitAnalysisService.holdToDay(p, 3, 0).getAsDouble()).isCloseTo(-10.0, within(1e-6)); // (900-1000)/1000
        assertThat(MultidayExitAnalysisService.holdToDay(p, 2, 0).getAsDouble()).isCloseTo(20.0, within(1e-6)); // (1200-1000)/1000
        assertThat(MultidayExitAnalysisService.holdToDay(p, 7, 0).isPresent()).isFalse();           // 미수집 → 제외
    }

    @Test
    void trailing_고점_대비_되돌림_첫시점_청산() {
        // peak: D1=1200, D2 1000 ≤ 1200*0.9=1080 → 청산 @1000
        Path p = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 1200, 1000, 1300}, true);
        assertThat(MultidayExitAnalysisService.trailing(p, 10, 0).getAsDouble()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void trailing_미발동_완주면_마지막종가_미완주면_제외() {
        Path up = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 1100, 1200, 1300}, true);
        assertThat(MultidayExitAnalysisService.trailing(up, 10, 0).getAsDouble()).isCloseTo(30.0, within(1e-6)); // 완주→마지막
        Path partial = path(1000, new int[]{0, 1, 2}, new long[]{1000, 1100, 1200}, false);
        assertThat(MultidayExitAnalysisService.trailing(partial, 10, 0).isPresent()).isFalse();       // 미완주→제외
    }

    @Test
    void maExit_종가가_MA아래로_떨어진_첫시점_청산() {
        // MA3: i=3 ma=(1010+1020+900)/3=976.67, c=900<ma → 청산 @900
        Path p = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 1010, 1020, 900}, true);
        assertThat(MultidayExitAnalysisService.maExit(p, 3, 0).getAsDouble()).isCloseTo(-10.0, within(1e-6));
    }

    @Test
    void stopExit_매수대비_손절선_이하_첫시점_청산() {
        Path p = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 950, 900, 1000}, true);
        assertThat(MultidayExitAnalysisService.stopExit(p, 8, 0).getAsDouble()).isCloseTo(-10.0, within(1e-6)); // 920 이하 첫= 900
        Path noHit = path(1000, new int[]{0, 1, 2, 3}, new long[]{1000, 990, 980, 1050}, true);
        assertThat(MultidayExitAnalysisService.stopExit(noHit, 8, 0).getAsDouble()).isCloseTo(5.0, within(1e-6)); // 미발동+완주→마지막
    }

    @Test
    void 비용차감_반영() {
        Path p = path(1000, new int[]{0, 1}, new long[]{1000, 1100}, false);
        assertThat(MultidayExitAnalysisService.holdToDay(p, 1, 0.22).getAsDouble()).isCloseTo(10.0 - 0.22, within(1e-6));
    }

    @Test
    void buildPaths_outcome별_거래일오름차순_묶기_완주판정() {
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0.22, 3, 20, "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J");
        // 뒤섞인 입력 2 outcomes: #1 완주(D3 도달), #2 미완주(D2까지)
        List<OutcomeDailyMark> marks = List.of(
                new OutcomeDailyMark(1L, "INDEX_RELATIVE_D", 1000, 2, "20200106", 1200),
                new OutcomeDailyMark(1L, "INDEX_RELATIVE_D", 1000, 0, "20200102", 1000),
                new OutcomeDailyMark(1L, "INDEX_RELATIVE_D", 1000, 3, "20200107", 1300),
                new OutcomeDailyMark(1L, "INDEX_RELATIVE_D", 1000, 1, "20200103", 1100),
                new OutcomeDailyMark(2L, "INDEX_RELATIVE_D", 500, 0, "20200102", 500),
                new OutcomeDailyMark(2L, "INDEX_RELATIVE_D", 500, 1, "20200103", 520));
        List<Path> paths = svc.buildPaths(marks);
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0).days()).containsExactly(0, 1, 2, 3);   // 정렬됨
        assertThat(paths.get(0).complete()).isTrue();                  // D3=maxHold 도달
        assertThat(paths.get(1).complete()).isFalse();                 // D1까지 → 미완주
    }

    @Test
    void fullPathsOnly는_고정코호트로_비교해_코호트교체_착시를_제거한다() {
        // 실측 D의 구조 재현: 완주 표본(D+15까지)은 계속 손실인데, 짧은 horizon에만 존재하는 미완주 표본이
        // 크게 이겨서 "오래 들수록 좋아진다"로 보이는 상황. 고정 코호트로 보면 그 착시가 사라져야 한다.
        List<OutcomeDailyMark> marks = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 25; i++) {            // 완주 20개: D+1 −1%, D+3 −3% (계속 손실)
            marks.addAll(fullPath(id++, new long[]{10_000, 9_900, 9_800, 9_700}));
        }
        for (int i = 0; i < 25; i++) {            // 미완주: D+1 +6%까지만 존재(D+3 없음)
            marks.add(new OutcomeDailyMark(id, "INDEX_RELATIVE_D", 10_000, 0, "20260801", 10_000));
            marks.add(new OutcomeDailyMark(id, "INDEX_RELATIVE_D", 10_000, 1, "20260804", 10_600));
            id++;
        }
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 20, "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J");

        MultidayExitAnalysisService.MultidayExitComparison mixed = d(svc.compare(false));
        MultidayExitAnalysisService.MultidayExitComparison fixed = d(svc.compare(true));

        // 혼합 코호트: D+1이 미완주 승자에 끌려 올라감(50건 평균 +2.5%)
        assertThat(hold(mixed, 1).avgNetPct()).isCloseTo(2.5, within(1e-6));
        assertThat(hold(mixed, 1).samples()).isEqualTo(50);
        // 고정 코호트: 완주 25건만 → D+1은 그대로 −1%
        assertThat(hold(fixed, 1).avgNetPct()).isCloseTo(-1.0, within(1e-6));
        assertThat(hold(fixed, 1).samples()).isEqualTo(25);
        // outcomes/fullPaths는 두 모드에서 동일하게 '전체 기준'으로 보고돼 표본 축소를 알아볼 수 있다
        assertThat(fixed.outcomes()).isEqualTo(50);
        assertThat(fixed.fullPaths()).isEqualTo(25);
    }

    @Test
    void 단일일_클러스터는_LOO_부호반전으로_잡아_권장에서_제외한다() {
        // 2026-08-24 실측 구조 재현: 완주 코호트의 44%가 하루(20260731)인데 그날이 크게 이겨서 전체 net이 양수로
        // 보이지만, 그 하루를 빼면 부호가 뒤집힌다. 건수 비중 44%는 문턱(80%)을 통과하므로 LOO 없이는 못 잡는다.
        List<OutcomeDailyMark> marks = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 12; i++) {   // 20260731 — 하루에 12건, +10%
            marks.addAll(fullPathOn(id++, "20260731", new long[]{10_000, 11_000, 11_000, 11_000}));
        }
        String[] others = {"20260801", "20260804", "20260805", "20260806", "20260807"};
        for (String d : others) {        // 나머지 5거래일 — 각 3건, -2%
            for (int i = 0; i < 3; i++) marks.addAll(fullPathOn(id++, d, new long[]{10_000, 9_800, 9_800, 9_800}));
        }
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J");

        MultidayExitAnalysisService.MultidayExitComparison c = d(svc.compare(true));
        MultidayExitAnalysisService.MethodResult h1 = hold(c, 1);

        assertThat(h1.samples()).isEqualTo(27);
        assertThat(h1.avgNetPct()).isGreaterThan(0);              // 전체는 양수(12건×+10% 가 끌어올림)
        assertThat(h1.distinctDays()).isEqualTo(6);
        assertThat(h1.maxDaySharePct()).isCloseTo(44.44, within(0.01));   // 문턱 80% 통과 = 비중만으론 못 잡음
        assertThat(h1.topDay()).isEqualTo("20260731");
        assertThat(h1.netExTopDayPct()).isCloseTo(-2.0, within(1e-6));    // 그 하루 빼면 음수 = 부호 반전
        assertThat(h1.clustered()).isTrue();
        // 클러스터 방식은 권장 후보에서 빠진다 — 남는 비클러스터 방식이 없으면 그렇게 보고한다.
        assertThat(c.recommended()).isNotEqualTo("보유 D+1");
    }

    @Test
    void 여러_거래일에_고르게_퍼진_표본은_클러스터가_아니다() {
        List<OutcomeDailyMark> marks = new ArrayList<>();
        long id = 1;
        String[] days = {"20260801", "20260804", "20260805", "20260806", "20260807", "20260810"};
        for (String d : days) {
            for (int i = 0; i < 4; i++) marks.addAll(fullPathOn(id++, d, new long[]{10_000, 10_200, 10_200, 10_200}));
        }
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J");

        MultidayExitAnalysisService.MultidayExitComparison c = d(svc.compare(true));
        MultidayExitAnalysisService.MethodResult h1 = hold(c, 1);

        assertThat(h1.distinctDays()).isEqualTo(6);
        assertThat(h1.clustered()).isFalse();
        assertThat(c.recommended()).isEqualTo("보유 D+1");   // 비클러스터라 권장으로 채택됨
    }

    @Test
    void 진입일_미상이면_클러스터_판정을_생략한다() {
        // 구 백필분(조인 실패)은 일자 집계가 비어 판정 불가 — degrade open(clustered=false)이되
        // distinctDays=0 으로 "진단 없음"임이 드러나야 한다.
        List<OutcomeDailyMark> marks = new ArrayList<>();
        for (long id = 1; id <= 6; id++) marks.addAll(fullPath(id, new long[]{10_000, 9_900, 9_800, 9_700}));
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        // findEntryDatesByStrategy 는 Mockito 기본값(빈 리스트) — 진입일 미상 상황
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "MEAN_REVERSION_C,INDEX_RELATIVE_D,VALUE_REVERSAL_J");

        MultidayExitAnalysisService.MethodResult h1 = hold(d(svc.compare(true)), 1);
        assertThat(h1.samples()).isEqualTo(6);
        assertThat(h1.distinctDays()).isZero();
        assertThat(h1.clustered()).isFalse();
    }


    // ── 유니버스 반사실 (2026-09-07) ───────────────────────────────────
    // 🔴 계기: 12전략으로 넓힌 멀티데이 비교가 8개 전략에서 "보유 D+15"를 권장했는데, 완주 코호트의 진입일이
    //    반등 구간(7/20~8/13)에 몰려 있어 그 수치의 정체가 시장 드리프트였다. 같은 진입일로 유니버스를 그냥
    //    들고 있었으면 D+15가 +8% 수준이라 10전략 중 9개가 유니버스에 졌다. 절대 net만 보는 권장은
    //    "지수에 지는 규칙"을 채택하게 만든다.

    private MultidayExitAnalysisService withUniverse(OutcomeDailyMarkRepository repo, List<Object[]> universeRows) {
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "INDEX_RELATIVE_D");
        DailyPriceRepository daily = mock(DailyPriceRepository.class);
        when(daily.universeForwardReturns(anyString(), anyString(), anyInt(), anyLong(), anyLong()))
                .thenReturn(universeRows);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "dailyPriceRepository", daily);
        return svc;
    }

    private static Object[] uni(String date, int k, double retPct) {
        return new Object[]{date, k, retPct, 1000L};
    }

    private List<OutcomeDailyMark> spread(String[] days, long[] closes) {
        List<OutcomeDailyMark> marks = new ArrayList<>();
        long id = 1;
        for (String d : days) {
            for (int i = 0; i < 4; i++) marks.addAll(fullPathOn(id++, d, closes));
        }
        return marks;
    }

    private static final String[] SIX_DAYS =
            {"20260720", "20260721", "20260722", "20260723", "20260724", "20260727"};

    @Test
    void 시장드리프트가_만든_승자는_유니버스에_져서_권장되지_않는다() {
        // 6거래일 × 4건, 전부 D+3에 +8% — 절대 net만 보면 훌륭한 방식이다.
        List<OutcomeDailyMark> marks = spread(SIX_DAYS, new long[]{10_000, 10_200, 10_500, 10_800});
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));

        // 같은 날 유니버스를 3거래일 들고 있었으면 +10% — 즉 전략은 시장에 2%p 졌다.
        List<Object[]> universe = new ArrayList<>();
        for (String d : SIX_DAYS) { universe.add(uni(d, 1, 3.0)); universe.add(uni(d, 3, 10.0)); }

        MultidayExitAnalysisService.MultidayExitComparison c = d(withUniverse(repo, universe).compare(true));
        MultidayExitAnalysisService.MethodResult h3 = hold(c, 3);

        assertThat(h3.avgNetPct()).isCloseTo(8.0, within(1e-6));           // 절대 net은 크게 양수
        assertThat(h3.clustered()).isFalse();                              // 클러스터 가드는 통과한다
        assertThat(h3.universeNetPct()).isCloseTo(10.0, within(1e-6));
        assertThat(h3.excessVsUniversePct()).isCloseTo(-2.0, within(1e-6));
        assertThat(h3.excessSamples()).isEqualTo(24);
        assertThat(c.benchmarkAvailable()).isTrue();
        assertThat(c.recommended()).isEqualTo("유니버스 미달(초과수익>0 방식 없음)");  // 종전이라면 보유 D+3을 권장했다
    }

    @Test
    void 유니버스를_이기면_초과수익_최대_방식을_권장한다() {
        List<OutcomeDailyMark> marks = spread(SIX_DAYS, new long[]{10_000, 10_200, 10_500, 10_800});
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));

        List<Object[]> universe = new ArrayList<>();
        for (String d : SIX_DAYS) { universe.add(uni(d, 1, 1.0)); universe.add(uni(d, 3, 5.0)); }

        MultidayExitAnalysisService.MultidayExitComparison c = d(withUniverse(repo, universe).compare(true));
        assertThat(c.benchmarkAvailable()).isTrue();
        assertThat(c.recommended()).isEqualTo("보유 D+3");
        assertThat(c.recommendedExcessPct()).isCloseTo(3.0, within(1e-6));
    }

    @Test
    void 초과수익은_방식마다_실제_청산거래일로_맞춘다() {
        // 손절은 D+1에 발동해 나간다. 그걸 "3거래일 들고 있던 시장"과 비교하면 보유기간이 어긋난다.
        List<OutcomeDailyMark> marks = spread(SIX_DAYS, new long[]{10_000, 9_000, 9_100, 9_200});
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));

        List<Object[]> universe = new ArrayList<>();
        for (String d : SIX_DAYS) { universe.add(uni(d, 1, -5.0)); universe.add(uni(d, 3, 10.0)); }

        MultidayExitAnalysisService.MultidayExitComparison c = d(withUniverse(repo, universe).compare(true));
        MultidayExitAnalysisService.MethodResult stop = c.methods().stream()
                .filter(m -> m.method().equals("손절 -8%")).findFirst().orElseThrow();

        assertThat(stop.avgNetPct()).isCloseTo(-10.0, within(1e-6));        // D+1 종가 9,000에 청산
        assertThat(stop.universeNetPct()).isCloseTo(-5.0, within(1e-6));    // k=3(+10%)이 아니라 k=1(-5%)로 맞춘다
        assertThat(stop.excessVsUniversePct()).isCloseTo(-5.0, within(1e-6));
    }

    @Test
    void 벤치마크_미가용이면_종전대로_절대net으로_권장하고_그_사실을_노출한다() {
        String[] days = {"20260801", "20260804", "20260805", "20260806", "20260807", "20260810"};
        List<OutcomeDailyMark> marks = spread(days, new long[]{10_000, 10_200, 10_200, 10_200});
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findByStrategyOrderByOutcomeIdAscMarkDaysAsc(anyString())).thenReturn(marks);
        when(repo.findEntryDatesByStrategy(anyString())).thenReturn(entryDateRows(marks));
        // dailyPriceRepository 미주입 → 일봉 미적재 환경과 같다
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "INDEX_RELATIVE_D");

        MultidayExitAnalysisService.MultidayExitComparison c = d(svc.compare(true));
        assertThat(c.benchmarkAvailable()).isFalse();
        assertThat(c.recommendedExcessPct()).isNull();
        assertThat(c.recommended()).isEqualTo("보유 D+1");                   // 종전 동작 보존
        assertThat(hold(c, 1).universeNetPct()).isNull();
    }

    @Test
    void 분석대상은_설정과_마크보유_전략의_합집합이다() {
        // 🐞 수집을 넓혀도 분석 대상이 하드코딩이면 데이터가 있는데 "없는 것처럼" 보인다.
        OutcomeDailyMarkRepository repo = mock(OutcomeDailyMarkRepository.class);
        when(repo.findDistinctStrategies()).thenReturn(List.of("REVERSAL_L", "MEAN_REVERSION_C"));
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0, 3, 5, "MEAN_REVERSION_C,INDEX_RELATIVE_D");

        assertThat(svc.targetStrategies())
                .containsExactly("INDEX_RELATIVE_D", "MEAN_REVERSION_C", "REVERSAL_L");
    }

    private List<Object[]> entryDateRows(List<OutcomeDailyMark> marks) {
        Map<Long, String> byOutcome = new LinkedHashMap<>();
        for (OutcomeDailyMark m : marks) byOutcome.putIfAbsent(m.getOutcomeId(), entryDates.get(m.getOutcomeId()));
        List<Object[]> rows = new ArrayList<>();
        byOutcome.forEach((k, v) -> rows.add(new Object[]{k, v}));
        return rows;
    }

    private final Map<Long, String> entryDates = new LinkedHashMap<>();

    /** 진입일을 붙인 완주 경로(클러스터 판정 테스트용). */
    private List<OutcomeDailyMark> fullPathOn(long id, String entryDate, long[] closes) {
        entryDates.put(id, entryDate);
        return fullPath(id, closes);
    }

    private List<OutcomeDailyMark> fullPath(long id, long[] closes) {
        List<OutcomeDailyMark> out = new ArrayList<>();
        String[] dates = {"20260801", "20260804", "20260805", "20260806"};
        for (int d = 0; d < closes.length; d++) {
            out.add(new OutcomeDailyMark(id, "INDEX_RELATIVE_D", 10_000, d, dates[d], closes[d]));
        }
        return out;
    }

    private MultidayExitAnalysisService.MultidayExitComparison d(
            List<MultidayExitAnalysisService.MultidayExitComparison> rows) {
        return rows.stream().filter(r -> r.strategy().equals("INDEX_RELATIVE_D")).findFirst().orElseThrow();
    }

    private MultidayExitAnalysisService.MethodResult hold(
            MultidayExitAnalysisService.MultidayExitComparison c, int days) {
        return c.methods().stream().filter(m -> m.method().equals("보유 D+" + days)).findFirst().orElseThrow();
    }
}
