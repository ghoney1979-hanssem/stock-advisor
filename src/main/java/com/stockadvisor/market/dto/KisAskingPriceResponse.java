package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한국투자증권 국내주식 호가/예상체결(FHKST01010200) 응답 — 매도 10호가(가격+잔량)와 최우선 매수호가.
 * 실측 스프레드(1호가) + 시장충격(잔량 walk)에 사용. rt_cd="0" 정상. output2(예상체결)는 미사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisAskingPriceResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output1") Output1 output
) {
    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    /** 매도호가1~10(askpN)·매도호가잔량1~10(askp_rsqnN), 최우선 매수호가(bidp1). 매수 주문은 매도호가를 잠식. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output1(
            @JsonProperty("askp1") String askp1, @JsonProperty("askp2") String askp2,
            @JsonProperty("askp3") String askp3, @JsonProperty("askp4") String askp4,
            @JsonProperty("askp5") String askp5, @JsonProperty("askp6") String askp6,
            @JsonProperty("askp7") String askp7, @JsonProperty("askp8") String askp8,
            @JsonProperty("askp9") String askp9, @JsonProperty("askp10") String askp10,
            @JsonProperty("askp_rsqn1") String askq1, @JsonProperty("askp_rsqn2") String askq2,
            @JsonProperty("askp_rsqn3") String askq3, @JsonProperty("askp_rsqn4") String askq4,
            @JsonProperty("askp_rsqn5") String askq5, @JsonProperty("askp_rsqn6") String askq6,
            @JsonProperty("askp_rsqn7") String askq7, @JsonProperty("askp_rsqn8") String askq8,
            @JsonProperty("askp_rsqn9") String askq9, @JsonProperty("askp_rsqn10") String askq10,
            @JsonProperty("bidp1") String bidp1
    ) {
        public String[] askPrices() { return new String[]{askp1, askp2, askp3, askp4, askp5, askp6, askp7, askp8, askp9, askp10}; }
        public String[] askQtys() { return new String[]{askq1, askq2, askq3, askq4, askq5, askq6, askq7, askq8, askq9, askq10}; }
    }
}
