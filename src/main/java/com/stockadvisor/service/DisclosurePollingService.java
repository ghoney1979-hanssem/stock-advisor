package com.stockadvisor.service;

import com.stockadvisor.dart.DartApiClient;
import com.stockadvisor.dart.dto.DartDisclosureResponse;
import com.stockadvisor.domain.Disclosure;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DART 최신 공시를 폴링해 워치리스트(company) 종목의 신규 공시를 적재한다.
 * 적재된 공시는 이후 {@link SignalAlertService} 가 신호 평가/알림 대상으로 사용한다.
 */
@Service
public class DisclosurePollingService {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePollingService.class);

    // 1분 주기 폴링이 놓치지 않도록 넉넉히 최신 100건 조회
    private static final int PAGE_COUNT = 100;

    private final DartApiClient dartApiClient;
    private final CompanyRepository companyRepository;
    private final DisclosureRepository disclosureRepository;

    public DisclosurePollingService(DartApiClient dartApiClient,
                                    CompanyRepository companyRepository,
                                    DisclosureRepository disclosureRepository) {
        this.dartApiClient = dartApiClient;
        this.companyRepository = companyRepository;
        this.disclosureRepository = disclosureRepository;
    }

    /**
     * 최신 공시를 조회해 워치리스트 종목의 신규 공시를 저장한다.
     *
     * @return 새로 적재된 공시 건수
     */
    @Transactional
    public int pollAndStore() {
        Set<String> watchlist = new HashSet<>(companyRepository.findAllStockCodes());
        if (watchlist.isEmpty()) {
            log.debug("워치리스트가 비어 있어 공시 폴링을 건너뜀");
            return 0;
        }

        List<DartDisclosureResponse.Disclosure> recent = dartApiClient.fetchRecentDisclosures(PAGE_COUNT);
        int stored = 0;
        for (DartDisclosureResponse.Disclosure d : recent) {
            if (!watchlist.contains(d.stockCode())) {
                continue;   // 워치리스트 외 종목 공시는 무시
            }
            if (disclosureRepository.existsByReceiptNo(d.receiptNo())) {
                continue;   // 이미 적재된 공시
            }
            disclosureRepository.save(Disclosure.builder()
                    .receiptNo(d.receiptNo())
                    .stockCode(d.stockCode())
                    .corpCode(d.corpCode())
                    .reportName(d.reportName() == null ? null : d.reportName().trim())
                    .receiptDate(d.receiptDate())
                    .build());
            stored++;
            log.info("신규 공시 감지 [{}] {} - {}", d.stockCode(), d.corpName(), d.reportName());
        }
        if (stored > 0) {
            log.info("공시 폴링 완료: 신규 {}건 적재 (조회 {}건)", stored, recent.size());
        }
        return stored;
    }
}
