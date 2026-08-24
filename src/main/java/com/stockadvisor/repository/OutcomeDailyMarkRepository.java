package com.stockadvisor.repository;

import com.stockadvisor.domain.OutcomeDailyMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutcomeDailyMarkRepository extends JpaRepository<OutcomeDailyMark, Long> {

    /** 해당 outcome 의 D+N 종가가 이미 수집됐는지(중복 저장 방지). */
    boolean existsByOutcomeIdAndMarkDays(Long outcomeId, int markDays);

    /** 해당 outcome 이 오늘(businessDate) 이미 수집됐는지 — 하루 1회 일봉 조회 게이트. */
    boolean existsByOutcomeIdAndBusinessDate(Long outcomeId, String businessDate);

    /** 전략별 일봉 마크 전체(멀티데이 청산 시뮬 입력 — Phase 2). */
    List<OutcomeDailyMark> findByStrategyOrderByOutcomeIdAscMarkDaysAsc(String strategy);

    long countByStrategy(String strategy);

    /**
     * 마크가 있는 outcome 의 <b>진입일</b>(outcomeId, alertDate) 쌍 — 멀티데이 시뮬의 단일일 클러스터 판정용.
     *
     * <p>{@link OutcomeDailyMark} 는 진입일을 갖지 않는다({@code businessDate} 는 <b>마크가 찍힌 날</b>이고,
     * D0 마크는 구 백필분에 없어 진입일 대용으로 못 쓴다 — 실측 mark_days=0 이 1,222행뿐인데 D+1 은 1,348행).
     * 그래서 {@code TradeOutcome} 과 조인해 진입일을 따로 받는다.</p>
     */
    @Query("select m.outcomeId, o.alertDate from OutcomeDailyMark m, com.stockadvisor.domain.TradeOutcome o "
            + "where o.id = m.outcomeId and m.strategy = :strategy group by m.outcomeId, o.alertDate")
    List<Object[]> findEntryDatesByStrategy(@Param("strategy") String strategy);
}
