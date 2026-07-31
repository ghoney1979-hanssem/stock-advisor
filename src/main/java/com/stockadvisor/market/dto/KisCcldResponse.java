package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 주식일별주문체결조회(inquire-daily-ccld) 응답. rt_cd="0" 정상.
 * output1 = 당일 주문별 체결 현황(주문번호별 체결수량/잔여/체결금액).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisCcldResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output1") List<Ccld> orders
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ccld(
            @JsonProperty("odno") String orderNo,          // 주문번호
            @JsonProperty("pdno") String stockCode,        // 종목코드
            @JsonProperty("ord_qty") String orderQty,      // 주문수량
            @JsonProperty("tot_ccld_qty") String filledQty,// 총체결수량
            @JsonProperty("rmn_qty") String remainingQty,  // 잔여수량
            @JsonProperty("tot_ccld_amt") String filledAmt,// 총체결금액
            @JsonProperty("sll_buy_dvsn_cd") String sellBuyCode  // 01매도/02매수
    ) {}
}
