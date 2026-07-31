package com.stockadvisor.service;

import com.stockadvisor.config.properties.AdaptiveStopProperties;
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
 * 전략별 적응형 catastrophic 손절선 제공자.
 *
 * <p>{@link MaeAnalysisService}의 전략별 <b>승자 MAE worst10(승자 10분위 최대 역행)</b>을 손절선으로 채택 —
 * "승자가 이만큼까지만 물린다"는 지점 바로 아래에 손절을 둬 승자를 덜 죽이면서 패자를 자른다.
 * <b>fail-closed</b>: 승자 표본 &lt; {@code minSamples}면 채택 안 하고 고정 손절({@code catastrophic-stop-pct}) 유지.
 * 채택값은 [{@code minStopPct}, {@code maxStopPct}] 클램프. {@code refreshMinutes} TTL 캐시.</p>
 *
 * <p>{@code catastrophic-stop-pct ≤ 0}(손절 마스터 비활성)이면 항상 0(손절 안 함). 적응형 비활성이면 고정값.</p>
 */
@Service
public class StrategyStopProvider {

    private static final Logger log = LoggerFactory.getLogger(StrategyStopProvider.class);

    private final MaeAnalysisService maeAnalysisService;
    private final AdaptiveStopProperties props;
    private final double defaultStopPct;   // 고정 catastrophic 손절(%) — fallback

    private volatile Instant lastRefresh;
    private volatile Map<String, StopInfo> cache = Map.of();

    public StrategyStopProvider(MaeAnalysisService maeAnalysisService,
                                AdaptiveStopProperties props,
                                @Value("${stockadvisor.trading.risk.catastrophic-stop-pct:7.0}") double defaultStopPct) {
        this.maeAnalysisService = maeAnalysisService;
        this.props = props;
        this.defaultStopPct = defaultStopPct;
    }

    /** @param auto true=승자 MAE 기반 채택, false=고정값. winners/basisMaePct는 auto일 때 의미. */
    public record StopInfo(String strategy, double stopPct, boolean auto, int winners, Double basisMaePct) {}

    /** 해당 전략의 손절선(%). 마스터 비활성이면 0, 적응형 비활성/표본부족이면 고정값. */
    public double stopPct(String strategy) {
        if (defaultStopPct <= 0) return 0;              // 손절 마스터 비활성
        if (!props.enabled()) return defaultStopPct;
        refreshIfStale();
        StopInfo i = cache.get(strategy);
        return i != null && i.auto() ? i.stopPct() : defaultStopPct;
    }

    /** 전략별 현재 적용 손절선(가시화/관리 API용). */
    public List<StopInfo> describe() {
        if (defaultStopPct > 0 && props.enabled()) refreshIfStale();
        return new ArrayList<>(cache.values());
    }

    private synchronized void refreshIfStale() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).toMinutes() < props.refreshMinutes()) {
            return;
        }
        refresh();
    }

    /** MAE 분석을 읽어 전략별 손절선 캐시 갱신. 표본 충분(≥minSamples)한 전략만 자동 채택. */
    public synchronized void refresh() {
        Map<String, StopInfo> map = new LinkedHashMap<>();
        for (MaeAnalysisService.StrategyHeat h : maeAnalysisService.analyze()) {
            MaeAnalysisService.HeatGroup w = h.winners();
            if (w == null || w.n() < props.minSamples() || w.worst10MaePct() == null) {
                map.put(h.strategy(), new StopInfo(h.strategy(), defaultStopPct, false,
                        w == null ? 0 : w.n(), w == null ? null : w.worst10MaePct()));
                continue;
            }
            double clamped = Math.max(props.minStopPct(), Math.min(props.maxStopPct(), Math.abs(w.worst10MaePct())));
            map.put(h.strategy(), new StopInfo(h.strategy(), round1(clamped), true, w.n(), w.worst10MaePct()));
        }
        cache = map;
        lastRefresh = Instant.now();
        List<String> auto = map.values().stream().filter(StopInfo::auto)
                .map(i -> i.strategy() + "=-" + i.stopPct() + "%(n" + i.winners() + ")").toList();
        if (!auto.isEmpty()) log.info("적응형 손절선 갱신: {}", auto);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
