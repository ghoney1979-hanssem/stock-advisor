package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 주식현재가 체결(FHKST01010300) 응답 — 최근 체결 틱 목록.
 * 진입 시점 <b>당일 체결강도</b>({@code tday_rltv} = 매수체결/매도체결×100, >100이면 매수 우위) 태깅용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisCcnlResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<Ccnl> output
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ccnl(
            @JsonProperty("stck_cntg_hour") String time,     // 체결 시간 HHMMSS
            @JsonProperty("cntg_vol") String volume,          // 체결 거래량
            @JsonProperty("tday_rltv") String strength        // 당일 체결강도(%)
    ) {}

    /** 최신 체결 행의 당일 체결강도(%) — 없거나 파싱 불가면 null(태깅 생략). */
    public Double latestStrength() {
        if (output == null) return null;
        for (Ccnl c : output) {   // output 은 최신순
            if (c == null || c.strength() == null || c.strength().isBlank()) continue;
            try {
                return Double.parseDouble(c.strength().trim());
            } catch (NumberFormatException ignored) {
                // 다음 행 시도
            }
        }
        return null;
    }
}
