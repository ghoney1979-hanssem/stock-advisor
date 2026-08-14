package com.stockadvisor.service;

import com.stockadvisor.config.properties.AdaptiveExitProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략별 시간기반 청산 보유시간 제공자.
 *
 * <p>{@link ExitTimingService}가 계산한 보유시간별 평균 net 수익 곡선에서, 전략별로 <b>표본이 충분한
 * (≥ minSamples) 마크 중 평균수익 최대 마크</b>를 보유시간으로 채택한다. 자격 마크가 없으면(데이터 부족)
 * 고정값 {@code policy.timeExitHoldMinutes()}로 fallback. 종가(EOD) 권장이거나 과대 마크는 maxHoldMinutes로 캡.</p>
 *
 * <p>{@link PositionExitService}가 매분 호출하므로 {@code refreshMinutes} 주기 TTL 캐시로 재계산을 줄인다.</p>
 */
@Service
public class StrategyHoldTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(StrategyHoldTimeProvider.class);
    private static final List<String> STRATEGIES = List.of("MOMENTUM_A", "VOLUME_LEADING_B", "MEAN_REVERSION_C");

    private final ExitTimingService exitTimingService;
    private final TradingPolicyProperties policy;
    private final AdaptiveExitProperties props;

    private volatile Instant lastRefresh;
    private volatile Map<String, HoldInfo> cache = Map.of();

    // 가시화(describe)용 전략 목록 — 필드주입(생성자 무churn, 기존 단위테스트 영향 없음). 미주입이면 STRATEGIES만.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<com.stockadvisor.strategy.TradingStrategy> strategies;

    public StrategyHoldTimeProvider(ExitTimingService exitTimingService,
                                    TradingPolicyProperties policy,
                                    AdaptiveExitProperties props) {
        this.exitTimingService = exitTimingService;
        this.policy = policy;
        this.props = props;
    }

    /** @param auto true=분석 기반 자동, false=고정값 fallback. samples/avgReturnPct는 자동일 때만 의미. */
    public record HoldInfo(String strategy, int holdMinutes, boolean auto, int samples, Double avgReturnPct) {}

    /** 해당 전략의 청산 보유시간(분). 적응형 비활성/표본부족이면 고정값. */
    public int holdMinutes(String strategy) {
        if (!props.enabled()) return policy.timeExitHoldMinutes();
        refreshIfStale();
        HoldInfo info = cache.get(strategy);
        return info != null ? info.holdMinutes() : policy.timeExitHoldMinutes();
    }

    /**
     * <b>전 전략</b> 현재 적용 보유시간(가시화/관리 API용).
     *
     * <p>🐞 2026-08-14 수정: 하드코딩된 A/B/C만 반환해, 실제로는 D 90분·K 90분·F 70분·G/H 35분·E 5분으로
     * 청산·게이팅되고 있는데도 <b>조회로는 확인할 방법이 없었다</b>(게이트 사유 문자열에서 역추론해야 했음).
     * → 등록된 전략 빈 전체 ∪ 캐시 키를 노출한다. 데이터 부족 전략은 고정 fallback값이 그대로 보여 진단이 된다.</p>
     */
    public List<HoldInfo> describe() {
        if (props.enabled()) refreshIfStale();
        List<HoldInfo> out = new ArrayList<>();
        for (String s : knownStrategies()) {
            HoldInfo info = props.enabled() ? cache.get(s) : null;
            out.add(info != null ? info
                    : new HoldInfo(s, policy.timeExitHoldMinutes(), false, 0, null));
        }
        return out;
    }

    /** 가시화 대상 전략명 — 등록된 전략 빈 ∪ 분석 캐시 키(둘 중 하나에만 있어도 노출). */
    private List<String> knownStrategies() {
        java.util.SortedSet<String> names = new java.util.TreeSet<>(STRATEGIES);
        if (strategies != null) for (com.stockadvisor.strategy.TradingStrategy s : strategies) names.add(s.name());
        names.addAll(cache.keySet());
        return new ArrayList<>(names);
    }

    private synchronized void refreshIfStale() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).toMinutes() < props.refreshMinutes()) {
            return;   // 캐시 유효
        }
        refresh();
    }

    /** ExitTimingService 분석을 읽어 전략별 보유시간 캐시를 갱신. */
    public synchronized void refresh() {
        Map<String, HoldInfo> map = new LinkedHashMap<>();
        for (ExitTimingService.StrategyExitTiming t : exitTimingService.analyze()) {
            ExitTimingService.MarkStat best = t.curve().stream()
                    .filter(m -> m.samples() >= props.minSamples())
                    .max(Comparator.comparingDouble(ExitTimingService.MarkStat::avgReturnPct))
                    .orElse(null);
            if (best == null) continue;   // 자격 마크 없음 → fallback(캐시 미등록)
            // 종가(EOD, markMinutes<0)이거나 과대 마크는 상한으로 캡 → 사실상 장마감까지 보유
            int mins = best.markMinutes() < 0
                    ? props.maxHoldMinutes()
                    : Math.min(best.markMinutes(), props.maxHoldMinutes());
            map.put(t.strategy(), new HoldInfo(t.strategy(), mins, true, best.samples(), best.avgReturnPct()));
        }
        cache = map;
        lastRefresh = Instant.now();
        if (!map.isEmpty()) {
            log.info("적응형 청산 보유시간 갱신: {}", map.values().stream()
                    .map(h -> h.strategy() + "=" + h.holdMinutes() + "분(n=" + h.samples() + ")").toList());
        }
    }
}
