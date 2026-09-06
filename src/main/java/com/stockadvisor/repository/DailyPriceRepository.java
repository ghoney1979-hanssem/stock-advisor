package com.stockadvisor.repository;

import com.stockadvisor.domain.DailyPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * <b>유니버스 동일가중 보유 수익률</b> — 진입일(base_date)마다 k거래일 뒤까지의 평균 등락률(%).
     *
     * <p><b>왜 필요한가</b>(2026-09-07): {@code multiday-exit-comparison} 이 "보유 D+15가 최고"라고
     * 권장하는데, 그 완주 코호트의 진입일이 반등 구간(7/20~8/13)에 몰려 있어 <b>수치의 정체가 시장 드리프트</b>였다.
     * 실측으로 같은 진입일에 유동성 필터만 걸고 전 종목을 그냥 들고 있었으면 D+15 평균이 +8% 수준이라,
     * 10전략 중 9개가 유니버스에 졌다(B는 −7.79%p). 반사실 없이 절대 net만 보면 지수에 지는 규칙을 채택하게 된다.
     *
     * <p>k는 <b>거래일 오프셋</b>(달력일 아님) — 종목별 행 순번 차이라 {@code outcome_daily_mark} 의 D+N과
     * 같은 의미다. 유동성 필터는 <b>진입일 시점</b>에만 적용한다(라이브 진입 판정과 같은 자리).</p>
     *
     * @return (base_date, k, ret_pct, n) 행. 조회 실패·데이터 없음이면 빈 목록 → 벤치마크 미가용으로 degrade.
     */
    @Query(value = """
            with r as (
              select stock_code, business_date, close_price, volume,
                     row_number() over (partition by stock_code order by business_date) rn
              from daily_price where business_date >= :entryFrom
            )
            select b.business_date, cast(f.rn - b.rn as int), avg((f.close_price - b.close_price) * 100.0 / b.close_price), count(*)
            from r b join r f on f.stock_code = b.stock_code and f.rn > b.rn and f.rn <= b.rn + :maxK
            where b.business_date between :entryFrom and :entryTo
              and b.close_price >= :minPrice
              and b.close_price * b.volume >= :minTurnoverKrw
            group by 1, 2
            """, nativeQuery = true)
    List<Object[]> universeForwardReturns(@Param("entryFrom") String entryFrom,
                                          @Param("entryTo") String entryTo,
                                          @Param("maxK") int maxK,
                                          @Param("minPrice") long minPrice,
                                          @Param("minTurnoverKrw") long minTurnoverKrw);
}
