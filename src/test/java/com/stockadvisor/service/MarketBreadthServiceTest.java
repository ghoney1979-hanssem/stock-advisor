package com.stockadvisor.service;

import com.stockadvisor.domain.Company;
import com.stockadvisor.repository.CompanyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시장 폭 집계 — 상승비율/중앙값, 시장별 분리, 스캔 비활성 시 no-op.
 */
class MarketBreadthServiceTest {

    private final CompanyRepository companyRepo = mock(CompanyRepository.class);

    /** 커버리지 가드 0(비활성) — 기존 케이스들은 부분 기록으로 집계 로직 자체를 검증하는 게 목적. */
    private MarketBreadthService svc() {
        return svc(0);
    }

    private MarketBreadthService svc(double minCoverageRatio) {
        when(companyRepo.findAll()).thenReturn(List.of(
                new Company("A", "a", null, "KOSPI"),
                new Company("B", "b", null, "KOSPI"),
                new Company("C", "c", null, "KOSDAQ")));
        return new MarketBreadthService(companyRepo, minCoverageRatio);
    }

    @Test
    void 상승비율_중앙값_시장별() {
        MarketBreadthService s = svc();
        s.beginScan();
        s.record("A", 1.0);    // KOSPI 상승
        s.record("B", -0.5);   // KOSPI 하락
        s.record("C", 2.0);    // KOSDAQ 상승
        s.publish();

        assertThat(s.overallBreadthPct()).isCloseTo(66.67, within(0.1));   // 3중 2 상승
        assertThat(s.breadthPct("KOSPI")).isCloseTo(50.0, within(0.1));    // 2중 1
        assertThat(s.breadthPct("KOSDAQ")).isCloseTo(100.0, within(0.1));  // 1중 1
    }

    @Test
    void 스캔_비활성이면_기록안됨() {
        MarketBreadthService s = svc();
        s.record("A", 1.0);   // beginScan 없이 → 무시
        s.publish();
        assertThat(s.overallBreadthPct()).isNull();
    }

    @Test
    void 기록_0건_publish는_기존_스냅샷_유지() {
        MarketBreadthService s = svc();
        s.beginScan();
        s.record("A", 1.0);
        s.publish();
        assertThat(s.overallBreadthPct()).isCloseTo(100.0, within(0.1));

        // 마감 후 빈 스캔(세션가드로 record 0건) — 장중 스냅샷을 덮어쓰면 안 됨
        s.beginScan();
        s.publish();
        assertThat(s.overallBreadthPct()).isCloseTo(100.0, within(0.1));
    }

    @Test
    void 전수스캔_스레드가_아니면_기록안됨() throws Exception {
        MarketBreadthService s = svc();
        s.beginScan();
        s.record("A", 1.0);   // 전수 스캔 스레드(현재 스레드)
        Thread hot = new Thread(() -> s.record("C", 2.0));   // 동시 핫스캔 경로 — 무시돼야 함
        hot.start();
        hot.join();
        s.publish();
        assertThat(s.overallBreadthPct()).isCloseTo(100.0, within(0.1));   // A만 집계(1중 1 상승)
        assertThat(s.breadthPct("KOSDAQ")).isNull();
    }

    @Test
    void 커버리지_미달_부분스캔은_기존_스냅샷을_덮지_않는다() {
        // 2026-08-14 실측: 15:20 세션가드가 전수 스캔을 끊어 n=718 부분 스냅샷이 정상 n=1,334를 덮고 KOSDAQ 행이 소실됨
        MarketBreadthService s = svc(0.8);
        s.beginScan();
        s.record("A", 1.0); s.record("B", 1.0); s.record("C", 1.0);   // 3/3 = 100% → 확정
        s.publish();
        assertThat(s.overallBreadthPct()).isCloseTo(100.0, within(0.1));
        assertThat(s.breadthPct("KOSDAQ")).isCloseTo(100.0, within(0.1));

        s.beginScan();
        s.record("A", -5.0);   // 1/3 = 33% < 80% → 부분 스캔이라 유지돼야 함
        s.publish();
        assertThat(s.overallBreadthPct()).isCloseTo(100.0, within(0.1));   // 덮이지 않음
        assertThat(s.breadthPct("KOSDAQ")).isCloseTo(100.0, within(0.1));  // KOSDAQ 행도 살아있음
    }

    @Test
    void 커버리지_판정_경계() {
        assertThat(MarketBreadthService.hasEnoughCoverage(1334, 1500, 0.8)).isTrue();    // 88.9%
        assertThat(MarketBreadthService.hasEnoughCoverage(718, 1500, 0.8)).isFalse();    // 47.9% — 실측 부분 스캔
        assertThat(MarketBreadthService.hasEnoughCoverage(718, 1500, 0)).isTrue();       // 0=비활성
        assertThat(MarketBreadthService.hasEnoughCoverage(10, 0, 0.8)).isTrue();         // 유니버스 미상 → degrade open
    }

    @Test
    void 미매핑_종목은_전체에만_반영() {
        MarketBreadthService s = svc();
        s.beginScan();
        s.record("A", 1.0);
        s.record("ZZZ", -1.0);   // Company 맵에 없음 → OVERALL만
        s.publish();
        assertThat(s.describe()).extracting(MarketBreadthService.Breadth::market).contains("OVERALL", "KOSPI");
        assertThat(s.overallBreadthPct()).isCloseTo(50.0, within(0.1));   // 2중 1 상승
        assertThat(s.breadthPct("KOSPI")).isCloseTo(100.0, within(0.1));  // KOSPI엔 A만
    }
}
