package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한국투자증권 국내주식 현금주문(order-cash) 응답 매핑. rt_cd="0" 이 접수 성공.
 * 접수 성공은 "주문 전송됨"을 뜻하며, 실제 체결은 비동기(별도 체결조회 필요).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisOrderResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Output output
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String exchangeOrgNo, // 한국거래소전송주문조직번호
            @JsonProperty("ODNO") String orderNo,                     // 주문번호
            @JsonProperty("ORD_TMD") String orderTime                 // 주문시각(HHMMSS)
    ) {}
}
