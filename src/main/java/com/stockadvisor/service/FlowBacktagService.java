package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 과거 진입분의 장중흐름(entry_index_mom30/60) 소급 태깅.
 *
 * <p>KIS는 과거 지수 분봉을 안 줘서 분(minute) 정밀 복원은 불가. 대신 <b>진입마다 저장된
 * {@code entry_market_change}(진입 순간 지수 당일등락%, 60초 캐시라 실시간에 근접)</b>를 이용한다. 하루 동안 여러 진입이
 * 서로 다른 시각에 각자의 지수값을 찍어놔 <b>그날 지수 장중경로를 ~10–30분 간격으로 성기게 샘플링</b>한 셈이므로,
 * 시각 차 나는 두 값을 빼면 그 구간 지수 이동% = 트레일링 모멘텀을 근사할 수 있다.</p>
 *
 * <ul>
 *   <li>(시장, 진입일)별로 모든 행의 {@code (alertTime, entryMarketChange)}를 앵커 경로로 구성 →
 *       각 진입의 T−30/T−60분 지수값을 <b>보간</b>해 {@code mom30 = C(T) − C(T−30)} 형태로 채움.</li>
 *   <li><b>mom10은 채우지 않음</b>(앵커 간격이 ~10–30분이라 10분 해상도 복원 불가). → 라이브 태그(mom10 채워짐)와 자연 구분.</li>
 *   <li>앵커가 T−30/T−60 근처(±{@value #TOLERANCE_MIN}분)에 없거나 간극이 과대하면 skip(null 유지). 이른 아침·희소일 일부는 못 채움.</li>
 *   <li>null만 채움(라이브·기존 backfill 불변). 재실행 안전.</li>
 * </ul>
 *
 * <p>⚠️ 근사값(보간·60초 캐시·진입시각 클러스터링 기반) — 확정 minute-bar 아님. 라이브 태그가 확정.</p>
 */
@Service
public class FlowBacktagService {

    private static final Logger log = LoggerFactory.getLogger(FlowBacktagService.class);
    private static final long TOLERANCE_MIN = 20;              // 앵커 허용 오차 ±분
    private static final double TOL_SEC = TOLERANCE_MIN * 60;
    private static final double LAG30_SEC = 30 * 60;
    private static final double LAG60_SEC = 60 * 60;

    private final TradeOutcomeRepository tradeOutcomeRepository;

    public FlowBacktagService(TradeOutcomeRepository tradeOutcomeRepository) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
    }

    public record BacktagResult(int scanned, int filled30, int filled60, int anchorSeries, List<String> details) {}

    @Transactional
    public BacktagResult backfill() {
        List<TradeOutcome> all = tradeOutcomeRepository.findAll();

        // 앵커 경로: (시장|진입일) -> 시각순 [epochSec, 지수등락%]. 진입·대조군 무관하게 값 있는 행 전부 사용(경로 조밀↑).
        Map<String, List<double[]>> anchors = new HashMap<>();
        for (TradeOutcome o : all) {
            if (o.getEntryMarketChange() == null || o.getEntryMarket() == null || o.getAlertTime() == null) continue;
            anchors.computeIfAbsent(o.getEntryMarket() + "|" + o.getAlertDate(), k -> new ArrayList<>())
                    .add(new double[]{o.getAlertTime().getEpochSecond(), o.getEntryMarketChange()});
        }
        anchors.values().forEach(l -> l.sort(Comparator.comparingDouble(a -> a[0])));

        int scanned = 0, filled30 = 0, filled60 = 0;
        Map<String, int[]> perDay = new TreeMap<>();
        for (TradeOutcome o : all) {
            if (o.isControl()) continue;                                     // 분석은 진입분만
            if (o.getEntryMarketChange() == null || o.getEntryMarket() == null || o.getAlertTime() == null) continue;
            if (o.getEntryIndexMom30() != null && o.getEntryIndexMom60() != null) continue;   // 이미 있음(라이브/기존)
            List<double[]> series = anchors.get(o.getEntryMarket() + "|" + o.getAlertDate());
            if (series == null) continue;

            scanned++;
            double t = o.getAlertTime().getEpochSecond();
            double c = o.getEntryMarketChange();
            Double m30 = null, m60 = null;
            if (o.getEntryIndexMom30() == null) {
                Double a = at(series, t - LAG30_SEC, TOL_SEC);
                if (a != null) { m30 = round2(c - a); filled30++; }
            }
            if (o.getEntryIndexMom60() == null) {
                Double a = at(series, t - LAG60_SEC, TOL_SEC);
                if (a != null) { m60 = round2(c - a); filled60++; }
            }
            if (m30 != null || m60 != null) {
                o.backfillIntradayFlow(m30, m60);
                tradeOutcomeRepository.save(o);
                int[] cnt = perDay.computeIfAbsent(o.getEntryMarket() + " " + o.getAlertDate(), k -> new int[2]);
                if (m30 != null) cnt[0]++;
                if (m60 != null) cnt[1]++;
            }
        }

        List<String> details = new ArrayList<>();
        perDay.forEach((k, v) -> details.add(k + " → mom30 " + v[0] + " · mom60 " + v[1]));
        log.info("장중흐름 소급 태깅 완료: 대상 {} / mom30 {}건 · mom60 {}건 (앵커일 {})",
                scanned, filled30, filled60, anchors.size());
        return new BacktagResult(scanned, filled30, filled60, anchors.size(), details);
    }

    /**
     * 정렬된 앵커 경로에서 시각 t의 지수등락% 보간. 브래킷 앵커가 있으면 선형보간(간극 &gt; 2×tol이면 신뢰 불가 null),
     * 한쪽만 있으면 ±tol 내일 때만 그 값. 범위/오차 밖이면 null.
     */
    static Double at(List<double[]> anchors, double t, double tol) {
        double[] lo = null, hi = null;
        for (double[] a : anchors) {
            if (a[0] <= t) lo = a;
            if (a[0] >= t) { hi = a; break; }
        }
        if (lo != null && hi != null) {
            if (lo[0] == hi[0]) return lo[1];
            if (hi[0] - lo[0] > 2 * tol) return null;                 // 앵커 간극 과대 → 근사 신뢰 불가
            double f = (t - lo[0]) / (hi[0] - lo[0]);
            return lo[1] + f * (hi[1] - lo[1]);
        }
        double[] near = lo != null ? lo : hi;
        if (near != null && Math.abs(near[0] - t) <= tol) return near[1];
        return null;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
