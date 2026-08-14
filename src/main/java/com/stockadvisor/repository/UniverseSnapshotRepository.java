package com.stockadvisor.repository;

import com.stockadvisor.domain.UniverseSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UniverseSnapshotRepository extends JpaRepository<UniverseSnapshot, Long> {

    /** 해당 일자·버킷에 이미 기록됐는지(중복 수집 방지 — 스캔이 12분마다 도므로 같은 버킷 창에 여러 번 들어올 수 있음). */
    boolean existsBySnapDateAndSnapTime(String snapDate, String snapTime);

    /** 타깃 미완 행 — 이후 스캔이 만나는 종목마다 채운다. 오늘·어제분만 대상(그 이상은 만료). */
    @Query("""
            select s from UniverseSnapshot s
            where s.stockCode = :code and s.snapDate >= :fromDate
              and (s.price90m is null or s.priceClose is null or s.priceNextClose is null)
            """)
    List<UniverseSnapshot> findPendingTargets(String code, String fromDate);

    /** 사후 타깃이 남은 종목코드 — 스캔 시작 시 1회 조회해 전 종목 개별 쿼리를 막는 사전필터. */
    @Query("""
            select distinct s.stockCode from UniverseSnapshot s
            where s.snapDate >= :fromDate
              and (s.price90m is null or s.priceClose is null or s.priceNextClose is null)
            """)
    List<String> findPendingCodes(String fromDate);

    /** 수집 현황(가시화) — 일자·버킷별 행 수. */
    @Query("""
            select s.snapDate, s.snapTime, count(s), sum(case when s.price90m is not null then 1 else 0 end),
                   sum(case when s.priceClose is not null then 1 else 0 end),
                   sum(case when s.priceNextClose is not null then 1 else 0 end)
            from UniverseSnapshot s group by s.snapDate, s.snapTime order by s.snapDate desc, s.snapTime
            """)
    List<Object[]> summarize();
}
