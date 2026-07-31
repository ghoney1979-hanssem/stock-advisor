package com.stockadvisor.repository;

import com.stockadvisor.domain.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 특정 종목의 가장 최근 추천 1건 */
    Optional<Recommendation> findTopByStockCodeOrderByCreatedAtDesc(String stockCode);
}
