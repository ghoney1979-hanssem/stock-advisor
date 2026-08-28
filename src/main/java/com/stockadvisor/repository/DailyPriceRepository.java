package com.stockadvisor.repository;

import com.stockadvisor.domain.DailyPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 일봉 히스토리 조회. 쓰기(대량 적재)는 JPA가 아니라 {@code DailyHistoryBackfillService}의
 * JdbcTemplate 배치 upsert를 쓴다 — 종목당 ~2,450행 × 1,500종목 ≈ 370만 행이라 영속성 컨텍스트를 태우면 느리다.
 */
public interface DailyPriceRepository extends JpaRepository<DailyPrice, Long> {

    /** 백테스트 주 진입점 — 한 종목의 기간 일봉을 날짜 오름차순으로. */
    List<DailyPrice> findByStockCodeAndBusinessDateBetweenOrderByBusinessDateAsc(
            String stockCode, String from, String to);

    /** 적재 현황(종목별 커버리지) — 재실행 시 "이미 덮인 종목"을 건너뛰는 판단에 쓴다. */
    @Query("select d.stockCode, min(d.businessDate), max(d.businessDate), count(d) "
            + "from DailyPrice d group by d.stockCode")
    List<Object[]> summarizeCoverage();

    @Query("select count(distinct d.stockCode) from DailyPrice d")
    long countDistinctStocks();

    @Query("select min(d.businessDate) from DailyPrice d")
    String minBusinessDate();

    @Query("select max(d.businessDate) from DailyPrice d")
    String maxBusinessDate();
}
