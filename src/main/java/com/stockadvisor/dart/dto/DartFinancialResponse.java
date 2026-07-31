package com.stockadvisor.dart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

/**
 * DART 단일회사 주요계정(fnlttSinglAcnt) 응답 매핑.
 * status="000" 이 정상이며, 그 외 코드는 오류 또는 데이터 없음을 의미한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartFinancialResponse(
        String status,
        String message,
        List<FinancialItem> list
) implements Serializable {

    public boolean isSuccess() {
        return "000".equals(status);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinancialItem(
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("bsns_year") String businessYear,
            @JsonProperty("account_nm") String accountName,
            @JsonProperty("fs_div") String financialStatementDiv,
            @JsonProperty("thstrm_nm") String currentTermName,
            @JsonProperty("thstrm_amount") String currentTermAmount,
            @JsonProperty("frmtrm_amount") String previousTermAmount
    ) implements Serializable {
    }
}