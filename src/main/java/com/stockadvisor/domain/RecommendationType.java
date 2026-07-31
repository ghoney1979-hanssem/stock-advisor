package com.stockadvisor.domain;

/**
 * 투자 의견 구분.
 */
public enum RecommendationType {
    BUY("매수"),
    HOLD("중립"),
    SELL("매도");

    private final String korean;

    RecommendationType(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
