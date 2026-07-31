package com.stockadvisor.dart;

import com.stockadvisor.common.ExternalApiException;
import com.stockadvisor.dart.dto.DartFinancialResponse;
import com.stockadvisor.dart.dto.DartFinancialResponse.FinancialItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * DART 주요계정 응답을 가공해 {@link FinancialSummary} 로 제공하는 서비스.
 *
 * <p>가장 최근 사업보고서(11011)를 조회한다. 올해 사업보고서가 아직 미공시이거나
 * DART 연동이 실패하면 재무데이터 없이(Optional.empty) 처리해 추천 자체는 동작하도록 한다.</p>
 */
@Service
public class DartFinancialService {

    private static final Logger log = LoggerFactory.getLogger(DartFinancialService.class);

    /** 사업보고서 코드 (연간) */
    private static final String ANNUAL_REPORT = "11011";

    private final DartApiClient dartApiClient;

    public DartFinancialService(DartApiClient dartApiClient) {
        this.dartApiClient = dartApiClient;
    }

    /**
     * 최근 연간 재무 요약 조회. corpCode 가 없거나 데이터가 없으면 empty.
     * 직전 사업연도부터 최대 2개년을 역순으로 시도한다(연초엔 전년도 보고서가 미공시일 수 있음).
     */
    public Optional<FinancialSummary> getLatestAnnualSummary(String corpCode) {
        if (corpCode == null || corpCode.isBlank()) {
            return Optional.empty();
        }
        int startYear = LocalDate.now().getYear() - 1;
        for (int year = startYear; year >= startYear - 1; year--) {
            try {
                DartFinancialResponse response =
                        dartApiClient.fetchSingleCompanyFinancials(corpCode, String.valueOf(year), ANNUAL_REPORT);
                Optional<FinancialSummary> summary = extract(response, String.valueOf(year));
                if (summary.isPresent()) {
                    return summary;
                }
            } catch (ExternalApiException ex) {
                // 해당 연도 데이터 없음(013) 등 — 이전 연도로 폴백
                log.debug("DART 재무 조회 실패 corpCode={} year={}: {}", corpCode, year, ex.getMessage());
            }
        }
        log.warn("DART 재무데이터를 찾지 못함 corpCode={} (최근 2개년)", corpCode);
        return Optional.empty();
    }

    /** 주요계정 리스트에서 필요한 항목을 추출한다. 연결(CFS) 우선, 없으면 별도(OFS). */
    private Optional<FinancialSummary> extract(DartFinancialResponse response, String year) {
        if (response == null || response.list() == null || response.list().isEmpty()) {
            return Optional.empty();
        }
        String fsDiv = response.list().stream().anyMatch(it -> "CFS".equals(it.financialStatementDiv()))
                ? "CFS" : "OFS";
        List<FinancialItem> items = response.list().stream()
                .filter(it -> fsDiv.equals(it.financialStatementDiv()))
                .toList();

        long revenue = currentAmount(items, nm -> nm.equals("매출액") || nm.equals("영업수익") || nm.equals("수익(매출액)"));
        long prevRevenue = previousAmount(items, nm -> nm.equals("매출액") || nm.equals("영업수익") || nm.equals("수익(매출액)"));
        long operatingProfit = currentAmount(items, nm -> nm.startsWith("영업이익"));
        long netIncome = currentAmount(items, nm -> nm.startsWith("당기순이익"));
        long totalLiabilities = currentAmount(items, nm -> nm.equals("부채총계"));
        long totalEquity = currentAmount(items, nm -> nm.equals("자본총계"));

        // 매출·영업이익·자본총계가 모두 비어 있으면 의미 있는 분석 불가
        if (revenue == 0 && operatingProfit == 0 && totalEquity == 0) {
            return Optional.empty();
        }
        return Optional.of(new FinancialSummary(
                year, fsDiv, revenue, prevRevenue, operatingProfit, netIncome, totalLiabilities, totalEquity));
    }

    private long currentAmount(List<FinancialItem> items, Predicate<String> nameMatch) {
        return items.stream()
                .filter(it -> it.accountName() != null && nameMatch.test(it.accountName()))
                .findFirst()
                .map(it -> parseAmount(it.currentTermAmount()))
                .orElse(0L);
    }

    private long previousAmount(List<FinancialItem> items, Predicate<String> nameMatch) {
        return items.stream()
                .filter(it -> it.accountName() != null && nameMatch.test(it.accountName()))
                .findFirst()
                .map(it -> parseAmount(it.previousTermAmount()))
                .orElse(0L);
    }

    /** DART 금액 문자열(예: "333,605,938,000,000", "-1,234") → long. */
    private long parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String cleaned = raw.replace(",", "").trim();
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
