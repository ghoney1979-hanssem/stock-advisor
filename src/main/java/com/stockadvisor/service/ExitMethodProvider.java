package com.stockadvisor.service;

import com.stockadvisor.config.properties.ExitMethodProperties;
import com.stockadvisor.domain.ExitMethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략별 적응형 청산방식 제공자.
 *
 * <p>{@link ExitStrategyService#recommend()}(과거 가격경로 시뮬레이션의 평균 net 수익 최대 방식)을 읽어,
 * 표본이 충분한 전략은 그 방식을, 부족하면 시간기반(TIME)으로 fallback한다. {@link PositionExitService}가
 * 매 청산 점검 때 소비하므로 {@code refreshMinutes} TTL 캐시.</p>
 */
@Service
public class ExitMethodProvider {

    private static final Logger log = LoggerFactory.getLogger(ExitMethodProvider.class);
    private static final List<String> STRATEGIES = List.of("MOMENTUM_A", "VOLUME_LEADING_B", "MEAN_REVERSION_C");

    private final ExitStrategyService exitStrategyService;
    private final ExitMethodProperties props;

    private volatile Instant lastRefresh;
    private volatile Map<String, ExitStrategyService.BestExit> cache = Map.of();

    // 가시화(describe)용 전략 목록 — 필드주입(생성자 무churn). 미주입(테스트)이면 STRATEGIES만.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<com.stockadvisor.strategy.TradingStrategy> strategies;

    public ExitMethodProvider(ExitStrategyService exitStrategyService, ExitMethodProperties props) {
        this.exitStrategyService = exitStrategyService;
        this.props = props;
    }

    private static final ExitStrategyService.BestExit TIME_DEFAULT =
            new ExitStrategyService.BestExit("", ExitMethodType.TIME, 0, 0, 0);

    /** 해당 전략의 청산방식. 비활성/표본부족/미산출이면 시간기반(TIME). */
    /** 추세전환 청산 확인 횟수(N회 연속 하락 요구). PositionExitService가 소비. */
    public int trendConfirm() {
        return props.trendConfirm();
    }

    public ExitStrategyService.BestExit methodFor(String strategy) {
        if (!props.enabled()) return TIME_DEFAULT;
        refreshIfStale();
        ExitStrategyService.BestExit b = cache.get(strategy);
        if (b == null || b.samples() < props.minSamples()) return TIME_DEFAULT;
        return b;
    }

    /**
     * <b>전 전략</b> 현재 채택 청산방식(가시화/관리 API용).
     * 🐞 2026-08-14 수정: A/B/C 하드코딩이라 D·K·F·G·H 등 실제 운용 중인 전략의 청산방식을 조회할 수 없었음
     * ({@link StrategyHoldTimeProvider#describe()}와 동일 결손).
     */
    public List<ExitStrategyService.BestExit> describe() {
        List<ExitStrategyService.BestExit> out = new ArrayList<>();
        for (String s : knownStrategies()) {
            ExitStrategyService.BestExit m = methodFor(s);
            out.add(new ExitStrategyService.BestExit(s, m.type(), m.param(), m.avgReturnPct(), m.samples()));
        }
        return out;
    }

    /** 가시화 대상 전략명 — 등록된 전략 빈 ∪ 분석 캐시 키. */
    private List<String> knownStrategies() {
        java.util.SortedSet<String> names = new java.util.TreeSet<>(STRATEGIES);
        if (strategies != null) for (com.stockadvisor.strategy.TradingStrategy s : strategies) names.add(s.name());
        names.addAll(cache.keySet());
        return new ArrayList<>(names);
    }

    private synchronized void refreshIfStale() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).toMinutes() < props.refreshMinutes()) {
            return;
        }
        refresh();
    }

    /** ExitStrategyService 추천을 읽어 캐시 갱신. */
    public synchronized void refresh() {
        Map<String, ExitStrategyService.BestExit> map = new LinkedHashMap<>();
        for (ExitStrategyService.BestExit b : exitStrategyService.recommend()) {
            map.put(b.strategy(), b);
        }
        cache = map;
        lastRefresh = Instant.now();
        if (!map.isEmpty()) {
            log.info("적응형 청산방식 갱신: {}", map.values().stream()
                    .map(b -> b.strategy() + "=" + b.type().korean()
                            + (b.type() == ExitMethodType.TRAILING ? "(" + b.param() + "%)" : "")
                            + "[n=" + b.samples() + "]").toList());
        }
    }
}
