package com.stockadvisor.service;

import com.stockadvisor.domain.Company;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisQuoteResponse;
import com.stockadvisor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 워치리스트 종목의 <b>액면가·상장주식수</b>를 KIS 현재가에서 받아 {@code company}에 저장한다(2026-08-29).
 *
 * <p><b>왜 필요한가</b>: 가치 축(PBR·이익수익률) 백테스트에는 <b>과거 시점의 시가총액</b>이 필요한데
 * 과거 상장주식수 이력이 없다. 대신 {@code financial_fact.capital_stock}(사업연도별 자본금)이 있으므로
 * <b>주식수ᵧ = 자본금ᵧ ÷ 액면가</b>로 복원한다. 일봉이 수정주가라 액면분할은 자본금이 불변·주가가 조정돼
 * 자동으로 정합하고, 증자·감자는 자본금 변화로 반영된다. (우선주 자본금 포함·무액면주 미대응은 근사 오차로 수용.)</p>
 *
 * <p>비용: 종목당 KIS 1콜, <b>캐시 우회</b>(백필은 키마다 한 번이라 캐시 이득이 없고 Redis만 채운다).
 * 이미 값이 있는 종목은 {@code force=false}면 건너뛴다.</p>
 */
@Service
public class ShareInfoBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ShareInfoBackfillService.class);

    private final CompanyRepository companyRepository;
    private final KisApiClient kisApiClient;

    public ShareInfoBackfillService(CompanyRepository companyRepository, KisApiClient kisApiClient) {
        this.companyRepository = companyRepository;
        this.kisApiClient = kisApiClient;
    }

    public record Report(int companies, int fetched, int saved, int skipped, int failed, long seconds) {}

    public Report backfill(boolean force, Integer limit) {
        long t0 = System.currentTimeMillis();
        List<Company> all = companyRepository.findAll();
        int fetched = 0, saved = 0, skipped = 0, failed = 0;
        for (Company c : all) {
            if (limit != null && fetched >= limit) break;
            if (!force && c.getFaceValue() != null && c.getFaceValue() > 0) { skipped++; continue; }
            fetched++;
            try {
                KisQuoteResponse.Output o = kisApiClient.fetchCurrentQuoteUncached(c.getStockCode()).output();
                Long face = parseLong(o == null ? null : o.faceValue());
                Long shares = parseLong(o == null ? null : o.listedShares());
                if (face == null && shares == null) { failed++; continue; }
                c.setShareInfo(face, shares);
                companyRepository.save(c);
                saved++;
            } catch (Exception e) {
                failed++;
                log.debug("액면가/주식수 조회 실패 {}: {}", c.getStockCode(), e.getMessage());
            }
        }
        long sec = (System.currentTimeMillis() - t0) / 1000;
        log.info("[주식수백필] 대상 {} · 조회 {} · 저장 {} · 스킵 {} · 실패 {} · {}초", all.size(), fetched, saved, skipped, failed, sec);
        return new Report(all.size(), fetched, saved, skipped, failed, sec);
    }

    static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            long v = Long.parseLong(s.trim().replace(",", ""));
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
