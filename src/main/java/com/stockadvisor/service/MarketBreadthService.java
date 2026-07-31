package com.stockadvisor.service;

import com.stockadvisor.domain.Company;
import com.stockadvisor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 폭(breadth) 집계 — 워치리스트 스캔 중 <b>전 종목 등락률</b>로 "몇 종목이 오르고 있나"(참여 넓이)를 산출.
 *
 * <p>지수(시총가중)는 소수 대형주에 끌려가지만, breadth는 <b>동일가중 관점</b>이라 "좁은 랠리"(지수↑·다수↓)를 잡는다.
 * 우린 12분마다 1500종목을 이미 스캔하므로 거의 공짜로 얻는다.</p>
 *
 * <p>⚠️ <b>반드시 볼륨 게이트 이전(신호 산출 직후)에 record</b> — 거래량 급증 종목만 세면 편향되기 때문.
 * {@link StrategyEvaluator}가 평가하는 모든 종목을 {@link #record}로 넣고, 시장(KOSPI/KOSDAQ)은 Company.market으로 룩업.
 * {@link WatchlistScanService}가 스캔 시작에 {@link #beginScan}, 종료에 {@link #publish}로 스냅샷 확정(직전 완료 스캔값을 태깅에 사용).</p>
 */
@Service
public class MarketBreadthService {

    private static final Logger log = LoggerFactory.getLogger(MarketBreadthService.class);
    private static final String OVERALL = "OVERALL";

    private final CompanyRepository companyRepository;

    private volatile Map<String, String> codeToMarket = Map.of();
    private boolean active;
    private Thread scanThread;   // 전수 스캔 스레드 고정 — 동시에 도는 핫스캔·공시 경로의 record는 중복이라 배제
    private Map<String, List<Double>> building = new HashMap<>();
    private volatile Map<String, Breadth> published = Map.of();
    private volatile java.time.Instant publishedAt;   // 스냅샷 확정 시각 — 신선도 판정(리스크오프가 전일 스냅샷에 오발동 방지)

    public MarketBreadthService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    /** @param advPct 상승 종목 비율(%), medianChangePct 등락률 중앙값. */
    public record Breadth(String market, int total, int advancers, Double advPct, Double medianChangePct) {}

    /** 스캔 시작 — 누산기 리셋 + code→market 맵 갱신(워치리스트는 매일 바뀔 수 있어 스캔마다 재적재). */
    public synchronized void beginScan() {
        active = true;
        scanThread = Thread.currentThread();
        building = new HashMap<>();
        Map<String, String> m = new HashMap<>();
        for (Company c : companyRepository.findAll()) {
            if (c.getMarket() != null && !c.getMarket().isBlank()) m.put(c.getStockCode(), c.getMarket());
        }
        codeToMarket = m;
    }

    /** 평가한 종목의 당일 등락률 기록(전수 스캔 스레드만, 볼륨 게이트 이전). 시장은 Company.market 룩업. */
    public synchronized void record(String stockCode, double changeRate) {
        if (!active || Thread.currentThread() != scanThread) return;
        building.computeIfAbsent(OVERALL, k -> new ArrayList<>()).add(changeRate);
        String mkt = codeToMarket.get(stockCode);
        if (mkt != null) building.computeIfAbsent(mkt, k -> new ArrayList<>()).add(changeRate);
    }

    /** 스캔 종료 — 누산기로 시장별 breadth 스냅샷 확정. 기록 0건(마감 후 빈 스캔)이면 기존 스냅샷 유지. */
    public synchronized void publish() {
        active = false;
        if (building.isEmpty()) {
            log.debug("시장폭 기록 0건(세션 밖 스캔) — 기존 스냅샷 유지");
            return;
        }
        Map<String, Breadth> pub = new LinkedHashMap<>();
        building.forEach((mkt, changes) -> pub.put(mkt, compute(mkt, changes)));
        published = pub;
        publishedAt = java.time.Instant.now();
        Breadth all = pub.get(OVERALL);
        if (all != null) log.info("시장폭 갱신: 전체 상승비율 {}% (n{}), 중앙 {}%",
                all.advPct(), all.total(), all.medianChangePct());
    }

    /** 전체 워치리스트 상승 비율(%). 미집계면 null. */
    public Double overallBreadthPct() {
        Breadth b = published.get(OVERALL);
        return b == null ? null : b.advPct();
    }

    /** 해당 시장(KOSPI/KOSDAQ) 상승 비율(%). 미집계면 null. */
    public Double breadthPct(String market) {
        Breadth b = market == null ? null : published.get(market);
        return b == null ? null : b.advPct();
    }

    /** 해당 시장 등락률 중앙값(%). 미집계면 null. */
    public Double medianChangePct(String market) {
        Breadth b = market == null ? null : published.get(market);
        return b == null ? null : b.medianChangePct();
    }

    /** 스냅샷이 최근 {@code maxAgeMinutes}분 내 확정됐는가 — 마감 후/전일 스냅샷 기반 오판정 방지용. */
    public boolean isFresh(long maxAgeMinutes) {
        java.time.Instant at = publishedAt;
        return at != null && java.time.Duration.between(at, java.time.Instant.now()).toMinutes() <= maxAgeMinutes;
    }

    /** 현재 스냅샷(가시화 API용). */
    public List<Breadth> describe() {
        return new ArrayList<>(published.values());
    }

    private Breadth compute(String market, List<Double> changes) {
        if (changes == null || changes.isEmpty()) return new Breadth(market, 0, 0, null, null);
        int adv = 0;
        List<Double> sorted = new ArrayList<>(changes);
        for (double c : changes) if (c > 0) adv++;
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        return new Breadth(market, changes.size(), adv,
                round2(100.0 * adv / changes.size()), round2(median));
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
