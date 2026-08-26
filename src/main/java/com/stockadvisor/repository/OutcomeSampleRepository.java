package com.stockadvisor.repository;

import com.stockadvisor.domain.OutcomeSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutcomeSampleRepository extends JpaRepository<OutcomeSample, Long> {

    boolean existsByOutcomeIdAndMarkMinutes(Long outcomeId, int markMinutes);

    /** 특정 전략·청산마크(분)의 표본 — 성과게이트가 실제 청산시점 가격으로 net을 측정할 때 사용. */
    List<OutcomeSample> findByStrategyAndMarkMinutes(String strategy, int markMinutes);

    /** 청산마크 근방 [low,high]분 표본 — 정확 마크 없을 때 근접 마크로 대체(게이트 표본 기근 보정). */
    List<OutcomeSample> findByStrategyAndMarkMinutesBetween(String strategy, int low, int high);

    /**
     * 마크가 있는 outcome 의 <b>진입일</b>(outcomeId, alertDate) 쌍 — 청산곡선의 단일일 클러스터 판정용.
     *
     * <p>{@link OutcomeSample} 은 진입일을 갖지 않으므로(마크는 outcomeId·경과분만 안다) {@code TradeOutcome} 과
     * 조인해 따로 받는다 — {@code OutcomeDailyMarkRepository.findEntryDatesByStrategy} 와 같은 패턴.
     * outcome 당 1행이라(마크 수만큼 늘지 않는다) 결과 크기는 진입·대조군 행 수 수준.</p>
     */
    @Query("select s.outcomeId, o.alertDate from OutcomeSample s, com.stockadvisor.domain.TradeOutcome o "
            + "where o.id = s.outcomeId group by s.outcomeId, o.alertDate")
    List<Object[]> findEntryDates();
}
