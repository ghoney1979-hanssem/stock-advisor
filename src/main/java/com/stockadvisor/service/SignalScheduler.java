package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공시 폴링/신호 평가/워치리스트 동기화 스케줄러.
 * cron 은 application.yml 에서 주입(장중 중심, 평일). 장기 실행 동기화 대비 스케줄러 풀은 확장돼 있음.
 */
@Component
public class SignalScheduler {

    private static final Logger log = LoggerFactory.getLogger(SignalScheduler.class);

    private final DisclosurePollingService pollingService;
    private final SignalAlertService signalAlertService;
    private final WatchlistSyncService watchlistSyncService;
    private final WatchlistScanService watchlistScanService;
    private final TradeFollowUpService tradeFollowUpService;
    private final DailyReportService dailyReportService;
    private final PositionExitService positionExitService;
    private final FillSyncService fillSyncService;
    private final OrderCancelService orderCancelService;
    private final OrderService orderService;
    private final MarketOpenNotifier marketOpenNotifier;
    private final GateChangeNotifier gateChangeNotifier;
    private final HotWatchService hotWatchService;
    private final boolean tieredScanEnabled;
    private final int kospiTop;
    private final int kosdaqTop;

    public SignalScheduler(DisclosurePollingService pollingService,
                           SignalAlertService signalAlertService,
                           WatchlistSyncService watchlistSyncService,
                           WatchlistScanService watchlistScanService,
                           TradeFollowUpService tradeFollowUpService,
                           DailyReportService dailyReportService,
                           PositionExitService positionExitService,
                           FillSyncService fillSyncService,
                           OrderCancelService orderCancelService,
                           OrderService orderService,
                           MarketOpenNotifier marketOpenNotifier,
                           GateChangeNotifier gateChangeNotifier,
                           HotWatchService hotWatchService,
                           @Value("${stockadvisor.tiered-scan.enabled:true}") boolean tieredScanEnabled,
                           @Value("${stockadvisor.watchlist.kospi-top:1000}") int kospiTop,
                           @Value("${stockadvisor.watchlist.kosdaq-top:500}") int kosdaqTop) {
        this.pollingService = pollingService;
        this.signalAlertService = signalAlertService;
        this.watchlistSyncService = watchlistSyncService;
        this.watchlistScanService = watchlistScanService;
        this.tradeFollowUpService = tradeFollowUpService;
        this.dailyReportService = dailyReportService;
        this.positionExitService = positionExitService;
        this.fillSyncService = fillSyncService;
        this.orderCancelService = orderCancelService;
        this.orderService = orderService;
        this.marketOpenNotifier = marketOpenNotifier;
        this.gateChangeNotifier = gateChangeNotifier;
        this.hotWatchService = hotWatchService;
        this.tieredScanEnabled = tieredScanEnabled;
        this.kospiTop = kospiTop;
        this.kosdaqTop = kosdaqTop;
    }

    /** 장 시작 알림 — 시초 국면 + 전략 진입 태세(화이트리스트·게이트 통과) 통지 (기본 평일 09:00). */
    @Scheduled(cron = "${stockadvisor.scheduler.market-open-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void marketOpen() {
        try {
            marketOpenNotifier.notifyMarketOpen();
        } catch (Exception ex) {
            log.error("장 시작 알림 스케줄 실행 오류", ex);
        }
    }

    /** 게이트 통과 집합 변화 감시 — 장중 전이(열림/닫힘/fallback축소) 실시간 통지 (기본 5분). */
    @Scheduled(cron = "${stockadvisor.scheduler.gate-change-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void watchGateChanges() {
        try {
            gateChangeNotifier.checkAndNotify();
        } catch (Exception ex) {
            log.error("게이트 변화 감시 스케줄 실행 오류", ex);
        }
    }

    /** 일별 성과 리포트 Discord 발송 (기본 평일 16:30). */
    @Scheduled(cron = "${stockadvisor.scheduler.daily-report-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void dailyReport() {
        try {
            dailyReportService.sendDailyReport();
        } catch (Exception ex) {
            log.error("일별 리포트 스케줄 실행 오류", ex);
        }
    }

    /** 워치리스트 전 종목 스캔 — 시장 기반 전략(B/C) 평가 (기본 15분, 장중 평일). */
    @Scheduled(cron = "${stockadvisor.scheduler.market-scan-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void scanWatchlist() {
        try {
            watchlistScanService.scanAll();
        } catch (Exception ex) {
            log.error("워치리스트 스캔 스케줄 실행 오류", ex);
        }
    }

    /** 티어드 스캔 — 핫셋(곧 급증 근접 소집합)만 자주(기본 2분) 평가해 급변 조기 포착. 핫셋 비면 skip. */
    @Scheduled(cron = "${stockadvisor.scheduler.hot-scan-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void scanHot() {
        if (!tieredScanEnabled) return;
        try {
            var codes = hotWatchService.hotCodes();
            if (!codes.isEmpty()) watchlistScanService.scanCodes(codes);
        } catch (Exception ex) {
            log.error("핫 스캔 스케줄 실행 오류", ex);
        }
    }

    /** 공시 폴링 (기본 1분) */
    @Scheduled(cron = "${stockadvisor.scheduler.disclosure-poll-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void pollDisclosures() {
        try {
            pollingService.pollAndStore();
        } catch (Exception ex) {
            log.error("공시 폴링 스케줄 실행 오류", ex);
        }
    }

    /** 신호 평가 + 알림 (기본 5분) */
    @Scheduled(cron = "${stockadvisor.scheduler.signal-eval-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void evaluateSignals() {
        try {
            signalAlertService.scanAndAlert();
        } catch (Exception ex) {
            log.error("신호 평가 스케줄 실행 오류", ex);
        }
    }

    /** 매수가정 가격추적 후속알림 (기본 1분 주기). +5분/+10분 샘플, +10분에 후속 발송. */
    @Scheduled(cron = "${stockadvisor.scheduler.follow-up-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void trackFollowUps() {
        try {
            tradeFollowUpService.processFollowUps();
        } catch (Exception ex) {
            log.error("후속 추적 스케줄 실행 오류", ex);
        }
    }

    /** 체결 동기화 — LIVE 접수 주문의 실제 체결 반영 (기본 1분 주기, DRY_RUN은 no-op). */
    @Scheduled(cron = "${stockadvisor.scheduler.follow-up-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void syncFills() {
        try {
            fillSyncService.syncFills();
        } catch (Exception ex) {
            log.error("체결 동기화 스케줄 실행 오류", ex);
        }
    }

    /** 미체결 주문 취소 — 타임아웃 경과 지정가 취소(다음 틱 재주문=추격) (기본 1분, DRY_RUN no-op). */
    @Scheduled(cron = "${stockadvisor.scheduler.follow-up-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void cancelStaleOrders() {
        try {
            orderCancelService.cancelStaleOrders();
        } catch (Exception ex) {
            log.error("미체결 취소 스케줄 실행 오류", ex);
        }
    }

    /** 승인 대기 만료 — 타임아웃 경과 미승인 주문 자동 거부 (기본 1분). */
    @Scheduled(cron = "${stockadvisor.scheduler.follow-up-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void expireApprovals() {
        try {
            orderService.expireStaleApprovals();
        } catch (Exception ex) {
            log.error("승인 만료 스케줄 실행 오류", ex);
        }
    }

    /** 시간기반 청산 — 보유시간 경과/장마감 포지션 매도 (기본 1분 주기). */
    @Scheduled(cron = "${stockadvisor.scheduler.follow-up-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void closePositions() {
        try {
            positionExitService.closeDuePositions();
        } catch (Exception ex) {
            log.error("포지션 청산 스케줄 실행 오류", ex);
        }
    }

    /** 워치리스트 동기화 (기본 장 마감 후 1일 1회). 전 종목 순회 + reconcile. */
    @Scheduled(cron = "${stockadvisor.scheduler.watchlist-sync-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void syncWatchlist() {
        try {
            log.info("워치리스트 정기 동기화 시작 (코스피 {}, 코스닥 {})", kospiTop, kosdaqTop);
            watchlistSyncService.sync(kospiTop, kosdaqTop, null);
        } catch (Exception ex) {
            log.error("워치리스트 동기화 스케줄 실행 오류", ex);
        }
    }
}
