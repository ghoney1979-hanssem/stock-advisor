package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상장기업 마스터.
 * 종목코드(KRX)와 DART 고유 기업코드를 연결한다.
 */
@Entity
@Table(name = "company")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {

    /** 종목코드 6자리 (KRX 단축코드, 예: 005930) */
    @Id
    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    /** 회사명 */
    @Column(name = "name", nullable = false)
    private String name;

    /** DART 고유 기업코드 8자리 */
    @Column(name = "corp_code", length = 8)
    private String corpCode;

    /** 시장 구분 (KOSPI / KOSDAQ) */
    @Column(name = "market", length = 10)
    private String market;

    public Company(String stockCode, String name, String corpCode, String market) {
        this.stockCode = stockCode;
        this.name = name;
        this.corpCode = corpCode;
        this.market = market;
    }
}
