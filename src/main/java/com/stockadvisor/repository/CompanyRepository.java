package com.stockadvisor.repository;

import com.stockadvisor.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface CompanyRepository extends JpaRepository<Company, String> {

    /** 워치리스트 종목코드 전체 (공시 필터링용) */
    @Query("select c.stockCode from Company c")
    List<String> findAllStockCodes();

    /** 동기화 reconcile: 선정 목록(codes)에 없는 종목을 일괄 삭제. 삭제 건수 반환. */
    @Transactional
    @Modifying
    @Query("delete from Company c where c.stockCode not in :codes")
    int deleteByStockCodeNotIn(@Param("codes") Collection<String> codes);
}
