package com.stockadvisor.service;

import com.stockadvisor.dart.DartAnnualFactExtractor;
import com.stockadvisor.dart.DartApiClient;
import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.FinancialFact;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.FinancialFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DART 연간 재무 <b>소급 수집</b> — 종목 선정력 측정(F-Score 랭킹 스프레드)의 전제 데이터.
 *
 * <p>목적이 매매가 아니라 <b>검증</b>이라는 점이 설계를 지배한다: "물타기+트레일링을 구현하기 전에,
 * 재무 스코어에 애초에 선정력(상·하위 스프레드)이 있는가"를 먼저 답하기 위한 데이터다.
 * 스프레드가 없으면 그 뒤 작업이 전부 무의미해지므로, 이게 가장 싼 kill test다.</p>
 *
 * <p>비용: 사업보고서(11011)만 쓰면 <b>종목당 연도 1콜</b> = 10년×1,500종목 ≈ <b>15,000콜</b>.
 * DART 일일 한도(~2만) 안이라 하루면 끝난다. 분기까지 가면 6만 콜이라 3일이 걸려 과하다 —
 * 연 1회 리밸런싱이면 멀티데이 전략엔 충분하다.</p>
 *
 * <p>⚠️ <b>1콜이 당기+전기를 함께 준다</b>는 게 F-Score와 궁합이 맞는 지점이다(변화량 5개 기준을 추가 호출 없이 계산).
 * 단 연도별로 점수를 내려면 연도마다 1콜이 필요하다 — (Y)콜은 Y·Y-1을, (Y-1)콜은 Y-1·Y-2를 주므로 절약이 안 된다.</p>
 *
 * <ul>
 *   <li>이미 가진 (종목,연도)는 <b>조회조차 하지 않는다</b>(재실행 안전 + 일일 한도 보호).</li>
 *   <li>종목·연도별 실패 격리 — 상장 전 연도는 데이터 없음(013)으로 실패하는 게 정상이다.</li>
 * </ul>
 */
@Service
public class FinancialFactBackfillService {

    private static final Logger log = LoggerFactory.getLogger(FinancialFactBackfillService.class);
    private static final String ANNUAL_REPORT = "11011";
    private static final int DEFAULT_YEARS = 10;
    /** DART 일일 한도(~2만) 보호용 상한. 초과분은 다음 실행에서 이어받는다(이미 가진 키는 건너뛰므로). */
    private static final int DEFAULT_MAX_CALLS = 18000;

    private final CompanyRepository companyRepository;
    private final FinancialFactRepository factRepository;
    private final DartApiClient dartApiClient;
    private final long throttleMs;

    public FinancialFactBackfillService(CompanyRepository companyRepository,
                                        FinancialFactRepository factRepository,
                                        DartApiClient dartApiClient,
                                        @Value("${stockadvisor.financial-history.throttle-ms:60}") long throttleMs) {
        this.companyRepository = companyRepository;
        this.factRepository = factRepository;
        this.dartApiClient = dartApiClient;
        this.throttleMs = throttleMs;
    }

    /**
     * @param stocksNoCorpCode corpCode 미보유 종목 — DART 조회 자체가 불가(워치리스트 동기화가 채우는 값)
     * @param callsMade        실제 DART 호출 수(일일 한도 대비 확인용)
     * @param noData           데이터 없음(상장 전·미공시) — 실패가 아니라 정상
     */
    public record BackfillReport(int stocksTotal, int stocksNoCorpCode, int callsMade, int rowsSaved,
                                 int skippedExisting, int noData, int failed,
                                 int fromYear, int toYear, long elapsedMs, boolean hitCallLimit) {}

    public BackfillReport backfill(Integer years, Integer limit, Integer maxCalls) {
        long started = System.currentTimeMillis();
        int span = (years == null || years <= 0) ? DEFAULT_YEARS : years;
        int callCap = (maxCalls == null || maxCalls <= 0) ? DEFAULT_MAX_CALLS : maxCalls;
        // 가장 최근 사업연도는 '작년' — 올해 사업보고서는 아직 없다.
        int toYear = LocalDate.now().getYear() - 1;
        int fromYear = toYear - span + 1;

        List<Company> companies = companyRepository.findAll();
        int total = companies.size();
        if (limit != null && limit > 0 && limit < companies.size()) companies = companies.subList(0, limit);

        Set<String> existing = new HashSet<>(factRepository.findAllKeys());

        int noCorp = 0, calls = 0, saved = 0, skipped = 0, noData = 0, failed = 0;
        boolean hitLimit = false;
        List<FinancialFact> buffer = new ArrayList<>();

        outer:
        for (Company c : companies) {
            String corpCode = c.getCorpCode();
            if (corpCode == null || corpCode.isBlank()) {
                noCorp++;
                continue;
            }
            for (int year = toYear; year >= fromYear; year--) {
                String key = c.getStockCode() + ":" + year;
                if (existing.contains(key)) {
                    skipped++;
                    continue;
                }
                if (calls >= callCap) {
                    hitLimit = true;
                    break outer;
                }
                try {
                    var response = dartApiClient.fetchSingleCompanyFinancialsUncached(
                            corpCode, String.valueOf(year), ANNUAL_REPORT);
                    calls++;
                    Optional<FinancialFact> fact = DartAnnualFactExtractor.extract(
                            response, c.getStockCode(), corpCode, String.valueOf(year));
                    if (fact.isPresent()) {
                        buffer.add(fact.get());
                        saved++;
                    } else {
                        noData++;
                    }
                } catch (Exception e) {
                    // 상장 전 연도는 데이터 없음(013)으로 예외가 나는 게 정상 — 실패로 세되 중단하지 않는다.
                    calls++;
                    failed++;
                    log.debug("[재무소급] {} {}년 조회 실패: {}", c.getStockCode(), year, e.getMessage());
                }
                if (buffer.size() >= 500) {
                    factRepository.saveAll(buffer);
                    buffer.clear();
                }
                sleep();
            }
            if (calls > 0 && calls % 1000 == 0) {
                log.info("[재무소급] 진행 {}콜 · 저장 {}행", calls, saved);
            }
        }
        if (!buffer.isEmpty()) factRepository.saveAll(buffer);

        long elapsed = System.currentTimeMillis() - started;
        log.info("[재무소급] 완료 {}콜 · 저장 {}행 · 생략 {} · 데이터없음 {} · 실패 {} · {}ms",
                calls, saved, skipped, noData, failed, elapsed);
        return new BackfillReport(total, noCorp, calls, saved, skipped, noData, failed,
                fromYear, toYear, elapsed, hitLimit);
    }

    /** 수집 현황(연도별 행 수). */
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("stocks", factRepository.countDistinctStocks());
        m.put("rows", factRepository.count());
        Map<String, Long> byYear = new HashMap<>();
        for (Object[] r : factRepository.countByYear()) {
            byYear.put((String) r[0], ((Number) r[1]).longValue());
        }
        m.put("byYear", byYear);
        m.put("watchlist", companyRepository.count());
        return m;
    }

    private void sleep() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
