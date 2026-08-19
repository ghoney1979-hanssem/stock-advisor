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

    /**
     * 마감 직전 버킷은 +90분이 장 마감(15:20)을 넘어 <b>당일 스캔으로 영원히 못 채운다</b> —
     * 실측 2026-08-18~19 두 날 모두 14:30 버킷의 filled90m이 0이었다(나머지 세 버킷은 1334/1334).
     * 이걸 "미완"으로 세면 pending 집합이 영영 안 비어 매 스캔 헛조회를 하므로 타깃 대상에서 제외한다.
     */
    @Test
    void 마감_직전_버킷은_90분_타깃_대상에서_제외된다() {
        List<String> reachable = UniverseSnapshotService.reachable90mBuckets(BUCKETS, 90, LocalTime.of(15, 20));
        assertThat(reachable).containsExactly("09:30", "11:00", "13:00");   // 14:30+90분=16:00 > 15:20 → 제외
    }

    @Test
    void 경계는_포함이고_전부_불가하면_매칭안되는_sentinel() {
        // 13:50 + 90분 = 15:20 = 세션종료 → 도달 가능(경계 포함)
        assertThat(UniverseSnapshotService.reachable90mBuckets(
                UniverseSnapshotService.parseTimes("13:50"), 90, LocalTime.of(15, 20))).containsExactly("13:50");
        // 전부 불가여도 빈 리스트를 반환하면 JPA in () 이 되므로 매칭 안 되는 sentinel을 준다
        assertThat(UniverseSnapshotService.reachable90mBuckets(
                UniverseSnapshotService.parseTimes("14:30"), 90, LocalTime.of(15, 20)))
                .containsExactly("-").doesNotContain("14:30");
    }

    /**
     * 종가는 "15:15 이후 관측가" 근사가 아니라 익일 일봉의 확정 종가로 채운다. 단 일봉의 직전 영업일이
     * DB의 직전 스냅샷일과 다르면(앱 다운·휴장·일봉 지연) 엉뚱한 날 종가가 들어가므로 채우지 않는다.
     */
    @Test
    void 종가_채움은_일봉_직전영업일과_DB_직전스냅샷일이_일치할_때만() {
        assertThat(UniverseSnapshotService.closeFillUsable(2405, "20260819", "20260819")).isTrue();
        // 앱이 하루 쉬어 DB의 직전 스냅샷일이 8/18인데 일봉은 8/19을 직전 영업일로 본다 → skip(fail-closed)
        assertThat(UniverseSnapshotService.closeFillUsable(2405, "20260819", "20260818")).isFalse();
        assertThat(UniverseSnapshotService.closeFillUsable(0, "20260819", "20260819")).isFalse();     // 전일종가 미상
        assertThat(UniverseSnapshotService.closeFillUsable(2405, null, "20260819")).isFalse();        // 일봉 1행뿐
        assertThat(UniverseSnapshotService.closeFillUsable(2405, "20260819", null)).isFalse();        // 이전 스냅샷 없음
    }
}
