package com.stockadvisor.dart;

import com.stockadvisor.domain.FinancialFact;
import com.stockadvisor.dart.dto.DartFinancialResponse;
import com.stockadvisor.dart.dto.DartFinancialResponse.FinancialItem;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * DART 주요계정 응답 → {@link FinancialFact} 추출(<b>순수 정적</b> — 네트워크 없이 테스트).
 *
 * <p>{@code DartFinancialService.extract}와 계정명 매칭 규칙을 공유하지만 목적이 다르다: 저쪽은 추천 점수용
 * 파생 지표 3개(성장·마진·부채)만 뽑고, 여기는 <b>F-Score가 요구하는 원시 항목 전부</b>를 당기·전기 양쪽으로 뽑는다.</p>
 *
 * <p>⚠️ 연결(CFS) 우선, 없으면 별도(OFS) — 둘을 섞으면 같은 회사의 연도별 값이 서로 다른 기준이 돼
 * 전년 대비 변화(F-Score의 5개 기준)가 통째로 허수가 된다. <b>한 응답 안에서 한 기준만</b> 쓴다.</p>
 */
public final class DartAnnualFactExtractor {

    private DartAnnualFactExtractor() {}

    public static Optional<FinancialFact> extract(DartFinancialResponse response,
                                                  String stockCode, String corpCode, String year) {
        if (response == null || response.list() == null || response.list().isEmpty()) {
            return Optional.empty();
        }
        String fsDiv = response.list().stream().anyMatch(it -> "CFS".equals(it.financialStatementDiv()))
                ? "CFS" : "OFS";
        List<FinancialItem> items = response.list().stream()
                .filter(it -> fsDiv.equals(it.financialStatementDiv()))
                .toList();

        Predicate<String> revenueNm = nm -> nm.equals("매출액") || nm.equals("영업수익") || nm.equals("수익(매출액)");
        Predicate<String> opNm = nm -> nm.startsWith("영업이익");
        Predicate<String> niNm = nm -> nm.startsWith("당기순이익");

        long revenue = cur(items, revenueNm);
        long operatingProfit = cur(items, opNm);
        long netIncome = cur(items, niNm);
        long totalAssets = cur(items, nm -> nm.equals("자산총계"));
        long totalLiabilities = cur(items, nm -> nm.equals("부채총계"));
        long totalEquity = cur(items, nm -> nm.equals("자본총계"));
        long currentAssets = cur(items, nm -> nm.equals("유동자산"));
        long currentLiabilities = cur(items, nm -> nm.equals("유동부채"));
        long capitalStock = cur(items, nm -> nm.equals("자본금"));

        // 자산총계·자본총계가 둘 다 없으면 의미 있는 재무 분석 자체가 불가
        if (totalAssets == 0 && totalEquity == 0) return Optional.empty();

        return Optional.of(new FinancialFact(
                stockCode, corpCode, year, fsDiv,
                revenue, operatingProfit, netIncome,
                totalAssets, totalLiabilities, totalEquity,
                currentAssets, currentLiabilities, capitalStock,
                prev(items, revenueNm), prev(items, opNm), prev(items, niNm),
                prev(items, nm -> nm.equals("자산총계")),
                prev(items, nm -> nm.equals("부채총계")),
                prev(items, nm -> nm.equals("자본총계")),
                prev(items, nm -> nm.equals("유동자산")),
                prev(items, nm -> nm.equals("유동부채")),
                prev(items, nm -> nm.equals("자본금"))));
    }

    private static long cur(List<FinancialItem> items, Predicate<String> match) {
        return pick(items, match, true);
    }

    private static long prev(List<FinancialItem> items, Predicate<String> match) {
        return pick(items, match, false);
    }

    private static long pick(List<FinancialItem> items, Predicate<String> match, boolean current) {
        return items.stream()
                .filter(it -> it.accountName() != null && match.test(it.accountName()))
                .findFirst()
                .map(it -> parseAmount(current ? it.currentTermAmount() : it.previousTermAmount()))
                .orElse(0L);
    }

    /** DART 금액 문자열("333,605,938,000,000", "-1,234", "") → long. 파싱 불가는 0(=해당없음). */
    static long parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String cleaned = raw.replace(",", "").trim();
        if (cleaned.equals("-")) return 0;
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
