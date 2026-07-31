package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 티어드 스캔 핫셋 — 볼륨 상위 N + 이벤트 상위 M(캡) + 인버스, 하한/비활성 처리.
 */
class HotWatchServiceTest {

    private HotWatchService svc(int size, int eventMax, double minRatio) {
        return new HotWatchService(size, eventMax, minRatio, "114800,251340");
    }

    @Test
    void 거래량배수_상위N_인버스포함() {
        HotWatchService s = svc(2, 50, 1.3);
        s.beginScan();
        s.record("A", 3.0, false);   // 상위
        s.record("B", 2.0, false);   // 상위
        s.record("C", 1.5, false);   // 3순위 → N=2라 탈락
        s.record("D", 1.0, false);   // 하한 미만 → 제외
        s.publish();

        var hot = s.hotCodes();
        assertThat(hot).contains("A", "B", "114800", "251340");
        assertThat(hot).doesNotContain("C", "D");
    }

    @Test
    void 볼륨무관_이벤트트리거는_volumeRatio무관_편입() {
        HotWatchService s = svc(1, 50, 1.3);
        s.beginScan();
        s.record("A", 3.0, false);   // 볼륨 상위(N=1)
        s.record("E", 0.5, true);    // 거래량 조용하지만 이벤트 → 편입돼야
        s.record("F", 0.8, false);   // 조용 + 이벤트 없음 → 제외
        s.publish();

        var hot = s.hotCodes();
        assertThat(hot).contains("A", "E", "114800", "251340");
        assertThat(hot).doesNotContain("F");
    }

    @Test
    void 이벤트_상위M_캡_반등장_폭증방지() {
        HotWatchService s = svc(50, 2, 1.3);   // eventMax=2
        s.beginScan();
        s.record("X", 0.3, true);
        s.record("Y", 0.5, true);
        s.record("Z", 0.7, true);   // 이벤트 3개지만 상위 2(volumeRatio순)만
        s.publish();

        var hot = s.hotCodes();
        assertThat(hot).contains("Z", "Y");        // vr 상위 2
        assertThat(hot).doesNotContain("X");        // 캡 초과 → 제외
    }

    @Test
    void 스캔_비활성이면_기록안됨_인버스만() {
        HotWatchService s = svc(50, 50, 1.3);
        s.record("A", 5.0, true);   // beginScan 없이 → 무시
        s.publish();
        assertThat(s.hotCodes()).containsExactlyInAnyOrder("114800", "251340");
    }
}
