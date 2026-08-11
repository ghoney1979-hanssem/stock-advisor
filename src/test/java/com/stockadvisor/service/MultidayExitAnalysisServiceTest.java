package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import com.stockadvisor.service.MultidayExitAnalysisService.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

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
        MultidayExitAnalysisService svc = new MultidayExitAnalysisService(repo, 0.22, 3, 20);
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
}
