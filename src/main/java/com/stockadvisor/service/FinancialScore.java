package com.stockadvisor.service;

import com.stockadvisor.domain.FinancialFact;

/**
 * 축소판 F-Score(Piotroski) — <b>순수 정적</b>. 종목 선정력을 측정할 스코어.
 *
 * <p><b>왜 F-Score인가</b>: 유명해서가 아니라 <b>원 질문이 이 전략의 셋업과 같기 때문</b>이다 —
 * "싸진(떨어진) 종목 중 재무적으로 건강해서 회복할 놈은 누구인가". 기준이 전부 이진이라 튜닝 여지가 거의 없어
 * 과적합 저항도 좋다(이 시스템이 ATR·업종 pocket을 다중검정으로 죽인 전례를 생각하면 중요한 성질이다).</p>
 *
 * <p>7개 기준(원본 9개 중):</p>
 * <ol>
 *   <li>ROA &gt; 0</li>
 *   <li>ΔROA &gt; 0 (전년 대비 개선)</li>
 *   <li>Δ레버리지 감소 (부채/자산)</li>
 *   <li>Δ유동비율 증가 (유동자산/유동부채)</li>
 *   <li>신주발행 없음 (자본금 불변 이하)</li>
 *   <li>Δ영업이익률 증가 — 원본의 매출총이익률 대용(주요계정에 매출원가가 없다)</li>
 *   <li>Δ자산회전율 증가 (매출/자산)</li>
 * </ol>
 *
 * <p>⚠️ <b>빠진 2개가 하필 중요하다</b>: {@code CFO > 0}과 {@code CFO > 당기순이익}(발생액).
 * 후자가 F-Score에서 <b>회계상 이익 부풀리기를 잡는 핵심</b>인데, DART 주요계정엔 현금흐름표가 없어 계산 불가다
 * ({@code fnlttSinglAcntAll} 전체 재무제표로 바꾸면 가능 — 스프레드가 확인되면 그때 추가할 것).
 * 즉 지금 스코어는 <b>이익의 질을 못 본다</b>.</p>
 *
 * <p>⚠️ <b>판정 불가 항목을 미충족(0)으로 치지 않는다.</b> 금융업은 유동자산/유동부채가 아예 없어 4번이 계산되지
 * 않는데, 0으로 처리하면 업종 전체가 조용히 하위 버킷으로 밀려 <b>스코어가 업종 더미변수로 변질</b>된다.
 * → {@code evaluated}(판정 가능했던 항목 수)를 함께 반환하고, 분석에서 {@code minEvaluated}로 거른다.</p>
 */
public final class FinancialScore {

    private FinancialScore() {}

    public static final int MAX = 7;

    /**
     * @param score     충족한 기준 수(0~7)
     * @param evaluated 판정 가능했던 기준 수 — 이게 작으면 score를 그대로 비교하면 안 된다
     * @param detail    기준별 충족 여부(진단용, 순서는 위 목록과 동일)
     */
    public record Result(int score, int evaluated, boolean[] detail) {}

    public static Result of(FinancialFact f) {
        boolean[] d = new boolean[MAX];
        int score = 0, evaluated = 0;

        // 1) ROA > 0
        Double roa = ratio(f.getNetIncome(), f.getTotalAssets());
        if (roa != null) {
            evaluated++;
            if (roa > 0) { d[0] = true; score++; }
        }

        // 2) ΔROA > 0
        Double prevRoa = ratio(f.getPrevNetIncome(), f.getPrevTotalAssets());
        if (roa != null && prevRoa != null) {
            evaluated++;
            if (roa > prevRoa) { d[1] = true; score++; }
        }

        // 3) 레버리지 감소 (부채/자산) — 자본총계 분모(부채비율)가 아니라 자산 분모를 쓴다.
        //    자본잠식 종목에서 부채비율이 음수/발산해 순위가 뒤집히는 것을 피하기 위함.
        Double lev = ratio(f.getTotalLiabilities(), f.getTotalAssets());
        Double prevLev = ratio(f.getPrevTotalLiabilities(), f.getPrevTotalAssets());
        if (lev != null && prevLev != null) {
            evaluated++;
            if (lev < prevLev) { d[2] = true; score++; }
        }

        // 4) 유동비율 증가 — 금융업은 항목 자체가 없어 판정 불가(0 처리 금지)
        Double cur = ratio(f.getCurrentAssets(), f.getCurrentLiabilities());
        Double prevCur = ratio(f.getPrevCurrentAssets(), f.getPrevCurrentLiabilities());
        if (cur != null && prevCur != null) {
            evaluated++;
            if (cur > prevCur) { d[3] = true; score++; }
        }

        // 5) 신주발행 없음 — 원본은 발행주식수, 여기선 자본금으로 근사(무상증자·액면분할엔 오탐 가능)
        if (f.getCapitalStock() > 0 && f.getPrevCapitalStock() > 0) {
            evaluated++;
            if (f.getCapitalStock() <= f.getPrevCapitalStock()) { d[4] = true; score++; }
        }

        // 6) 영업이익률 증가 — 원본의 매출총이익률 대용(주요계정에 매출원가 없음)
        Double margin = ratio(f.getOperatingProfit(), f.getRevenue());
        Double prevMargin = ratio(f.getPrevOperatingProfit(), f.getPrevRevenue());
        if (margin != null && prevMargin != null) {
            evaluated++;
            if (margin > prevMargin) { d[5] = true; score++; }
        }

        // 7) 자산회전율 증가 (매출/자산)
        Double turn = ratio(f.getRevenue(), f.getTotalAssets());
        Double prevTurn = ratio(f.getPrevRevenue(), f.getPrevTotalAssets());
        if (turn != null && prevTurn != null) {
            evaluated++;
            if (turn > prevTurn) { d[6] = true; score++; }
        }

        return new Result(score, evaluated, d);
    }

    /** 분모가 0 이하(미공시·해당없음)면 null = <b>판정 불가</b>. 0으로 퉁치면 스코어가 업종 더미가 된다. */
    private static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return (double) numerator / denominator;
    }
}
