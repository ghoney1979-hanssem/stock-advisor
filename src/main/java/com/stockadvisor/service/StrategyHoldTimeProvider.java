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

    /**
     * 권장 마크 선택(순수) — 자격 마크(표본 ≥ minSamples) 중 <b>이웃 평활값</b>이 최대인 마크.
     *
     * <p>2026-08-18: 종전에는 마크별 원시 평균의 단순 최대를 골랐는데, 마크마다 표본이 서로 다른 부분집합
     * (n 60~230)이라 곡선이 ±0.4%p 노이즈를 갖는다. 60개 마크에서 최대를 뽑으면 그 노이즈의 상위 극단이
     * 잡힌다 — 실측: K는 95분 +0.94%인데 이웃 90분 −0.44%·100분 −0.56%, J는 245분 +0.19% / 이웃 −0.60%,
     * I는 255분 +0.45% / 250분 −0.36%. 이 마크는 곧 {@link StrategyPerformanceGate}의 채점 horizon이기도 해서
     * 게이트 net까지 같은 크기만큼 낙관 편향된다. → 이웃 창(smoothWindow) 평균으로 평활한 뒤 최대를 고른다.
     * 인접 마크가 함께 좋은 '구간'만 살아남으므로 단발 스파이크가 걸러진다.</p>
     *
     * <p>평활은 <b>선택에만</b> 쓰고 보고값(samples/avgReturnPct)은 원시 마크 그대로 노출한다 —
     * 가시화에서 "그 마크의 실제 표본·수익"이 바뀌면 진단이 어려워진다. smoothWindow ≤ 1이면 종전 max-pick.</p>
     */
    static ExitTimingService.MarkStat pickBest(List<ExitTimingService.MarkStat> curve,
                                               int minSamples, int smoothWindow) {
        List<ExitTimingService.MarkStat> qualified = curve.stream()
                .filter(m -> m.samples() >= minSamples)
                .sorted(Comparator.comparingInt(ExitTimingService.MarkStat::markMinutes))
                .toList();
        if (qualified.isEmpty()) return null;
        int half = Math.max(1, smoothWindow / 2);
        // 창을 온전히 채우는 마크만 후보로 둔다(양 끝 half개 제외) — 잘린 창으로 평균을 내면 이웃이 한쪽뿐인
        // 경계 마크가 구조적으로 유리해져(분모가 작아 스파이크가 덜 희석됨) 평활의 취지가 무너진다.
        // 자격 마크가 창보다 적으면 평활 자체가 무의미 → 종전 max-pick.
        if (smoothWindow <= 1 || qualified.size() < smoothWindow + 2) {
            return qualified.stream()
                    .max(Comparator.comparingDouble(ExitTimingService.MarkStat::avgReturnPct))
                    .orElse(null);
        }
        ExitTimingService.MarkStat best = null;
        double bestSmoothed = Double.NEGATIVE_INFINITY;
        for (int i = half; i < qualified.size() - half; i++) {
            double sum = 0;
            for (int j = i - half; j <= i + half; j++) sum += qualified.get(j).avgReturnPct();
            double smoothed = sum / (2 * half + 1);
            if (smoothed > bestSmoothed) {
                bestSmoothed = smoothed;
                best = qualified.get(i);
            }
        }
        return best;
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
            ExitTimingService.MarkStat best = pickBest(t.curve(), props.minSamples(), props.smoothWindow());
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
