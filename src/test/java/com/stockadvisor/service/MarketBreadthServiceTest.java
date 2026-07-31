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

    private MarketBreadthService svc() {
        when(companyRepo.findAll()).thenReturn(List.of(
                new Company("A", "a", null, "KOSPI"),
                new Company("B", "b", null, "KOSPI"),
                new Company("C", "c", null, "KOSDAQ")));
        return new MarketBreadthService(companyRepo);
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
