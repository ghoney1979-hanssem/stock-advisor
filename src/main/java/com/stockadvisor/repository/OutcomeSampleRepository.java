package com.stockadvisor.repository;

import com.stockadvisor.domain.OutcomeSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutcomeSampleRepository extends JpaRepository<OutcomeSample, Long> {

    boolean existsByOutcomeIdAndMarkMinutes(Long outcomeId, int markMinutes);

    /** 특정 전략·청산마크(분)의 표본 — 성과게이트가 실제 청산시점 가격으로 net을 측정할 때 사용. */
    List<OutcomeSample> findByStrategyAndMarkMinutes(String strategy, int markMinutes);

    /** 청산마크 근방 [low,high]분 표본 — 정확 마크 없을 때 근접 마크로 대체(게이트 표본 기근 보정). */
    List<OutcomeSample> findByStrategyAndMarkMinutesBetween(String strategy, int low, int high);
}
