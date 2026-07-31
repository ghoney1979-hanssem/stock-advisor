package com.stockadvisor.repository;

import com.stockadvisor.domain.Disclosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    boolean existsByReceiptNo(String receiptNo);

    /** 아직 알림 미발송이고 관찰 유효기간(detectedAt 이후) 내인 공시들 */
    List<Disclosure> findByNotifiedFalseAndDetectedAtAfter(Instant threshold);

    /** 진입 완료 처리(재평가 중단) */
    @Transactional
    @Modifying
    @Query("update Disclosure d set d.notified = true where d.id = :id")
    void markNotified(@Param("id") Long id);
}
