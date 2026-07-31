package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * 한국투자증권 국내주식 현재가 시세(FHKST01010100) 응답 매핑.
 * rt_cd="0" 이 정상 응답이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisQuoteResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Output output
) implements Serializable {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("stck_prpr") String currentPrice,        // 주식 현재가
            @JsonProperty("prdy_vrss") String dayChange,           // 전일 대비
            @JsonProperty("prdy_ctrt") String dayChangeRate,       // 전일 대비율
            @JsonProperty("hts_avls") String marketCap,            // 시가총액(억원)
            @JsonProperty("per") String per,                       // 주가수익비율
            @JsonProperty("pbr") String pbr,                       // 주가순자산비율
            @JsonProperty("rprs_mrkt_kor_name") String marketName, // 대표시장 한글명 (KOSPI200/KSQ150 등)
            @JsonProperty("bstp_kor_isnm") String sectorName,      // 업종 한글명 (예: 전기·전자)
            @JsonProperty("wghn_avrg_stck_prc") String vwap,       // 가중평균주가(VWAP)
            @JsonProperty("acml_vol") String accumulatedVolume,    // 누적 거래량
            @JsonProperty("mrkt_warn_cls_code") String marketWarnCode,  // 시장경고 00정상/01주의/02경고/03위험
            @JsonProperty("mang_issu_cls_code") String managedIssueCode, // 관리종목 여부 Y/N
            @JsonProperty("sltr_yn") String liquidationYn          // 정리매매 여부 Y/N
    ) implements Serializable {

        /** 관리종목·정리매매·투자경고(주의 이상) 등 건전하지 않은 종목이면 false. */
        public boolean isHealthy() {
            if ("Y".equalsIgnoreCase(managedIssueCode)) return false;
            if ("Y".equalsIgnoreCase(liquidationYn)) return false;
            return marketWarnCode == null || "00".equals(marketWarnCode);
        }
    }
}
