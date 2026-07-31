package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse.DailyPrice;
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
    private final KisApiClient kisApiClient;
    private final Duration firstDelay;
    private final Duration secondDelay;
    private final Duration thirdDelay;
    private final int[] exitMarks;                       // 가격 샘플 마크(촘촘, 5분 단위) — 청산시점 최적화용
    private final java.util.Set<Integer> vwapMarks;      // 이 마크에서만 VWAP 조회(성김) — VWAP는 청산방식 시뮬용이라 정밀도 불요, KIS 부하 절감

    public TradeFollowUpService(TradeOutcomeRepository tradeOutcomeRepository,
                                OutcomeSampleRepository outcomeSampleRepository,
                                KisApiClient kisApiClient,
                                @Value("${stockadvisor.followup.first-delay:5m}") Duration firstDelay,
                                @Value("${stockadvisor.followup.second-delay:10m}") Duration secondDelay,
                                @Value("${stockadvisor.followup.third-delay:30m}") Duration thirdDelay,
                                @Value("${stockadvisor.followup.exit-marks}") int[] exitMarks,
                                @Value("${stockadvisor.followup.vwap-marks}") int[] vwapMarks,
                                @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.kisApiClient = kisApiClient;
        this.firstDelay = firstDelay;
        this.secondDelay = secondDelay;
        this.thirdDelay = thirdDelay;
        this.exitMarks = exitMarks;
        this.vwapMarks = java.util.Arrays.stream(vwapMarks).boxed().collect(Collectors.toSet());
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    private final java.util.Set<String> swingStrategies;   // 스윙 전략 — 트레일링 검증가 수집 대상

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
    private void process(TradeOutcome o, Instant now) {
        Duration elapsed = Duration.between(o.getAlertTime(), now);
        boolean changed = false;

        // 1) 장중 분(分) horizon — 현재가(일봉 rows[0]) 사용
        boolean needIntraday = o.getPrice5min() == null || o.getPrice10min() == null || o.getPrice30min() == null;
        boolean needDaily = o.getPriceClose() == null || o.getPriceNextClose() == null;

        List<DailyPrice> rows = (needIntraday || needDaily)
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
        }

        // 종료 조건: D+3 종가 수집 완료 or 만료
        if (o.getPriceD3() != null || elapsed.compareTo(MAX_TRACK) > 0) {
            o.markCompleted(); changed = true;
        }
        if (changed) {
            tradeOutcomeRepository.save(o);
        }
    }

    /**
     * 일봉에서 종가를 찾는다. KIS 일봉에는 실제 거래일만 있으므로 휴장(주말·공휴일)은 카운트에서 자동 제외된다.
     *
     * @param businessDayOffset 0=당일(alertDate) 종가, N≥1=진입일 이후 N번째 거래일(D+N) 종가.
     *                          확정된(마감 후) 거래일만 반환하고, 그 외엔 empty.
     */
    private Optional<Long> findClose(List<DailyPrice> rows, String alertDate, int businessDayOffset) {
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
        return Optional.of(parseLong(picked.closePrice()));
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
