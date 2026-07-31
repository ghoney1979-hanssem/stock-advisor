package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 감지한 공시 1건. 워치리스트(company) 종목의 공시만 적재한다.
 * 신호 평가 후 알림 발송 여부를 함께 관리해 중복 알림을 방지한다.
 */
@Entity
@Table(name = "disclosure", indexes = {
        @Index(name = "idx_disclosure_receipt_no", columnList = "receipt_no", unique = true),
        @Index(name = "idx_disclosure_notified", columnList = "notified")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Disclosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DART 접수번호 (고유) */
    @Column(name = "receipt_no", length = 20, nullable = false, unique = true)
    private String receiptNo;

    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Column(name = "corp_code", length = 8)
    private String corpCode;

    @Column(name = "report_name", length = 500)
    private String reportName;

    /** 접수일자 YYYYMMDD */
    @Column(name = "receipt_date", length = 8)
    private String receiptDate;

    /** 공시 감지 시각 */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** 알림 발송 완료 여부 */
    @Column(name = "notified", nullable = false)
    private boolean notified;

    @Builder
    public Disclosure(String receiptNo, String stockCode, String corpCode,
                      String reportName, String receiptDate) {
        this.receiptNo = receiptNo;
        this.stockCode = stockCode;
        this.corpCode = corpCode;
        this.reportName = reportName;
        this.receiptDate = receiptDate;
        this.detectedAt = Instant.now();
        this.notified = false;
    }

    /** 알림 발송 완료 처리 */
    public void markNotified() {
        this.notified = true;
    }
}
