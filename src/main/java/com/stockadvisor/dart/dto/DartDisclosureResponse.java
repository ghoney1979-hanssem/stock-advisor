package com.stockadvisor.dart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DART 공시검색(list.json) 응답 매핑. 최신 공시가 먼저 온다.
 * status="000" 정상, "013" 조회 데이터 없음.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartDisclosureResponse(
        String status,
        String message,
        List<Disclosure> list
) {

    public boolean isSuccess() {
        return "000".equals(status);
    }

    /** 데이터 없음(정상적인 빈 결과). */
    public boolean isNoData() {
        return "013".equals(status);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Disclosure(
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("report_nm") String reportName,
            @JsonProperty("rcept_no") String receiptNo,    // 접수번호(고유, 정렬키)
            @JsonProperty("rcept_dt") String receiptDate    // 접수일자 YYYYMMDD
    ) {
    }
}
