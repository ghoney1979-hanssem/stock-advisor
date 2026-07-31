package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 당일 분봉 조회(FHKST03010200) 응답.
 * output2 는 최신 분이 먼저 오는 최대 30개 1분봉 배열이다. rt_cd="0" 정상.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisMinuteCandleResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output2") List<Candle> output2
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candle(
            @JsonProperty("stck_cntg_hour") String time,   // 체결시간 HHMMSS
            @JsonProperty("stck_prpr") String close,        // 해당 분 종가
            @JsonProperty("cntg_vol") String volume         // 해당 분 거래량
    ) {
    }
}
