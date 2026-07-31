package com.stockadvisor.domain;

/** 청산(매도) 방식 — 적응형 선택 대상. param 의미: TRAILING=되돌림%, TIME=마크(분), 그 외 미사용. */
public enum ExitMethodType {
    TIME("시간기반"),
    TRAILING("트레일링"),
    VWAP("VWAP이탈"),
    TREND_REVERSAL("추세전환"),
    FLOW_REVERSAL("지수흐름반전");   // 지수 mom30 ≤ param(%) 음전 시 청산 — 흐름 순풍이 꺼지면 이탈(2026-07-15)

    private final String korean;

    ExitMethodType(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
