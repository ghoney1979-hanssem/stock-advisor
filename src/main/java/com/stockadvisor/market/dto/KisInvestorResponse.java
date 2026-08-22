package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 한국투자증권 종목별 투자자매매동향(FHKST01010900) 응답 — <b>일별</b> 개인·외국인·기관 순매수.
 * 1콜에 최근 30거래일치가 온다(실측 20260709~20260821) → <b>소급 태깅 가능</b>.
 * rt_cd="0" 정상.
 *
 * <p>⚠️ 이 API는 <b>확정치</b>다. 같은 날 시장 가집계(FHPTJ04400000)와 값이 다르다
 * (실측 005930 8/21 외국인 순매수: 확정 1,567,349 vs 가집계 1,222,000 = 22% 차이) — 가집계는 잠정치다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisInvestorResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<Daily> output
) {
    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    /** 하루치 투자자별 순매수. 수량(qty)과 매수수량(shnu_vol)만 매핑 — 비중 계산에 필요한 최소집합. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Daily(
            @JsonProperty("stck_bsop_date") String businessDate,
            @JsonProperty("frgn_ntby_qty") String frgnNetBuyQty,     // 외국인 순매수 수량
            @JsonProperty("orgn_ntby_qty") String orgnNetBuyQty,     // 기관계 순매수 수량
            @JsonProperty("prsn_shnu_vol") String prsnBuyVol,        // 개인 매수 수량
            @JsonProperty("frgn_shnu_vol") String frgnBuyVol,        // 외국인 매수 수량
            @JsonProperty("orgn_shnu_vol") String orgnBuyVol         // 기관 매수 수량
    ) {}

    /**
     * 특정 일자의 수급 비중 — 순매수 수량을 <b>그날 거래량으로 나눠 스케일을 없앤다</b>.
     * 삼성전자 150만주와 소형주 1만주를 그대로 비교할 수 없으므로 절대 수량은 feature가 될 수 없다.
     *
     * @param basisDate   실제로 쓰인 기준 거래일(진입일보다 앞선 날)
     * @param frgnRatioPct 외국인 순매수 / 거래량 × 100 (양수=순매수)
     * @param orgnRatioPct 기관 순매수 / 거래량 × 100
     */
    public record Flow(String basisDate, double frgnRatioPct, double orgnRatioPct) {}

    /**
     * <b>{@code date} 직전</b> 거래일의 수급 비중. 없으면 null.
     *
     * <p>⚠️ <b>진입일 당일 행을 쓰면 안 된다(look-ahead)</b> — 그 행은 장 마감까지의 하루 전체 수급이라
     * 우리가 장중에 진입한 시점에는 존재하지 않던 정보다. 그래서 <b>strictly before</b>로 고른다.
     * 이 한 줄이 feature의 유효성을 좌우한다(소급 태깅이라 더더욱 — 미래를 보기가 너무 쉽다).</p>
     *
     * <p>거래량 분모는 <b>3주체 매수수량 합</b>(개인+외국인+기관)으로 근사한다 — 실측상 총거래량의 99.2%
     * (나머지는 기타법인). 응답 안에서 자족적으로 계산돼 추가 조회가 없다.</p>
     */
    public static Flow priorTo(List<Daily> rows, String date) {
        if (rows == null || date == null) return null;
        Daily best = null;
        for (Daily d : rows) {
            if (d == null || d.businessDate() == null) continue;
            if (d.businessDate().compareTo(date) >= 0) continue;              // 당일·미래 제외
            if (best == null || d.businessDate().compareTo(best.businessDate()) > 0) best = d;
        }
        if (best == null) return null;
        long volume = parse(best.prsnBuyVol()) + parse(best.frgnBuyVol()) + parse(best.orgnBuyVol());
        if (volume <= 0) return null;
        return new Flow(best.businessDate(),
                (double) parse(best.frgnNetBuyQty()) / volume * 100,
                (double) parse(best.orgnNetBuyQty()) / volume * 100);
    }

    private static long parse(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
