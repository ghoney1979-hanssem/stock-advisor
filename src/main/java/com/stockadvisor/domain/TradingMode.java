package com.stockadvisor.domain;

/** 매매 모드. 기본 DRY_RUN — 실주문 발송 없이 정책/흐름만 검증. */
public enum TradingMode {
    DRY_RUN,   // 주문 로깅만(돈 안 나감)
    LIVE       // 실계좌 실주문
}
