package com.stockadvisor.strategy;

/**
 * 전략 평가 트리거 범위.
 */
public enum StrategyScope {
    /** 공시 발생 종목에 대해서만 평가 (공시가 촉매인 전략). */
    DISCLOSURE,
    /** 공시와 무관하게 워치리스트 전 종목을 주기적으로 스캔. */
    MARKET_SCAN
}
