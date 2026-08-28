package com.stockadvisor.repository;

import com.stockadvisor.domain.FinancialFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FinancialFactRepository extends JpaRepository<FinancialFact, Long> {

    /** 재수집 생략 판정 — DART 일일 한도(~2만)가 있어 "이미 가진 (종목,연도)"를 먼저 아는 게 중요하다. */
    @Query("select f.stockCode || ':' || f.businessYear from FinancialFact f")
    List<String> findAllKeys();

    List<FinancialFact> findByBusinessYear(String businessYear);

    @Query("select count(distinct f.stockCode) from FinancialFact f")
    long countDistinctStocks();

    @Query("select f.businessYear, count(f) from FinancialFact f group by f.businessYear order by f.businessYear")
    List<Object[]> countByYear();
}
