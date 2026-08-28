package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DART 주요계정(연간)의 <b>원시 재무 항목</b> — 종목×사업연도 1행.
 *
 * <p>⚠️ <b>점수가 아니라 원시 항목을 저장하는 게 설계의 핵심</b>이다. DART는 일일 호출 한도(~2만)가 있어
 * 10년×1,500종목(15,000콜) 재수집이 비싼 반면, 점수 공식은 반드시 바뀐다(현재 7개 축소판 → 현금흐름 2개 추가).
 * 원시값을 쥐고 있으면 공식 변경이 <b>재계산 0콜</b>로 끝난다.</p>
 *
 * <p>DART 주요계정은 <b>1콜에 당기+전기</b>를 함께 주므로 전년 대비 변화(ΔROA·Δ부채비율 등)를
 * 추가 호출 없이 계산할 수 있다 — F-Score가 요구하는 게 정확히 그 변화량이라 궁합이 맞는다.</p>
 *
 * <p>⚠️ 금융업(은행·보험)은 유동자산/유동부채 항목이 없다 → 0으로 저장되고 해당 지표는 판정 불가로 처리된다
 * ({@code evaluated} 카운트로 드러남). 0을 "나쁨"으로 치면 업종 전체가 조용히 하위 버킷으로 밀린다.</p>
 */
@Entity
@Table(name = "financial_fact", indexes = {
        @Index(name = "idx_fin_fact_code_year", columnList = "stock_code, business_year", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    @Column(name = "corp_code", length = 8)
    private String corpCode;

    /** 사업연도(예: "2024"). 공시는 통상 이듬해 3~4월이라 <b>사용 시점은 Y+1년 4월 이후</b>여야 한다. */
    @Column(name = "business_year", length = 4, nullable = false)
    private String businessYear;

    /** CFS(연결) 우선, 없으면 OFS(별도). */
    @Column(name = "fs_div", length = 4)
    private String fsDiv;

    // ── 당기 ─────────────────────────────────────────────
    @Column(name = "revenue") private long revenue;
    @Column(name = "operating_profit") private long operatingProfit;
    @Column(name = "net_income") private long netIncome;
    @Column(name = "total_assets") private long totalAssets;
    @Column(name = "total_liabilities") private long totalLiabilities;
    @Column(name = "total_equity") private long totalEquity;
    @Column(name = "current_assets") private long currentAssets;
    @Column(name = "current_liabilities") private long currentLiabilities;
    @Column(name = "capital_stock") private long capitalStock;

    // ── 전기(같은 응답에 함께 온다 — 추가 호출 0) ──────────
    @Column(name = "prev_revenue") private long prevRevenue;
    @Column(name = "prev_operating_profit") private long prevOperatingProfit;
    @Column(name = "prev_net_income") private long prevNetIncome;
    @Column(name = "prev_total_assets") private long prevTotalAssets;
    @Column(name = "prev_total_liabilities") private long prevTotalLiabilities;
    @Column(name = "prev_total_equity") private long prevTotalEquity;
    @Column(name = "prev_current_assets") private long prevCurrentAssets;
    @Column(name = "prev_current_liabilities") private long prevCurrentLiabilities;
    @Column(name = "prev_capital_stock") private long prevCapitalStock;

    public FinancialFact(String stockCode, String corpCode, String businessYear, String fsDiv,
                         long revenue, long operatingProfit, long netIncome,
                         long totalAssets, long totalLiabilities, long totalEquity,
                         long currentAssets, long currentLiabilities, long capitalStock,
                         long prevRevenue, long prevOperatingProfit, long prevNetIncome,
                         long prevTotalAssets, long prevTotalLiabilities, long prevTotalEquity,
                         long prevCurrentAssets, long prevCurrentLiabilities, long prevCapitalStock) {
        this.stockCode = stockCode;
        this.corpCode = corpCode;
        this.businessYear = businessYear;
        this.fsDiv = fsDiv;
        this.revenue = revenue;
        this.operatingProfit = operatingProfit;
        this.netIncome = netIncome;
        this.totalAssets = totalAssets;
        this.totalLiabilities = totalLiabilities;
        this.totalEquity = totalEquity;
        this.currentAssets = currentAssets;
        this.currentLiabilities = currentLiabilities;
        this.capitalStock = capitalStock;
        this.prevRevenue = prevRevenue;
        this.prevOperatingProfit = prevOperatingProfit;
        this.prevNetIncome = prevNetIncome;
        this.prevTotalAssets = prevTotalAssets;
        this.prevTotalLiabilities = prevTotalLiabilities;
        this.prevTotalEquity = prevTotalEquity;
        this.prevCurrentAssets = prevCurrentAssets;
        this.prevCurrentLiabilities = prevCurrentLiabilities;
        this.prevCapitalStock = prevCapitalStock;
    }
}
