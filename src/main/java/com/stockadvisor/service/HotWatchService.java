package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 티어드 스캔의 "핫셋(hot set)" — 곧 진입조건에 근접한 소수 종목을 산출.
 *
 * <p>전수 스캔(12분)이 종목을 평가하며 {@link #record}로 거래량배수(volumeRatio)를 넣으면, 스캔 종료 시 {@link #publish}가
 * <b>volumeRatio 상위 N종목 + 인버스코드(항상)</b>를 핫셋으로 확정한다. 핫 스캔(2분)이 이 집합만 자주 평가 → 급변 포착 지연↓.</p>
 *
 * <p>대부분 전략 게이트가 {@code volumeSpike(≥2×)}라 <b>1.5~2.0× 구간 = "곧 급증할" 프라임 후보</b>. 추가 KIS 콜 0(이미 계산한 값 재활용).
 * breadth와 동일하게 전수 스캔만 begin/publish로 브래킷(핫 스캔은 record 안 함).</p>
 */
@Service
public class HotWatchService {

    private static final Logger log = LoggerFactory.getLogger(HotWatchService.class);

    private final int hotSetSize;
    private final double minVolumeRatio;
    private final Set<String> inverseCodes;

    private final int eventMaxSize;

    private boolean active;
    private Map<String, Double> building = new HashMap<>();
    private Map<String, Double> eventVol = new HashMap<>();   // 이벤트 트리거 종목 → volumeRatio(랭킹·캡용)
    private volatile Set<String> published = Set.of();

    public HotWatchService(@Value("${stockadvisor.tiered-scan.hot-set-size:50}") int hotSetSize,
                           @Value("${stockadvisor.tiered-scan.event-max-size:50}") int eventMaxSize,
                           @Value("${stockadvisor.tiered-scan.hot-min-volume-ratio:1.3}") double minVolumeRatio,
                           @Value("${stockadvisor.inverse-codes:114800,251340}") String inverseCsv) {
        this.hotSetSize = hotSetSize;
        this.eventMaxSize = eventMaxSize;
        this.minVolumeRatio = minVolumeRatio;
        this.inverseCodes = PolicyGate.parseCsv(inverseCsv);
    }

    /** 전수 스캔 시작 — 누산기 리셋. */
    public synchronized void beginScan() {
        active = true;
        building = new HashMap<>();
        eventVol = new HashMap<>();
    }

    /**
     * 평가한 종목 기록(전수 스캔 중). volumeRatio 상위 후보 + 볼륨무관 이벤트 트리거 종목 수집.
     * @param eventTriggered 볼륨무관 트리거(MA돌파/RSI반등/수축돌파) 발생 여부 — true면 이벤트 후보(volumeRatio로 랭킹·캡).
     */
    public synchronized void record(String stockCode, double volumeRatio, boolean eventTriggered) {
        if (!active) return;
        if (volumeRatio >= minVolumeRatio) building.merge(stockCode, volumeRatio, Math::max);
        if (eventTriggered) eventVol.merge(stockCode, volumeRatio, Math::max);
    }

    /** 전수 스캔 종료 — volumeRatio 상위 N + 이벤트 상위 M(캡) + 인버스로 핫셋 확정(반등장 이벤트 폭증 방지). */
    public synchronized void publish() {
        LinkedHashSet<String> hot = topByVolume(building, hotSetSize);   // 볼륨 상위 N
        hot.addAll(topByVolume(eventVol, eventMaxSize));                 // 이벤트 상위 M(volumeRatio 순, 캡)
        hot.addAll(inverseCodes);                                        // 인버스 항상
        published = hot;
        active = false;
        log.info("핫셋 갱신: {}종목(볼륨상위{} + 이벤트{}(총{}중 캡) + 인버스)",
                hot.size(), hotSetSize, Math.min(eventVol.size(), eventMaxSize), eventVol.size());
    }

    /** volumeRatio 내림차순 상위 limit 코드. */
    private LinkedHashSet<String> topByVolume(Map<String, Double> m, int limit) {
        return m.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 현재 핫셋(핫 스캔 대상). */
    public List<String> hotCodes() {
        return new ArrayList<>(published);
    }
}
