package com.stockadvisor.domain;

/** 시장 추세 국면 — 지수(프록시) MA·기울기로 판정. */
public enum MarketTrend {
    BULL("강세"),
    NEUTRAL("중립"),
    BEAR("약세");

    private final String korean;

    MarketTrend(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
