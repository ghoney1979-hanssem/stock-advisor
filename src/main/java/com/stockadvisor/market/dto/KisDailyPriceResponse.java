package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 국내주식 기간별 시세(일별, FHKST01010400) 응답.
 * output 은 최신일이 먼저 오는 최대 30 영업일 배열이다. rt_cd="0" 정상.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisDailyPriceResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<DailyPrice> output
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyPrice(
            @JsonProperty("stck_bsop_date") String businessDate,  // 영업일 YYYYMMDD
            @JsonProperty("stck_clpr") String closePrice,         // 종가
            @JsonProperty("stck_hgpr") String highPrice,          // 고가 (ATR 계산용, 없으면 null→종가대비로 degrade)
            @JsonProperty("stck_lwpr") String lowPrice,           // 저가
            @JsonProperty("acml_vol") String accumulatedVolume,   // 누적 거래량
            @JsonProperty("prdy_ctrt") String dayChangeRate,      // 전일 대비 등락률
            @JsonProperty("stck_oprc") String openPrice           // 시가 (개장갭 계산용)
    ) {
    }
}
