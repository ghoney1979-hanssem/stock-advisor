package com.stockadvisor.dart;

/**
 * DART 주요계정에서 추출·가공한 재무 요약.
 * 금액 단위는 원(KRW). 비율 계산용 파생 지표를 제공한다.
 *
 * @param businessYear      사업연도
 * @param fsDiv             재무제표 구분 (CFS 연결 / OFS 별도)
 * @param revenue           당기 매출액
 * @param prevRevenue       전기 매출액
 * @param operatingProfit   당기 영업이익
 * @param netIncome         당기 당기순이익
 * @param totalLiabilities  부채총계
 * @param totalEquity       자본총계
 */
public record FinancialSummary(
        String businessYear,
        String fsDiv,
        long revenue,
        long prevRevenue,
        long operatingProfit,
        long netIncome,
        long totalLiabilities,
        long totalEquity
) {

    /** 매출 성장률(%) — 전기 대비. 전기 매출이 0 이하이면 0 반환. */
    public double revenueGrowthRate() {
        if (prevRevenue <= 0) return 0;
        return (double) (revenue - prevRevenue) / prevRevenue * 100;
    }

    /** 영업이익률(%) = 영업이익 / 매출액. */
    public double operatingMargin() {
        if (revenue <= 0) return 0;
        return (double) operatingProfit / revenue * 100;
    }

    /** 부채비율(%) = 부채총계 / 자본총계. 자본총계가 0 이하이면 매우 큰 값 반환(부실 신호). */
    public double debtRatio() {
        if (totalEquity <= 0) return 999;
        return (double) totalLiabilities / totalEquity * 100;
    }
}
