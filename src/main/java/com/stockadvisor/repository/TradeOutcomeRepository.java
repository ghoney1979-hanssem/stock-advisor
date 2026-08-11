package com.stockadvisor.repository;

import com.stockadvisor.domain.TradeOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TradeOutcomeRepository extends JpaRepository<TradeOutcome, Long> {

    /** 추적 진행 중(미완료) 가상매수 — horizon 수집 대상 */
    List<TradeOutcome> findByCompletedFalse();

    /** (공시, 전략) 중복 기록 방지 */
    boolean existsByDisclosureIdAndStrategy(Long disclosureId, String strategy);

    /** 종목당 하루 1회 진입 제한용 (전략, 종목, 매수일) */
    boolean existsByStrategyAndStockCodeAndAlertDate(String strategy, String stockCode, String alertDate);

    /** (전략, 종목, 매수일) 기존 행 조회 — 인버스 control→entry 승격 판단/갱신용(앱 dedup상 통상 ≤1건). */
    List<TradeOutcome> findByStrategyAndStockCodeAndAlertDate(String strategy, String stockCode, String alertDate);

    /** 특정일 진입분 (일별 리포트용) */
    List<TradeOutcome> findByAlertDate(String alertDate);

    /** cutoff 이후 전체 진입분 (일별 성과 그래프용 — alertDate는 yyyyMMdd 고정폭이라 문자열 비교=날짜순) */
    List<TradeOutcome> findByAlertDateGreaterThanEqual(String alertDate);

    /** 전략별 최근 진입분 (성과 게이트용, alertDate ≥ cutoff) */
    List<TradeOutcome> findByStrategyAndAlertDateGreaterThanEqual(String strategy, String alertDate);

    /** 멀티데이 백필 대상 — 지정 전략들의 비대조군 진입분(alertDate ≥ cutoff, KIS 일봉 창 내). */
    List<TradeOutcome> findByStrategyInAndControlFalseAndAlertDateGreaterThanEqual(
            Collection<String> strategies, String alertDate);

    /** 국면 태그가 비어있는 (시장, 진입일) 조합 — 소급 태깅 대상 (시장 미상은 제외). */
    @Query("select distinct o.entryMarket, o.alertDate from TradeOutcome o "
            + "where o.entryMarketTrend is null and o.entryMarket is not null")
    List<Object[]> findDistinctMarketDateWithNullTrend();

    /** 특정 (시장, 진입일)의 국면 null 표본에 소급 국면 태깅. @return 갱신 행 수. */
    @Modifying
    @Query("update TradeOutcome o set o.entryMarketTrend = :trend "
            + "where o.entryMarket = :market and o.alertDate = :date and o.entryMarketTrend is null")
    int backfillTrend(@Param("market") String market, @Param("date") String date, @Param("trend") String trend);
}
