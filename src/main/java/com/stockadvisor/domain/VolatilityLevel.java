package com.stockadvisor.domain;

/** 시장 변동성 국면 — 지수(프록시) 일간수익률 실현변동성으로 판정. */
public enum VolatilityLevel {
    LOW("저변동"),
    MID("중변동"),
    HIGH("고변동");

    private final String korean;

    VolatilityLevel(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
