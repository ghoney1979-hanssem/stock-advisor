package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeDailyMark;
import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse.DailyPrice;
import com.stockadvisor.repository.OutcomeDailyMarkRepository;
import com.stockadvisor.repository.OutcomeSampleRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 전략별 가상매수의 horizon 가격 수집.
 *
 * <p>장중에는 +5/+10/+30분 가격을 분봉 현재가로 샘플링하고,
 * 당일종가·익일종가는 일봉 날짜 매칭으로 사후 수집한다(정확하고 견고).
 * A전략(알림 전략)에 한해 +10분 시점에 성과 후속 Discord 1회 발송한다.</p>
 */
@Service
public class TradeFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(TradeFollowUpService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration MAX_TRACK = Duration.ofDays(8);   // D+3 거래일 종가까지 추적(연휴 끼면 ~7캘린더일 → 8일 여유)
    private static final int EOD_MARK = -1;     // 당일종가 마크
    private static final long GRACE_MIN = 3;    // 마크 샘플 허용 지연(분)

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final OutcomeSampleRepository outcomeSampleRepository;
    private final OutcomeDailyMarkRepository dailyMarkRepository;
    private final KisApiClient kisApiClient;
    private final Duration firstDelay;
    private final Duration secondDelay;
    private final Duration thirdDelay;
    private final int[] exitMarks;                       // 가격 샘플 마크(촘촘, 5분 단위) — 청산시점 최적화용
    private final java.util.Set<Integer> vwapMarks;      // 이 마크에서만 VWAP 조회(성김) — VWAP는 청산방식 시뮬용이라 정밀도 불요, KIS 부하 절감

    public TradeFollowUpService(TradeOutcomeRepository tradeOutcomeRepository,
                                OutcomeSampleRepository outcomeSampleRepository,
                                OutcomeDailyMarkRepository dailyMarkRepository,
                                KisApiClient kisApiClient,
                                @Value("${stockadvisor.followup.first-delay:5m}") Duration firstDelay,
                                @Value("${stockadvisor.followup.second-delay:10m}") Duration secondDelay,
                                @Value("${stockadvisor.followup.third-delay:30m}") Duration thirdDelay,
                                @Value("${stockadvisor.followup.exit-marks}") int[] exitMarks,
                                @Value("${stockadvisor.followup.vwap-marks}") int[] vwapMarks,
                                @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv,
                                @Value("${stockadvisor.trading.multiday-strategies:}") String multidayCsv,
                                @Value("${stockadvisor.trading.multiday-max-hold-days:15}") int multidayMaxHoldDays) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.dailyMarkRepository = dailyMarkRepository;
        this.kisApiClient = kisApiClient;
        this.firstDelay = firstDelay;
        this.secondDelay = secondDelay;
        this.thirdDelay = thirdDelay;
        this.exitMarks = exitMarks;
        this.vwapMarks = java.util.Arrays.stream(vwapMarks).boxed().collect(Collectors.toSet());
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
        this.multidayStrategies = PolicyGate.parseCsv(multidayCsv);
        this.multidayMaxHoldDays = multidayMaxHoldDays;
        // 만료 백스톱: 목표 거래일의 2배 캘린더일(주말·연휴 여유). 실제 종료는 D+maxHoldDays 수집.
        this.multidayTrackWindow = Duration.ofDays(Math.max(1, multidayMaxHoldDays) * 2L);
    }

    private final java.util.Set<String> swingStrategies;   // 스윙 전략 — 트레일링 검증가 수집 대상
    private final java.util.Set<String> multidayStrategies; // 멀티데이(2-3주) 전략 — 일봉 종가 경로 수집 대상(Phase 1, 측정)
    private final int multidayMaxHoldDays;                  // D+N 까지 일봉 종가 수집(거래일)
    private final Duration multidayTrackWindow;             // 멀티데이 추적 만료 백스톱(캘린더일)

    /**
     * 미완료 가상매수들의 horizon 가격을 수집/갱신한다. (후속 알림은 제거 — 데이터 수집 전용)
     *
     * @return 처리한 미완료 outcome 수
     */
    public int processFollowUps() {
        List<TradeOutcome> pending = tradeOutcomeRepository.findByCompletedFalse();
        Instant now = Instant.now();
        for (TradeOutcome o : pending) {
            try {
                process(o, now);
            } catch (Exception ex) {
                log.warn("후속 추적 실패 strategy={} stockCode={}: {}",
                        o.getStrategy(), o.getStockCode(), ex.getMessage());
            }
        }
        return pending.size();
    }

    /**
     * 멀티데이 일봉 종가 경로 <b>소급(backfill)</b> — 이미 쌓인 C/D/J 비대조군 진입분에 대해
     * KIS 일봉(~30거래일 창)으로 D0..D+maxHoldDays 종가를 지금 채운다(Phase 2 시뮬을 forward 없이 즉시 가동).
     * null만 채우고 재실행 안전. 종목당 1콜(전역 rateGate 직렬화). @return 조회·처리한 outcome 수.
     */
    public int backfillMultidayMarks() {
        if (multidayStrategies.isEmpty()) return 0;
        String since = LocalDate.now(SEOUL).minusDays(45).format(YYYYMMDD);  // 45캘린더일 ≈ 30거래일 창 커버
        List<TradeOutcome> outcomes = tradeOutcomeRepository
                .findByStrategyInAndControlFalseAndAlertDateGreaterThanEqual(multidayStrategies, since);
        int touched = 0;
        for (TradeOutcome o : outcomes) {
            if (dailyMarkRepository.existsByOutcomeIdAndMarkDays(o.getId(), multidayMaxHoldDays)) continue; // 이미 완전
            try {
                List<DailyPrice> rows = kisApiClient.fetchDailyPrices(o.getStockCode()).output();
                if (rows != null && !rows.isEmpty()) { collectDailyMarks(o, rows); touched++; }
            } catch (Exception ex) {
                log.warn("멀티데이 백필 실패 outcome={} stock={}: {}", o.getId(), o.getStockCode(), ex.getMessage());
            }
        }
        log.info("멀티데이 일봉마크 백필: {}건 처리(대상 {})", touched, outcomes.size());
        return touched;
    }

    /** 보유시간 마크/EOD 시점 가격 + VWAP/거래량을 OutcomeSample 에 적재 (청산시점·신호청산 분석용). */
    private void sampleExitMarks(TradeOutcome o, long currentPrice, long elapsedMin) {
        for (int mark : exitMarks) {
            if (elapsedMin >= mark && elapsedMin <= mark + GRACE_MIN
                    && !outcomeSampleRepository.existsByOutcomeIdAndMarkMinutes(o.getId(), mark)) {
                OutcomeSample sample = new OutcomeSample(o.getId(), o.getStrategy(), o.getBuyPrice(), mark, currentPrice);
                // 가격은 모든(촘촘한) 마크에 기록(추가 호출 0). VWAP는 성긴 vwapMarks 에서만 조회(KIS 부하 절감).
                if (vwapMarks.contains(mark)) {
                    sample = withSignals(sample, o.getStockCode());
                }
                enrichContext(sample, o);   // 지수흐름·시장폭 — 캐시 재사용(KIS 0), 향후 조건부 청산 정밀 검증용
                outcomeSampleRepository.save(sample);
            }
        }
        if (o.getPriceClose() != null
                && !outcomeSampleRepository.existsByOutcomeIdAndMarkMinutes(o.getId(), EOD_MARK)) {
            outcomeSampleRepository.save(withSignals(
                    new OutcomeSample(o.getId(), o.getStrategy(), o.getBuyPrice(), EOD_MARK, o.getPriceClose()),
                    o.getStockCode()));
        }
    }

    // 보유중 관찰(2026-07-23) — 마크 시점의 지수 mom30·시장폭을 함께 저장(전부 TTL 캐시라 추가 KIS 0).
    // 7/23 what-if는 앵커 보간 근사였는데, 이 실측 저장이 쌓이면 조건부 청산/기대수익 테이블을 정밀 재검한다.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeService marketRegimeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketBreadthService marketBreadthService;
    private void enrichContext(OutcomeSample sample, TradeOutcome o) {
        try {
            String mkt = o.getEntryMarket();
            if (mkt == null || mkt.isBlank() || "INVERSE".equals(mkt)) return;
            Double mom = null, breadth = null;
            if (marketRegimeService != null) {
                var flow = marketRegimeService.intradayFlow(mkt);
                if (flow != null && flow.available()) mom = flow.mom30Pct();
            }
            if (marketBreadthService != null && marketBreadthService.isFresh(40)) {
                breadth = marketBreadthService.breadthPct(mkt);
            }
            sample.recordContext(mom, breadth);
        } catch (Exception ignore) { /* 관찰 실패는 샘플 저장을 깨지 않음 */ }
    }

    /** 샘플에 VWAP/거래량 보강 (현재가 비캐시 조회). 실패해도 가격 샘플은 유지. */
    private OutcomeSample withSignals(OutcomeSample sample, String stockCode) {
        try {
            KisApiClient.VwapVol vv = kisApiClient.fetchVwapVolume(stockCode);
            sample.recordSignals(vv.vwap(), vv.volume());
        } catch (Exception ex) {
            log.debug("VWAP/거래량 조회 실패 stockCode={}: {}", stockCode, ex.getMessage());
        }
        return sample;
    }

    /** 미완료 가상매수 1건의 horizon 가격/마크를 수집·갱신(후속 알림은 제거됨 — 데이터 수집 전용). */
    void process(TradeOutcome o, Instant now) {
        Duration elapsed = Duration.between(o.getAlertTime(), now);
        boolean changed = false;

        // 1) 장중 분(分) horizon — 현재가(일봉 rows[0]) 사용
        boolean needIntraday = o.getPrice5min() == null || o.getPrice10min() == null || o.getPrice30min() == null;
        boolean needDaily = o.getPriceClose() == null || o.getPriceNextClose() == null;

        // 멀티데이(2-3주) 일봉 종가 경로 수집 — 새 종가는 하루 1회(마감 후)만 확정되므로,
        // "장 마감 후 AND 오늘분 미수집"일 때만 일봉 조회(포지션당 1일 1콜로 제한). 대조군 제외(부하).
        java.time.LocalDateTime nowSeoul = java.time.LocalDateTime.ofInstant(now, SEOUL);
        String today = nowSeoul.toLocalDate().format(YYYYMMDD);
        boolean afterClose = nowSeoul.toLocalTime().isAfter(MARKET_CLOSE);
        boolean multiday = !o.isControl() && multidayStrategies.contains(o.getStrategy());
        boolean needMultidayDaily = multiday && afterClose
                && !dailyMarkRepository.existsByOutcomeIdAndBusinessDate(o.getId(), today);

        List<DailyPrice> rows = (needIntraday || needDaily || needMultidayDaily)
                ? kisApiClient.fetchDailyPrices(o.getStockCode()).output() : null;

        if (rows != null && !rows.isEmpty()) {
            long currentPrice = parseLong(rows.get(0).closePrice());

            // 보유 중 최고/최저가(MFE/MAE) 갱신
            if (currentPrice > 0) { o.updatePeakTrough(currentPrice); changed = true; }
            // 스윙 트레일링 검증(3/5/7% 되돌림 도달가) — 스윙 전략만, 매분 갱신(당일+익일 경로 커버).
            if (currentPrice > 0 && swingStrategies.contains(o.getStrategy())) o.updateSwingTrail(currentPrice);

            // 가격 샘플(분석용) — 알림과 분리
            if (o.getPrice5min() == null && elapsed.compareTo(firstDelay) >= 0) {
                o.setPrice5min(currentPrice); changed = true;
            }
            if (o.getPrice10min() == null && elapsed.compareTo(secondDelay) >= 0) {
                o.setPrice10min(currentPrice); changed = true;
            }
            if (o.getPrice30min() == null && elapsed.compareTo(thirdDelay) >= 0) {
                o.setPrice30min(currentPrice); changed = true;
            }
            // (후속 Discord 알림 제거됨 — 가격 샘플링/horizon 수집만 유지)

            // 2) 당일/익일/D+2/D+3 종가 — 일봉 날짜 매칭(확정된 거래일만, 휴장은 거래일 카운트라 자동 제외)
            if (o.getPriceClose() == null) {
                findClose(rows, o.getAlertDate(), 0).ifPresent(o::setPriceClose);
                if (o.getPriceClose() != null) changed = true;
            }
            if (o.getPriceNextClose() == null) {
                findClose(rows, o.getAlertDate(), 1).ifPresent(o::setPriceNextClose);
                if (o.getPriceNextClose() != null) changed = true;
            }
            if (o.getPriceD2() == null) {
                findClose(rows, o.getAlertDate(), 2).ifPresent(o::setPriceD2);
                if (o.getPriceD2() != null) changed = true;
            }
            if (o.getPriceD3() == null) {
                findClose(rows, o.getAlertDate(), 3).ifPresent(o::setPriceD3);
                if (o.getPriceD3() != null) changed = true;
            }

            // 3) 보유시간 마크 샘플링 (청산시점 분석용 곡선) — 현재가/EOD종가.
            //    대조군은 청산방식 분석 대상이 아니므로 제외(exit-timing/exit-comparison 오염 방지).
            if (!o.isControl()) {
                sampleExitMarks(o, currentPrice, elapsed.toMinutes());
            }

            // 4) 멀티데이 일봉 종가 경로(D0..D+maxHoldDays) — 마감 후 1일 1회. 미수집 거래일만 저장(재기동/휴장 복원).
            if (needMultidayDaily) {
                collectDailyMarks(o, rows);
            }
        }

        // 종료 조건: (멀티데이) D+maxHoldDays 수집 완료 / (일반) D+3 종가 수집 완료, 둘 다 만료 백스톱.
        boolean collected = multiday
                ? dailyMarkRepository.existsByOutcomeIdAndMarkDays(o.getId(), multidayMaxHoldDays)
                : o.getPriceD3() != null;
        Duration trackWindow = multiday ? multidayTrackWindow : MAX_TRACK;
        if (collected || elapsed.compareTo(trackWindow) > 0) {
            o.markCompleted(); changed = true;
        }
        if (changed) {
            tradeOutcomeRepository.save(o);
        }
    }

    /**
     * 멀티데이 일봉 종가 경로 수집 — D0..D+maxHoldDays 중 아직 저장 안 된 확정 거래일만 {@link OutcomeDailyMark}로 적재.
     * (재기동으로 하루 놓쳐도 KIS 일봉 ~30거래일 창 안이면 다음 실행에 소급 복원됨.)
     */
    private void collectDailyMarks(TradeOutcome o, List<DailyPrice> rows) {
        for (int n = 0; n <= multidayMaxHoldDays; n++) {
            if (dailyMarkRepository.existsByOutcomeIdAndMarkDays(o.getId(), n)) continue;
            Optional<DailyPrice> rowOpt = findCloseRow(rows, o.getAlertDate(), n);
            if (rowOpt.isEmpty()) continue;
            DailyPrice row = rowOpt.get();
            long close = parseLong(row.closePrice());
            if (close <= 0) continue;
            dailyMarkRepository.save(new OutcomeDailyMark(
                    o.getId(), o.getStrategy(), o.getBuyPrice(), n, row.businessDate(), close));
        }
    }

    /**
     * 일봉에서 종가를 찾는다. KIS 일봉에는 실제 거래일만 있으므로 휴장(주말·공휴일)은 카운트에서 자동 제외된다.
     *
     * @param businessDayOffset 0=당일(alertDate) 종가, N≥1=진입일 이후 N번째 거래일(D+N) 종가.
     *                          확정된(마감 후) 거래일만 반환하고, 그 외엔 empty.
     */
    private Optional<Long> findClose(List<DailyPrice> rows, String alertDate, int businessDayOffset) {
        return findCloseRow(rows, alertDate, businessDayOffset).map(r -> parseLong(r.closePrice()));
    }

    /** {@link #findClose}와 동일 규칙이되 확정된 일봉 행(거래일·종가)을 그대로 반환. */
    private Optional<DailyPrice> findCloseRow(List<DailyPrice> rows, String alertDate, int businessDayOffset) {
        DailyPrice picked;
        if (businessDayOffset <= 0) {
            picked = rows.stream()
                    .filter(r -> alertDate.equals(r.businessDate()))
                    .findFirst().orElse(null);
        } else {
            // alertDate 이후 거래일들을 오름차순 정렬해 N번째(=D+N) 선택
            List<DailyPrice> after = new ArrayList<>();
            for (DailyPrice r : rows) {
                if (r.businessDate() != null && r.businessDate().compareTo(alertDate) > 0) after.add(r);
            }
            after.sort(Comparator.comparing(DailyPrice::businessDate));
            picked = after.size() >= businessDayOffset ? after.get(businessDayOffset - 1) : null;
        }
        if (picked == null || !isFinalized(picked.businessDate())) {
            return Optional.empty();
        }
        return Optional.of(picked);
    }

    /** 해당 거래일의 종가가 확정되었는지(과거일 or 오늘 마감 후). */
    private boolean isFinalized(String businessDate) {
        LocalDate bd = LocalDate.parse(businessDate, YYYYMMDD);
        LocalDate today = LocalDate.now(SEOUL);
        if (bd.isBefore(today)) return true;
        if (bd.isEqual(today)) return LocalTime.now(SEOUL).isAfter(MARKET_CLOSE);
        return false;
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
