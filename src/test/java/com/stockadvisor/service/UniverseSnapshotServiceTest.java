package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유니버스 스냅샷 — 버킷 시각 선택(순수 코어) 검증.
 *
 * <p>전수 스캔은 12분 주기라 버킷 시각에 정확히 걸리지 않는다 → "버킷 시각 이후 window분 내" 스캔이 수집한다.
 * 창이 겹치면 가장 가까운(늦은) 버킷을 고른다.</p>
 */
class UniverseSnapshotServiceTest {

    private static final List<LocalTime> BUCKETS = UniverseSnapshotService.parseTimes("09:30,11:00,13:00,14:30");

    @Test
    void 버킷_시각_파싱은_정렬되고_공백을_허용한다() {
        assertThat(UniverseSnapshotService.parseTimes(" 13:00 , 09:30 ,11:00 "))
                .containsExactly(LocalTime.of(9, 30), LocalTime.of(11, 0), LocalTime.of(13, 0));
        assertThat(UniverseSnapshotService.parseTimes("")).isEmpty();
        assertThat(UniverseSnapshotService.parseTimes(null)).isEmpty();
    }

    @Test
    void 버킷_창_안이면_해당_버킷_밖이면_null() {
        // 09:30 버킷, window 20분 → [09:30, 09:50)
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 30), BUCKETS, 20)).isEqualTo("09:30");  // 경계 포함
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 41), BUCKETS, 20)).isEqualTo("09:30");  // 12분 주기 스캔이 늦게 들어와도 수집
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 50), BUCKETS, 20)).isNull();            // 경계 배타
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 29), BUCKETS, 20)).isNull();            // 버킷 전
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(10, 30), BUCKETS, 20)).isNull();           // 버킷 사이
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(14, 35), BUCKETS, 20)).isEqualTo("14:30"); // 마지막 버킷
    }

    @Test
    void 창이_겹치면_가장_가까운_버킷() {
        // window를 크게 잡아 09:30·11:00 창이 겹치는 상황 — 11:05는 더 가까운 11:00으로
        List<LocalTime> b = UniverseSnapshotService.parseTimes("09:30,11:00");
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(11, 5), b, 120)).isEqualTo("11:00");
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(10, 55), b, 120)).isEqualTo("09:30");
    }

    @Test
    void 비활성_설정이면_수집_버킷_없음() {
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 35), List.of(), 20)).isNull();   // 버킷 미설정
        assertThat(UniverseSnapshotService.bucketFor(LocalTime.of(9, 35), BUCKETS, 0)).isNull();      // window 0
        assertThat(UniverseSnapshotService.bucketFor(null, BUCKETS, 20)).isNull();
    }
}
