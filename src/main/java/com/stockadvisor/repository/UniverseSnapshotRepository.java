package com.stockadvisor.repository;

import com.stockadvisor.domain.UniverseSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UniverseSnapshotRepository extends JpaRepository<UniverseSnapshot, Long> {

    /** 해당 일자·버킷에 이미 기록됐는지(중복 수집 방지 — 스캔이 12분마다 도므로 같은 버킷 창에 여러 번 들어올 수 있음). */
    boolean existsBySnapDateAndSnapTime(String snapDate, String snapTime);

    /**
     * 타깃 미완 행 — 이후 스캔이 만나는 종목마다 채운다. 최근 며칠분만 대상(그 이상은 만료).
     *
     * <p>⚠️ {@code m90Buckets}는 <b>+90분 타깃이 장중에 도달 가능한 버킷</b> 라벨이다. 마감 직전 버킷(예 14:30)은
     * 14:30+90분=16:00이라 당일 스캔으로 영원히 못 채우므로, 그 버킷의 {@code price90m} null은
     * "미완"이 아니라 "해당 없음"이다 — 미완으로 세면 pending 집합이 영구히 안 비고 매 스캔 헛조회를 한다.</p>
     */
    @Query("""
            select s from UniverseSnapshot s
            where s.stockCode = :code and s.snapDate >= :fromDate
              and ((s.price90m is null and s.snapTime in (:m90Buckets))
                   or s.priceClose is null or s.priceNextClose is null)
            """)
    List<UniverseSnapshot> findPendingTargets(String code, String fromDate, List<String> m90Buckets);

    /** 사후 타깃이 남은 종목코드 — 스캔 시작 시 1회 조회해 전 종목 개별 쿼리를 막는 사전필터. */
    @Query("""
            select distinct s.stockCode from UniverseSnapshot s
            where s.snapDate >= :fromDate
              and ((s.price90m is null and s.snapTime in (:m90Buckets))
                   or s.priceClose is null or s.priceNextClose is null)
            """)
    List<String> findPendingCodes(String fromDate, List<String> m90Buckets);

    /**
     * 오늘 이전 스냅샷 일자(최신순) — 종가/익일종가 타깃을 <b>직전 거래일 확정 종가</b>로 채울 때
     * "우리 DB의 직전 스냅샷일"과 "일봉이 말하는 직전 영업일"이 같은지 대조하는 데 쓴다(다르면 채우지 않음=fail-closed).
     */
    @Query("""
            select distinct s.snapDate from UniverseSnapshot s
            where s.snapDate < :today order by s.snapDate desc
            """)
    List<String> findSnapDatesBefore(String today);

    /** 수집 현황(가시화) — 일자·버킷별 행 수. */
    @Query("""
            select s.snapDate, s.snapTime, count(s), sum(case when s.price90m is not null then 1 else 0 end),
                   sum(case when s.priceClose is not null then 1 else 0 end),
                   sum(case when s.priceNextClose is not null then 1 else 0 end)
            from UniverseSnapshot s group by s.snapDate, s.snapTime order by s.snapDate desc, s.snapTime
            """)
    List<Object[]> summarize();
}
