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

    /** A/B/C 현재 채택 청산방식(가시화/관리 API용). */
    public List<ExitStrategyService.BestExit> describe() {
        List<ExitStrategyService.BestExit> out = new ArrayList<>();
        for (String s : STRATEGIES) {
            ExitStrategyService.BestExit m = methodFor(s);
            out.add(new ExitStrategyService.BestExit(s, m.type(), m.param(), m.avgReturnPct(), m.samples()));
        }
        return out;
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
