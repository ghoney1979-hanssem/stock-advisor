package com.stockadvisor.repository;

import com.stockadvisor.domain.SleeveSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SleeveSelectionRepository extends JpaRepository<SleeveSelection, Long> {

    List<SleeveSelection> findByStrategyOrderByRebalanceDateAscRankNoAsc(String strategy);

    List<SleeveSelection> findByStrategyAndRebalanceDate(String strategy, String rebalanceDate);

    /** 가장 최근 리밸런싱 일자 — 다음 리밸런싱 시점 판단용. */
    @Query("select max(s.rebalanceDate) from SleeveSelection s where s.strategy = :strategy")
    String findLatestRebalanceDate(String strategy);

    @Query("select distinct s.rebalanceDate from SleeveSelection s where s.strategy = :strategy order by s.rebalanceDate")
    List<String> findRebalanceDates(String strategy);
}
