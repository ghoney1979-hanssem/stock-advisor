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
 *
 * <p><b>⚠️ 종가 타깃은 관측가 근사가 아니라 익일 일봉의 확정 종가로 채운다(2026-08-19 수정)</b>.
 * 이전엔 "15:15 이후 스캔에서 다시 만난 현재가"를 종가로 삼았는데, 전수 스캔이 12분 주기이고 세션 가드가
 * 15:20에 자르므로 <b>종가를 채울 수 있는 창이 5분뿐</b>이었다. 순회 순서가 매일 같아 채워지는 종목이
 * 고정되고, 실측 결과 <b>KOSDAQ 92% vs KOSPI 36%</b>로 시장이 편중됐다(8/19 09:30 버킷 기준. 전체 762/1334=57%).
 * 급증 모집단 편향을 없애려고 만든 분모에 순회 순서라는 새 편향이 들어간 셈이라, <b>다음 거래일 첫 스캔에서
 * {@code SignalResult.prevClose}(직전 거래일 확정 종가)로 채운다</b> — 커버리지 100% · 근사 아닌 공식 종가 ·
 * 추가 KIS 0. 대신 종가는 <b>익일에야</b> 채워진다(연구용이라 무해).
 * 익일종가도 같은 값으로 채운다(전전일 스냅샷 기준). ⚠️ 소급 불가 — 수정 이전 수집분의 종가 결손은 복구되지 않는다.</p>
 */
@Service
public class UniverseSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(UniverseSnapshotService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final UniverseSnapshotRepository repository;
    private final CompanyRepository companyRepository;
    private final boolean enabled;
    private final List<LocalTime> buckets;
    private final int windowMinutes;
    private final int targetMinutes;
    private final LocalTime sessionEnd;
    /** +90분 타깃이 장중에 도달 가능한 버킷 라벨(버킷+target ≤ sessionEnd). 나머지는 90분 타깃 '해당 없음'. */
    private final List<String> m90Buckets;

    private boolean active;
    private Thread scanThread;                    // 전수 스캔 스레드 고정(핫스캔 중복 배제) — breadth와 동일
    private String activeBucket;                  // 이번 스캔에서 수집 중인 버킷 라벨(null=수집 안 함)
    private List<UniverseSnapshot> buffer = new ArrayList<>();
    private volatile Map<String, String> codeToMarket = Map.of();
    /** 미완 타깃이 있는 종목 — 스캔 시작 시 1회 조회. 전 종목마다 쿼리하는 것을 막는 값싼 사전필터. */
    private volatile java.util.Set<String> pendingCodes = java.util.Set.of();
    /** 우리 DB 기준 직전 스냅샷 일자(종가 타깃 대상) — 스캔 시작 시 1회 조회. */
    private volatile String prevSnapDate;
    /** 그 이전 스냅샷 일자(익일종가 타깃 대상). */
    private volatile String prevPrevSnapDate;

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
                                   @Value("${stockadvisor.signal.universe-snapshot-target-minutes:90}") int targetMinutes,
                                   @Value("${stockadvisor.signal.session-end:15:20}") String sessionEnd) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.enabled = enabled;
        this.buckets = parseTimes(times);
        this.windowMinutes = windowMinutes;
        this.targetMinutes = targetMinutes;
        this.sessionEnd = LocalTime.parse(sessionEnd);
        this.m90Buckets = reachable90mBuckets(this.buckets, targetMinutes, this.sessionEnd);
    }

    /**
     * +90분 타깃이 장중에 도달 가능한 버킷만(순수). 버킷+target &gt; 세션종료면 스캔이 끊겨 영원히 못 채우므로
     * 그 버킷의 {@code price90m} null은 "미완"이 아니라 "해당 없음"이다.
     *
     * <p>⚠️ 반환값이 비면 JPA {@code in ()}이 되므로 매칭 안 되는 sentinel을 넣는다.</p>
     */
    static List<String> reachable90mBuckets(List<LocalTime> buckets, int targetMinutes, LocalTime sessionEnd) {
        List<String> out = new ArrayList<>();
        for (LocalTime b : buckets) {
            if (!b.plusMinutes(targetMinutes).isAfter(sessionEnd)) out.add(b.toString());
        }
        return out.isEmpty() ? List.of("-") : List.copyOf(out);
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
                    repository.findPendingCodes(LocalDate.now(SEOUL).minusDays(5).format(YYYYMMDD), m90Buckets));
        } catch (Exception ex) {
            pendingCodes = java.util.Set.of();
            log.debug("유니버스 미완 타깃 조회 실패: {}", ex.getMessage());
        }
        // 종가/익일종가 타깃의 대상 일자 — 우리 DB 기준 직전 두 스냅샷일.
        // 실제 채울 때 일봉의 prevBusinessDate와 대조해 "정말 직전 거래일인지" 검증한다(앱 다운 등으로 어긋나면 skip).
        try {
            List<String> recent = repository.findSnapDatesBefore(today);
            prevSnapDate = recent.isEmpty() ? null : recent.get(0);
            prevPrevSnapDate = recent.size() > 1 ? recent.get(1) : null;
        } catch (Exception ex) {
            prevSnapDate = null; prevPrevSnapDate = null;
            log.debug("유니버스 직전 스냅샷일 조회 실패: {}", ex.getMessage());
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
        fillTargets(stockCode, signal.closePrice(), signal.prevClose(), signal.prevBusinessDate());
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
     * 이전 스냅샷의 사후 타깃을 채운다(추가 KIS 0 — 스캔이 이미 조회한 가격/일봉 재사용).
     *
     * <ul>
     *   <li><b>+90분</b>: 당일 스냅샷을 현재가로(경과 ≥ target). 90분 타깃이 장중에 도달 가능한 버킷만 대상.</li>
     *   <li><b>당일 종가</b>: <b>직전 거래일 스냅샷</b>을 오늘 일봉의 {@code prevClose}(확정 종가)로.
     *       관측가 근사를 쓰지 않으므로 스캔 순서·시각과 무관하게 <b>전 종목이 채워진다</b>.</li>
     *   <li><b>익일 종가</b>: 전전 거래일 스냅샷에 같은 값(= 직전 거래일 종가)을.</li>
     * </ul>
     *
     * <p>⚠️ 종가 계열은 <b>{@code prevBusinessDate}(일봉이 말하는 직전 영업일)와 스냅샷 일자가 정확히 일치할 때만</b>
     * 채운다 — 일봉이 밀리거나(장전·휴장) 앱이 하루 쉬어 DB의 "직전 스냅샷일"이 실제 직전 거래일이 아닐 때
     * 엉뚱한 날의 종가가 들어가는 것을 막는다(불일치 시 skip = fail-closed).</p>
     *
     * <p>⚠️ 트랜잭션은 호출자({@code evaluateStock}의 종목별 REQUIRES_NEW)를 그대로 탄다 —
     * 같은 클래스 내부 호출이라 별도 {@code @Transactional}을 붙여도 프록시를 안 타 무의미하기 때문.
     * 호출자가 롤백되면 이번 갱신도 함께 롤백되지만, 다음 스캔이 다시 채우므로 유실이 아니다.</p>
     *
     * <p>⚠️ 전 종목 × 매 스캔마다 쿼리를 날리지 않도록, 스캔 시작 시 <b>미완 타깃이 있는 종목 집합</b>을
     * 한 번만 조회해두고({@code pendingCodes}) 여기 속한 종목만 조회한다.</p>
     */
    private void fillTargets(String stockCode, long price, long prevClose, String prevBusinessDate) {
        if (price <= 0 || !active || !pendingCodes.contains(stockCode)) return;
        try {
            LocalDate today = LocalDate.now(SEOUL);
            String from = today.minusDays(5).format(YYYYMMDD);   // 익일 타깃까지 커버(연휴 여유)
            List<UniverseSnapshot> pending = repository.findPendingTargets(stockCode, from, m90Buckets);
            if (pending.isEmpty()) return;
            java.time.Instant now = java.time.Instant.now();
            String todayStr = today.format(YYYYMMDD);
            boolean closeUsable = closeFillUsable(prevClose, prevBusinessDate, prevSnapDate);
            boolean changed = false;
            for (UniverseSnapshot s : pending) {
                boolean sameDay = todayStr.equals(s.getSnapDate());
                if (s.getPrice90m() == null && sameDay && m90Buckets.contains(s.getSnapTime())
                        && Duration.between(s.getCapturedAt(), now).toMinutes() >= targetMinutes) {
                    s.setPrice90m(price); changed = true;
                }
                if (!closeUsable) continue;
                // 당일 종가 — 직전 거래일 스냅샷에 그 날의 확정 종가
                if (s.getPriceClose() == null && prevBusinessDate.equals(s.getSnapDate())) {
                    s.setPriceClose(prevClose); changed = true;
                }
                // 익일 종가 — 전전 거래일 스냅샷 입장에선 직전 거래일 종가가 곧 '익일 종가'
                if (s.getPriceNextClose() == null && prevPrevSnapDate != null
                        && prevPrevSnapDate.equals(s.getSnapDate())) {
                    s.setPriceNextClose(prevClose); changed = true;
                }
            }
            if (changed) repository.saveAll(pending);
        } catch (Exception ex) {
            log.debug("유니버스 타깃 갱신 실패 {}: {}", stockCode, ex.getMessage());
        }
    }

    /**
     * 종가 계열 타깃을 채워도 되는가(순수) — <b>일봉이 말하는 직전 영업일</b>이 <b>우리 DB의 직전 스냅샷일</b>과
     * 정확히 같을 때만 true.
     *
     * <p>이 대조가 없으면, 앱이 하루 쉬었거나 일봉이 밀린 날(장전·휴장) DB의 "직전 스냅샷일"이 실제 직전
     * 거래일이 아니어서 <b>엉뚱한 날짜의 종가</b>가 들어간다. 불일치면 채우지 않는다(fail-closed) —
     * 비어 있는 종가는 다음 분석에서 제외되지만, 잘못 채워진 종가는 조용히 결과를 오염시킨다.</p>
     */
    static boolean closeFillUsable(long prevClose, String prevBusinessDate, String prevSnapDate) {
        return prevClose > 0 && prevBusinessDate != null && prevBusinessDate.equals(prevSnapDate);
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
            // 마감 직전 버킷(예 14:30)은 +90분이 장 마감을 넘어 구조적으로 채울 수 없다 — 결손이 아니라 해당 없음.
            m.put("has90mTarget", m90Buckets.contains(String.valueOf(r[1])));
            m.put("filledClose", r[4]);
            m.put("filledNextClose", r[5]);
            out.add(m);
        }
        return out;
    }

    /** 현재 설정(진단용). */
    public Map<String, Object> config() {
        return Map.of("enabled", enabled, "times", Arrays.toString(buckets.toArray()),
                "windowMinutes", windowMinutes, "targetMinutes", targetMinutes,
                "sessionEnd", sessionEnd.toString(), "m90Buckets", Arrays.toString(m90Buckets.toArray()),
                "closeSource", "익일 일봉 확정 종가(prevClose) — 관측가 근사 아님");
    }
}
