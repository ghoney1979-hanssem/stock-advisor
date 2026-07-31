package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한국투자증권 국내업종 현재지수(FHPUP02100000) 응답. 코스피/코스닥 지수·등락률.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisIndexResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Output output
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("bstp_nmix_prpr") String indexPrice,        // 업종 지수 현재가
            @JsonProperty("bstp_nmix_prdy_ctrt") String changeRate    // 전일 대비 등락률(%)
    ) {
    }
}
