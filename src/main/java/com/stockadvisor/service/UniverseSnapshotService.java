package com.stockadvisor.service;

import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.UniverseSnapshot;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.UniverseSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전 종목 유니버스 스냅샷 수집 (Phase 1, 2026-08-14).
 *
 * <p>{@link UniverseSnapshot} 참조 — 목적은 "P(승자|feature)를 계산할 <b>분모</b>를 만드는 것".
 * 기존 표본이 전부 거래량 급증 모집단 안에서만 뽑히는 구조적 편향을 벗어난다.</p>
 *
 * <p><b>수집</b>: {@link StrategyEvaluator}가 볼륨 게이트 <b>이전</b>(breadth 훅과 같은 자리)에서
 * 전 종목 {@link #record}를 호출한다. 설정된 버킷 시각(예 09:30) 이후 {@code window}분 내이고
 * 그 버킷이 오늘 아직 확정되지 않았다면 버퍼에 쌓고, 스캔 종료 시 {@link #flush}가 일괄 저장한다.
 * ⚠️ 전수 스캔 브래킷 안에서만 동작(핫스캔·공시 경로는 begin이 없어 no-op) — breadth와 동일 사상.</p>
 *
 * <p><b>타깃 채우기</b>: 같은 {@link #record} 훅이 <b>이전 스냅샷의 사후 수익도 채운다</b> —
 * 이후 스캔이 그 종목을 다시 만날 때 현재가로 기록하므로 <b>추가 KIS 호출 0</b>.
 * ⚠️ 스캔 주기(12분)만큼 지연되므로 +90분 타깃의 실제 경과는 90~102분(항상 목표 이상인 일관된 근사).</p>
 */
@Service
public class UniverseSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(UniverseSnapshotService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 당일 종가 근사로 인정할 시각(이 이후 관측가를 priceClose로). */
    private static final LocalTime CLOSE_MARK = LocalTime.of(15, 15);

    private final UniverseSnapshotRepository repository;
    private final CompanyRepository companyRepository;
    private final boolean enabled;
    private final List<LocalTime> buckets;
    private final int windowMinutes;
    private final int targetMinutes;

    private boolean active;
    private Thread scanThread;                    // 전수 스캔 스레드 고정(핫스캔 중복 배제) — breadth와 동일
    private String activeBucket;                  // 이번 스캔에서 수집 중인 버킷 라벨(null=수집 안 함)
    private List<UniverseSnapshot> buffer = new ArrayList<>();
    private volatile Map<String, String> codeToMarket = Map.of();
    /** 미완 타깃이 있는 종목 — 스캔 시작 시 1회 조회. 전 종목마다 쿼리하는 것을 막는 값싼 사전필터. */
    private volatile java.util.Set<String> pendingCodes = java.util.Set.of();

    // 시장 컨텍스트 소스 — 전부 캐시 재사용(추가 KIS 0). 필드주입(생성자 무churn, 미주입이면 컨텍스트 null).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeService regimeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketBreadthService breadthService;

    public UniverseSnapshotService(UniverseSnapshotRepository repository,
                                   CompanyRepository companyRepository,
                                   @Value("${stockadvisor.signal.universe-snapshot-enabled:true}") boolean enabled,
                                   @Value("${stockadvisor.signal.universe-snapshot-times:09:30,11:00,13:00,14:30}") String times,
                                   @Value("${stockadvisor.signal.universe-snapshot-window-minutes:20}") int windowMinutes,
                                   @Value("${stockadvisor.signal.universe-snapshot-target-minutes:90}") int targetMinutes) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.enabled = enabled;
        this.buckets = parseTimes(times);
        this.windowMinutes = windowMinutes;
        this.targetMinutes = targetMinutes;
    }

    static List<LocalTime> parseTimes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<LocalTime> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(LocalTime.parse(t));
        }
        out.sort(LocalTime::compareTo);
        return List.copyOf(out);
    }

    /** 전수 스캔 시작 — 이번 스캔이 수집할 버킷 결정 + code→market 맵 갱신. */
    public synchronized void beginScan() {
        if (!enabled) return;
        active = true;
        scanThread = Thread.currentThread();
        buffer = new ArrayList<>();
        String today = LocalDate.now(SEOUL).format(YYYYMMDD);
        String bucket = bucketFor(LocalTime.now(SEOUL), buckets, windowMinutes);
        // 이미 오늘 그 버킷을 확정했으면 재수집 안 함(스캔이 12분 주기라 같은 창에 두 번 들어올 수 있음)
        activeBucket = (bucket != null && !repository.existsBySnapDateAndSnapTime(today, bucket)) ? bucket : null;
        // 사후 타깃이 남은 종목 집합 — 이번 스캔 동안 이 종목들만 채우기 시도(전 종목 쿼리 방지)
        try {
            pendingCodes = new java.util.HashSet<>(
                    repository.findPendingCodes(LocalDate.now(SEOUL).minusDays(5).format(YYYYMMDD)));
        } catch (Exception ex) {
            pendingCodes = java.util.Set.of();
            log.debug("유니버스 미완 타깃 조회 실패: {}", ex.getMessage());
        }
        if (activeBucket != null) {
            Map<String, String> m = new HashMap<>();
            for (Company c : companyRepository.findAll()) m.put(c.getStockCode(), c.getMarket());
            codeToMarket = m;
            log.info("유니버스 스냅샷 수집 시작: {} {}", today, activeBucket);
        }
    }

    /**
     * 종목 관측 — ① 활성 버킷이면 스냅샷 행 버퍼링 ② 이전 스냅샷의 사후 타깃 채우기.
     * 볼륨 게이트 <b>이전</b>에 전 종목에 대해 호출돼야 한다(급증분만 담으면 분모 의미가 사라짐).
     *
     * <p>시장·지수 컨텍스트는 여기서 <b>내부적으로</b> 해석한다 — 시장은 {@code beginScan}에 적재한
     * code→market 맵(DB 재조회 없음), 지수/시장폭은 캐시된 서비스값(추가 KIS 0).</p>
     */
    public void record(String stockCode, SignalResult signal) {
        if (!enabled) return;
        capture(stockCode, signal);
        fillTargets(stockCode, signal.closePrice());
    }

    private synchronized void capture(String stockCode, SignalResult signal) {
        if (!active || activeBucket == null || Thread.currentThread() != scanThread) return;
        String market = codeToMarket.get(stockCode);
        UniverseSnapshot s = new UniverseSnapshot(LocalDate.now(SEOUL).format(YYYYMMDD), activeBucket,
                stockCode, market, signal.closePrice());
        s.setFeatures(signal.changeRate(), signal.gapPct(), signal.volumeRatio(), signal.volumeSpike(),
                signal.atrPct(), signal.distFromHighPct(), signal.ret5dPct(), signal.maDistPct(),
                signal.maCrossUp(), signal.rsiCrossUp(), signal.squeezeBreakout());
        s.setMarketContext(indexChangeOf(market), indexMom30Of(market), indexGapOf(market),
                breadthService == null ? null : breadthService.overallBreadthPct(),
                (breadthService == null || market == null) ? null : breadthService.breadthPct(market));
        buffer.add(s);
    }

    // ── 시장 컨텍스트(전부 캐시 재사용 — 추가 KIS 0, 실패는 null로 degrade) ──────────
    private Double indexChangeOf(String market) {
        if (regimeService == null || market == null) return null;
        try { return regimeService.dayChangeOf(market); } catch (Exception ex) { return null; }
    }

    private Double indexMom30Of(String market) {
        if (regimeService == null || market == null) return null;
        try {
            MarketRegimeService.IntradayFlow f = regimeService.intradayFlow(market);
            return (f != null && f.available()) ? f.mom30Pct() : null;
        } catch (Exception ex) { return null; }
    }

    private Double indexGapOf(String market) {
        if (regimeService == null || market == null) return null;
        try { return regimeService.indexGapPct(market); } catch (Exception ex) { return null; }
    }

    /** 스캔 종료 — 버퍼 일괄 저장. 예외는 격리(스캔/매매를 절대 안 깨뜨림). */
    public synchronized void flush() {
        if (!enabled || !active) return;
        active = false;
        if (buffer.isEmpty()) { activeBucket = null; return; }
        try {
            repository.saveAll(buffer);
            log.info("유니버스 스냅샷 저장: {} {}건", activeBucket, buffer.size());
        } catch (Exception ex) {
            log.warn("유니버스 스냅샷 저장 실패({}): {}", activeBucket, ex.getMessage());
        } finally {
            buffer = new ArrayList<>();
            activeBucket = null;
        }
    }

    /**
     * 이전 스냅샷의 사후 타깃을 현재가로 채운다(추가 KIS 0 — 스캔이 이미 조회한 가격 재사용).
     *
     * <p>⚠️ 트랜잭션은 호출자({@code evaluateStock}의 종목별 REQUIRES_NEW)를 그대로 탄다 —
     * 같은 클래스 내부 호출이라 별도 {@code @Transactional}을 붙여도 프록시를 안 타 무의미하기 때문.
     * 호출자가 롤백되면 이번 갱신도 함께 롤백되지만, 다음 스캔이 다시 채우므로 유실이 아니다.</p>
     *
     * <p>⚠️ 전 종목 × 매 스캔마다 쿼리를 날리지 않도록, 스캔 시작 시 <b>미완 타깃이 있는 종목 집합</b>을
     * 한 번만 조회해두고({@code pendingCodes}) 여기 속한 종목만 조회한다.</p>
     */
    private void fillTargets(String stockCode, long price) {
        if (price <= 0 || !active || !pendingCodes.contains(stockCode)) return;
        try {
            LocalDate today = LocalDate.now(SEOUL);
            String from = today.minusDays(5).format(YYYYMMDD);   // 익일 타깃까지 커버(연휴 여유)
            List<UniverseSnapshot> pending = repository.findPendingTargets(stockCode, from);
            if (pending.isEmpty()) return;
            java.time.Instant now = java.time.Instant.now();
            LocalTime nowTime = LocalTime.now(SEOUL);
            String todayStr = today.format(YYYYMMDD);
            boolean changed = false;
            for (UniverseSnapshot s : pending) {
                boolean sameDay = todayStr.equals(s.getSnapDate());
                if (s.getPrice90m() == null && sameDay
                        && Duration.between(s.getCapturedAt(), now).toMinutes() >= targetMinutes) {
                    s.setPrice90m(price); changed = true;
                }
                if (s.getPriceClose() == null && sameDay && !nowTime.isBefore(CLOSE_MARK)) {
                    s.setPriceClose(price); changed = true;
                }
                // 익일 종가 — 스냅샷 다음 거래일의 마감 무렵 관측가(당일분은 위 priceClose가 담당)
                if (s.getPriceNextClose() == null && !sameDay && s.getPriceClose() != null
                        && !nowTime.isBefore(CLOSE_MARK)) {
                    s.setPriceNextClose(price); changed = true;
                }
            }
            if (changed) repository.saveAll(pending);
        } catch (Exception ex) {
            log.debug("유니버스 타깃 갱신 실패 {}: {}", stockCode, ex.getMessage());
        }
    }

    /**
     * 지금이 어느 스냅샷 버킷에 속하는가(순수) — 버킷 시각 ≤ now &lt; 버킷 시각+window면 그 버킷, 아니면 null.
     * 가장 늦은(가까운) 버킷을 고른다.
     */
    static String bucketFor(LocalTime now, List<LocalTime> buckets, int windowMinutes) {
        if (now == null || buckets == null || buckets.isEmpty() || windowMinutes <= 0) return null;
        LocalTime best = null;
        for (LocalTime b : buckets) {
            if (!now.isBefore(b) && now.isBefore(b.plusMinutes(windowMinutes))
                    && (best == null || b.isAfter(best))) best = b;
        }
        return best == null ? null : best.toString();
    }

    /** 수집 현황(가시화 API용). */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : repository.summarize()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("snapDate", r[0]);
            m.put("snapTime", r[1]);
            m.put("rows", r[2]);
            m.put("filled90m", r[3]);
            m.put("filledClose", r[4]);
            m.put("filledNextClose", r[5]);
            out.add(m);
        }
        return out;
    }

    /** 현재 설정(진단용). */
    public Map<String, Object> config() {
        return Map.of("enabled", enabled, "times", Arrays.toString(buckets.toArray()),
                "windowMinutes", windowMinutes, "targetMinutes", targetMinutes);
    }
}
