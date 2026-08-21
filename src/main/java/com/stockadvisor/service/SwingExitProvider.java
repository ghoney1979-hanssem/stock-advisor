package com.stockadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스윙 청산 방식 선택자 — <b>익일종가 보유 vs 트레일링(3/5/7%) 중 검증된 최선</b>을 전략별로 채택.
 *
 * <p><b>fail-closed</b>: {@link SwingTrailAnalysisService}에서 트레일링이 익일보유보다 {@code marginPct}%p 넘게 우수하고
 * 트리거 표본이 {@code minTriggered} 이상일 때만 그 트레일%를 채택. 아니면 <b>0(익일종가 보유)</b> — 즉 검증 전엔
 * 실청산이 바뀌지 않는다. ([[adaptive-stop]]·[[perf-gate]]와 동일한 "검증 후 적용" 원칙.)</p>
 */
@Service
public class SwingExitProvider {

    private static final Logger log = LoggerFactory.getLogger(SwingExitProvider.class);

    private final SwingTrailAnalysisService analysis;
    private final boolean enabled;
    private final int minTriggered;
    private final double marginPct;
    private final long refreshMinutes;

    private volatile Instant lastRefresh;
    private volatile Map<String, ExitChoice> cache = Map.of();

    public SwingExitProvider(SwingTrailAnalysisService analysis,
                             @Value("${stockadvisor.trading.swing-trail.enabled:true}") boolean enabled,
                             @Value("${stockadvisor.trading.swing-trail.min-triggered:20}") int minTriggered,
                             @Value("${stockadvisor.trading.swing-trail.margin-pct:0.2}") double marginPct,
                             @Value("${stockadvisor.trading.swing-trail.refresh-minutes:30}") long refreshMinutes) {
        this.analysis = analysis;
        this.enabled = enabled;
        this.minTriggered = minTriggered;
        this.marginPct = marginPct;
        this.refreshMinutes = refreshMinutes;
    }

    /** @param auto true=검증돼 트레일 채택, false=익일보유(기본/fail-closed). */
    public record ExitChoice(String strategy, int trailPct, boolean auto, Double holdNet, Double bestNet, int triggered) {}

    /** 해당 스윙 전략의 트레일 되돌림%(0=익일종가 보유). 검증 전엔 항상 0. */
    public double trailPct(String strategy) {
        if (!enabled) return 0;
        refreshIfStale();
        ExitChoice c = cache.get(strategy);
        return c != null && c.auto() ? c.trailPct() : 0;
    }

    public List<ExitChoice> describe() {
        if (enabled) refreshIfStale();
        return new ArrayList<>(cache.values());
    }

    private synchronized void refreshIfStale() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).toMinutes() < refreshMinutes) return;
        refresh();
    }

    public synchronized void refresh() {
        Map<String, ExitChoice> map = new LinkedHashMap<>();
        for (SwingTrailAnalysisService.StrategySwingTrail sa : analysis.analyze()) {
            SwingTrailAnalysisService.MethodStat hold = sa.methods().stream()
                    .filter(m -> m.trailPct() == 0).findFirst().orElse(null);
            if (hold == null || hold.avgNetPct() == null) {
                map.put(sa.strategy(), new ExitChoice(sa.strategy(), 0, false, null, null, 0));
                continue;
            }
            SwingTrailAnalysisService.MethodStat best = sa.methods().stream()
                    // ⚠️ 단일일 의존(최대기여일 제외 시 부호 반전) 우위는 채택하지 않는다(2026-08-21) —
                    // C의 익일보유 net +4.71%가 06-26 하루에서 나온 값이었고, 트레일 비교도 같은 표본 위에 있었다.
                    // 이 조건은 기존 필터에 AND로만 얹히므로 게이트는 닫히는 방향으로만 움직인다(fail-closed).
                    .filter(m -> m.trailPct() > 0 && m.triggered() >= minTriggered && m.avgNetPct() != null
                            && !m.clustered()
                            && m.avgNetPct() > hold.avgNetPct() + marginPct)
                    .max((x, y) -> Double.compare(x.avgNetPct(), y.avgNetPct()))
                    .orElse(null);
            if (best != null) {
                map.put(sa.strategy(), new ExitChoice(sa.strategy(), best.trailPct(), true,
                        hold.avgNetPct(), best.avgNetPct(), best.triggered()));
            } else {
                map.put(sa.strategy(), new ExitChoice(sa.strategy(), 0, false, hold.avgNetPct(), hold.avgNetPct(), 0));
            }
        }
        cache = map;
        lastRefresh = Instant.now();
        List<String> auto = map.values().stream().filter(ExitChoice::auto)
                .map(c -> c.strategy() + "=트레일" + c.trailPct() + "%(net " + c.bestNet() + " vs 보유 " + c.holdNet() + ")").toList();
        if (!auto.isEmpty()) log.info("스윙 청산방식 채택(검증됨): {}", auto);
    }
}
