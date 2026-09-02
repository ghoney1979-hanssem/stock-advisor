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

    // ⚠️ 생성자는 하나만 둔다 — 두 개면 Spring이 어느 쪽으로 주입할지 몰라 기동에 실패한다(2026-08-29 실측 함정).
    public StrategyStopProvider(MaeAnalysisService maeAnalysisService,
                                AdaptiveStopProperties props,
                                @Value("${stockadvisor.trading.risk.catastrophic-stop-pct:7.0}") double defaultStopPct,
                                // 전략별 손절선 하한(csv "STRATEGY:%") — 미지정 전략은 전역 min-stop-pct = 종전 동작
                                @Value("${stockadvisor.trading.adaptive-stop.min-stop-pct-per-strategy:}")
                                String minStopPerStrategyCsv) {
        this.maeAnalysisService = maeAnalysisService;
        this.props = props;
        this.defaultStopPct = defaultStopPct;
        this.minStopPerStrategy = parseStopFloors(minStopPerStrategyCsv);
    }

    /**
     * 전략별 손절선 <b>하한</b>(2026-09-02) — 전역 {@code min-stop-pct} 하나가 전 전략에 걸리는 문제 해소.
     *
     * <p>계기는 L 실측이다. 적응형 손절은 승자 MAE p10을 채택하는데 L은 p10이 −2.53%라 <b>전역 하한 3.0%로
     * 클램프</b>돼 −3.0%가 됐고, 그 결과 <b>히트율 23%(54/238)</b>로 지나치게 자주 물렸다. 손절선 격자 시뮬
     * (240분 마크, 8/25 제외, gross)에서 <b>어느 수준이든 손절이 발사되면 L은 손해</b>다 —
     * 무손절 +0.159 vs −3.0% <b>−0.609</b> / −5.0% −0.631 / −7.0% −0.134. 즉 현행 −3.0%가 건당 약 0.77%p를 태운다.
     * 가장 선명한 사례는 8/24로, 89건 중 <b>43건이 −3%를 찍었는데 그날 종가 평균은 +0.25%</b>였다.</p>
     *
     * <p>⚠️ 전역 하한을 올리는 것으로는 못 푼다 — min을 5로 올리면 A(4.9)도 함께 움직이고, 7로 올리면
     * F(5.8)·K(5.9)·H(6.2)·C(6.4)까지 전부 끌려온다. 전략별 하한이라야 <b>L만</b> 바꾼다.</p>
     *
     * <p>⚠️ 3~6% 구간의 순서는 노이즈다(히트 13~19건). 이 시뮬이 말하는 건 "−3.0이 최적이 아니다"이지
     * "−7.0이 최적이다"가 아니며, 손절의 존재 이유는 평균 net이 아니라 <b>꼬리 사고</b> 방어다
     * (표본 27거래일에 거래정지·연속 하한가가 없었다면 시뮬은 그 가치를 0으로 친다). 그래서 폐지가 아니라
     * <b>넓히기</b>다.</p>
     *
     * <p>⚠️ MAE는 <b>순서 미상</b>이라 저점이 먼저 왔다고 가정한 근사다 — 실효는 시뮬보다 작을 수 있다.</p>
     */
    private final Map<String, Double> minStopPerStrategy;

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
            // 하한은 전략별 지정이 있으면 그 값(전역 min-stop-pct 대체). 상한을 넘지 않게 잘라 "손절선 ≤ max-stop-pct" 불변식 유지.
            double floor = Math.min(props.maxStopPct(),
                    minStopPerStrategy.getOrDefault(h.strategy(), props.minStopPct()));
            double clamped = Math.max(floor, Math.min(props.maxStopPct(), Math.abs(w.worst10MaePct())));
            map.put(h.strategy(), new StopInfo(h.strategy(), round1(clamped), true, w.n(), w.worst10MaePct()));
        }
        cache = map;
        lastRefresh = Instant.now();
        List<String> auto = map.values().stream().filter(StopInfo::auto)
                .map(i -> i.strategy() + "=-" + i.stopPct() + "%(n" + i.winners() + ")").toList();
        if (!auto.isEmpty()) log.info("적응형 손절선 갱신: {}", auto);
    }

    /**
     * "REVERSAL_L:7.0,MOMENTUM_A:5" → {REVERSAL_L:7.0, MOMENTUM_A:5.0}.
     *
     * <p>오타·0·음수는 조용히 무시하고 전역 하한으로 degrade — 설정 실수로 손절선이 0이 되면
     * <b>손절이 사라지는 것</b>이라 fail-safe는 "무시하고 전역값"이다(보유시간 캡 파싱과 같은 사상).</p>
     */
    static Map<String, Double> parseStopFloors(String csv) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (csv == null) return m;
        for (String part : csv.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim();
            if (k.isEmpty()) continue;
            try {
                double v = Double.parseDouble(kv[1].trim());
                if (v > 0) m.put(k, v);
            } catch (NumberFormatException ignored) {
                // degrade — 전역 min-stop-pct로 되돌아간다
            }
        }
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
