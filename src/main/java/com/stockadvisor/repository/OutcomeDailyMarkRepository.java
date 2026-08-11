package com.stockadvisor.repository;

import com.stockadvisor.domain.OutcomeDailyMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutcomeDailyMarkRepository extends JpaRepository<OutcomeDailyMark, Long> {

    /** 해당 outcome 의 D+N 종가가 이미 수집됐는지(중복 저장 방지). */
    boolean existsByOutcomeIdAndMarkDays(Long outcomeId, int markDays);

    /** 해당 outcome 이 오늘(businessDate) 이미 수집됐는지 — 하루 1회 일봉 조회 게이트. */
    boolean existsByOutcomeIdAndBusinessDate(Long outcomeId, String businessDate);

    /** 전략별 일봉 마크 전체(멀티데이 청산 시뮬 입력 — Phase 2). */
    List<OutcomeDailyMark> findByStrategyOrderByOutcomeIdAscMarkDaysAsc(String strategy);

    long countByStrategy(String strategy);
}
