package com.stockadvisor.service;

import com.stockadvisor.dart.DartCorpCodeService;
import com.stockadvisor.dart.DartCorpCodeService.ListedCompany;
import com.stockadvisor.domain.Company;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisQuoteResponse;
import com.stockadvisor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 워치리스트(company) 동기화 배치.
 *
 * <p>DART corpCode 로 전 상장종목을 얻고, 각 종목을 KIS 현재가로 조회해
 * 시장 구분과 시가총액을 보강한 뒤, 코스피/코스닥 시총 상위 N 종목을 적재한다.</p>
 *
 * <p>전 종목(~2,600)을 순회하므로 1일 1회(장 마감 후) 배치로 운영한다.
 * KIS 초당 제한 대비는 {@link KisApiClient} 의 재시도 + 본 서비스의 호출 간 지연으로 처리.</p>
 */
@Service
public class WatchlistSyncService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistSyncService.class);

    // KIS 초당 제한(20 TPS) 여유를 위한 호출 간 지연
    private static final long THROTTLE_MS = 60;

    private final DartCorpCodeService corpCodeService;
    private final KisApiClient kisApiClient;
    private final CompanyRepository companyRepository;

    // 인버스 ETF 코드 — reconcile(stale 삭제)에서 항상 유지(시총선정과 무관).
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.inverse-codes:114800,251340}")
    private String inverseCsv;

    public WatchlistSyncService(DartCorpCodeService corpCodeService,
                                KisApiClient kisApiClient,
                                CompanyRepository companyRepository) {
        this.corpCodeService = corpCodeService;
        this.kisApiClient = kisApiClient;
        this.companyRepository = companyRepository;
    }

    /** 동기화 결과 요약. */
    public record SyncResult(int scanned, int failed, int skipped,
                             int kospiSelected, int kosdaqSelected, int removed) {
    }

    /** 후보(시장/시총 보강 완료) 임시 보관 */
    private record Candidate(String stockCode, String name, String corpCode,
                             String market, long marketCap) {
    }

    /**
     * @param kospiTop  코스피 시총 상위 N
     * @param kosdaqTop 코스닥 시총 상위 N
     * @param limit     테스트용 — 전 종목 중 앞 limit 개만 처리(null 이면 전체)
     */
    public SyncResult sync(int kospiTop, int kosdaqTop, Integer limit) {
        List<ListedCompany> listed = corpCodeService.fetchListedCompanies();
        if (limit != null && limit < listed.size()) {
            listed = listed.subList(0, limit);
        }
        log.info("워치리스트 동기화 시작: 대상 {}종목 (kospiTop={}, kosdaqTop={})",
                listed.size(), kospiTop, kosdaqTop);

        List<Candidate> kospi = new ArrayList<>();
        List<Candidate> kosdaq = new ArrayList<>();
        int failed = 0, skipped = 0, scanned = 0;

        for (ListedCompany c : listed) {
            scanned++;
            try {
                KisQuoteResponse.Output out = kisApiClient.fetchCurrentQuote(c.stockCode()).output();
                String market = classifyMarket(out.marketName());
                if (market == null) {
                    skipped++;       // KONEX/기타
                    continue;
                }
                long cap = parseLong(out.marketCap());
                Candidate cand = new Candidate(c.stockCode(), c.corpName(), c.corpCode(), market, cap);
                (market.equals("KOSPI") ? kospi : kosdaq).add(cand);
            } catch (Exception ex) {
                failed++;
                log.debug("시세 조회 실패 stockCode={}: {}", c.stockCode(), ex.getMessage());
            }
            throttle();
            if (scanned % 200 == 0) {
                log.info("동기화 진행 {}/{} (코스피 {}, 코스닥 {}, 실패 {})",
                        scanned, listed.size(), kospi.size(), kosdaq.size(), failed);
            }
        }

        List<Company> selected = new ArrayList<>();
        selected.addAll(topByMarketCap(kospi, kospiTop));
        selected.addAll(topByMarketCap(kosdaq, kosdaqTop));
        companyRepository.saveAll(selected);

        // reconcile: 선정 목록에 없는 기존 종목 삭제. 전체 실행(limit==null)일 때만,
        // 그리고 선정 결과가 비어있지 않을 때만(전면 실패 시 워치리스트 전체 삭제 방지).
        int removed = 0;
        if (limit == null && !selected.isEmpty()) {
            List<String> codes = new ArrayList<>(selected.stream().map(Company::getStockCode).toList());
            // 인버스 ETF(하락장 수익용)는 시총선정과 무관하게 항상 유지 — reconcile 삭제 대상에서 제외
            for (String inv : inverseCsv == null ? new String[0] : inverseCsv.split(",")) {
                String c = inv.trim();
                if (!c.isEmpty()) codes.add(c);
            }
            removed = companyRepository.deleteByStockCodeNotIn(codes);
        } else if (limit != null) {
            log.info("limit 지정 실행 — reconcile(stale 삭제) 생략");
        }

        int kospiSel = (int) selected.stream().filter(s -> "KOSPI".equals(s.getMarket())).count();
        int kosdaqSel = selected.size() - kospiSel;
        log.info("워치리스트 동기화 완료: 적재 {}종목 (코스피 {}, 코스닥 {}), 실패 {}, 스킵 {}, 삭제 {}",
                selected.size(), kospiSel, kosdaqSel, failed, skipped, removed);
        return new SyncResult(scanned, failed, skipped, kospiSel, kosdaqSel, removed);
    }

    private List<Company> topByMarketCap(List<Candidate> candidates, int topN) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(Candidate::marketCap).reversed())
                .limit(topN)
                .map(c -> new Company(c.stockCode(), c.name(), c.corpCode(), c.market()))
                .toList();
    }

    /** 대표시장명(KOSPI200/KSQ150/KONEX 등) → KOSPI/KOSDAQ/null(기타). */
    private String classifyMarket(String marketName) {
        if (marketName == null) return null;
        if (marketName.contains("KOSPI")) return "KOSPI";
        if (marketName.contains("KSQ") || marketName.contains("KOSDAQ")) return "KOSDAQ";
        return null;
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
