package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 국내주식 주식잔고조회(inquire-balance) 응답 매핑.
 * rt_cd="0" 이 정상. output1=보유종목, output2=계좌 요약(예수금 등).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisBalanceResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output1") List<Holding> holdings,
        @JsonProperty("output2") List<Summary> summary
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    /** 보유 종목 1건. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holding(
            @JsonProperty("pdno") String stockCode,            // 종목코드
            @JsonProperty("prdt_name") String name,            // 종목명
            @JsonProperty("hldg_qty") String holdingQty,       // 보유수량
            @JsonProperty("pchs_avg_pric") String avgBuyPrice, // 매입평균가격
            @JsonProperty("prpr") String currentPrice,         // 현재가
            @JsonProperty("evlu_amt") String evalAmount,       // 평가금액
            @JsonProperty("evlu_pfls_amt") String evalPnl,     // 평가손익금액
            @JsonProperty("evlu_pfls_rt") String evalPnlRate   // 평가손익률
    ) {}

    /** 계좌 요약(보통 1건). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
            @JsonProperty("dnca_tot_amt") String depositTotal,        // 예수금총금액
            @JsonProperty("nxdy_excc_amt") String nextDayDeposit,     // 익일정산금액(D+1)
            @JsonProperty("prvs_rcdl_excc_amt") String d2Deposit,     // 가수도정산금액(D+2 예수금, 주문가능에 근접)
            @JsonProperty("tot_evlu_amt") String totalEval,           // 총평가금액
            @JsonProperty("nass_amt") String netAsset,                // 순자산금액
            @JsonProperty("pchs_amt_smtl_amt") String totalBuyAmount, // 매입금액합계
            @JsonProperty("evlu_amt_smtl_amt") String totalEvalAmount,// 평가금액합계
            @JsonProperty("evlu_pfls_smtl_amt") String totalPnl       // 평가손익합계금액
    ) {}
}
