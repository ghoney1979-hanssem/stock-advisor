package com.stockadvisor.service;

import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.strategy.StrategyScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 워치리스트 전 종목을 주기적으로 스캔해 시장 기반(MARKET_SCAN) 전략(B/C)을 평가한다.
 * 공시와 무관한 전략이므로 공시 발생 여부와 상관없이 전 종목을 본다.
 *
 * <p>전 종목 순회라 무거움 → 15분 주기 권장. KIS 초당 제한 대비 호출 간 지연.</p>
 */
@Service
public class WatchlistScanService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistScanService.class);
    private static final long THROTTLE_MS = 60;
    private static final String CATALYST = "장중 스캔";

    private final CompanyRepository companyRepository;
    private final StrategyEvaluator evaluator;
    private final MarketBreadthService breadthService;
    private final HotWatchService hotWatchService;
    // 유니버스 스냅샷(전 종목 feature 수집) — 필드주입(생성자 무churn, 기존 단위테스트 영향 없음). 미주입이면 no-op.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UniverseSnapshotService universeSnapshotService;

    public WatchlistScanService(CompanyRepository companyRepository, StrategyEvaluator evaluator,
                                MarketBreadthService breadthService, HotWatchService hotWatchService) {
        this.companyRepository = companyRepository;
        this.evaluator = evaluator;
        this.breadthService = breadthService;
        this.hotWatchService = hotWatchService;
    }

    /** 핫셋(소집합) 스캔 — 티어드 스캔용. breadth/hotWatch 집계 없이 평가만(전수 스캔이 아니므로). */
    public int scanCodes(java.util.Collection<String> codes) {
        int alerts = 0;
        for (String code : codes) {
            try {
                alerts += evaluator.evaluateStock(code, null, "핫스캔", StrategyScope.MARKET_SCAN);
            } catch (Exception ex) {
                log.debug("핫 스캔 평가 실패 stockCode={}: {}", code, ex.getMessage());
            }
            throttle();
        }
        log.info("핫 스캔 완료: {}종목, 알림 {}건", codes.size(), alerts);
        return alerts;
    }

    /** 전 종목 스캔(스케줄러용). */
    public int scanAll() {
        return scan(null);
    }

    /**
     * 워치리스트 종목에 MARKET_SCAN 전략을 평가한다.
     *
     * @param limit 테스트용 — 앞 limit 종목만(null이면 전체)
     * @return 발송한 알림 건수
     */
    public int scan(Integer limit) {
        List<String> codes = companyRepository.findAllStockCodes();
        if (limit != null && limit < codes.size()) {
            codes = codes.subList(0, limit);
        }
        log.info("워치리스트 스캔 시작: {}종목 (MARKET_SCAN 전략)", codes.size());
        // 시장폭 집계는 전체 스캔일 때만(부분 스캔은 편향 스냅샷을 만들지 않도록).
        boolean fullScan = limit == null;
        if (fullScan) {
            breadthService.beginScan(); hotWatchService.beginScan();
            if (universeSnapshotService != null) universeSnapshotService.beginScan();
        }
        int alerts = 0, scanned = 0;
        try {
            for (String code : codes) {
                try {
                    alerts += evaluator.evaluateStock(code, null, CATALYST, StrategyScope.MARKET_SCAN);
                } catch (Exception ex) {
                    log.debug("스캔 평가 실패 stockCode={}: {}", code, ex.getMessage());
                }
                scanned++;
                throttle();
                if (scanned % 300 == 0) {
                    log.info("워치리스트 스캔 진행 {}/{} (알림 {})", scanned, codes.size(), alerts);
                }
            }
        } finally {
            if (fullScan) {   // 중간 예외에도 스냅샷 확정
                breadthService.publish(); hotWatchService.publish();
                if (universeSnapshotService != null) universeSnapshotService.flush();
            }
        }
        log.info("워치리스트 스캔 완료: {}종목, 알림 {}건", codes.size(), alerts);
        return alerts;
    }

    private void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
