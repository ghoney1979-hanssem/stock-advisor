package com.stockadvisor.domain;

/**
 * 주문 상태 머신.
 * NEW → SUBMITTED → (PARTIALLY_FILLED) → FILLED   (정상 체결)
 * NEW/SUBMITTED → CANCELLED | REJECTED | FAILED   (미체결/거부/오류)
 * DRY_RUN = 실제 발송 없이 정책 통과만 기록(검증 단계).
 */
public enum OrderStatus {
    NEW,               // 생성(아직 미발송)
    PENDING_APPROVAL,  // 수동 승인 대기(LIVE 게이트)
    DRY_RUN,           // dry-run 기록(실주문 미발송)
    SUBMITTED,         // 거래소 접수
    PARTIALLY_FILLED,  // 부분 체결
    FILLED,            // 전량 체결
    CANCELLED,         // 취소
    REJECTED,          // 거부
    FAILED;            // 전송 오류

    /** 아직 포지션이 살아있을 수 있는(보유/진행 중) 상태인지. */
    public boolean isActive() {
        return this == SUBMITTED || this == PARTIALLY_FILLED || this == FILLED;
    }
}
