package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 전략 K — 개장 갭 (순수 시초가, 섀도우, <b>거래량 무관</b>).
 *
 * <p>"어제까지의 시장 국면 + 오늘 시초가 갭"으로 <b>개장 창(09:00~windowEnd)</b>에 판단하는 단타. ORB(개장 레인지)와 달리
 * 15분 레인지 형성을 기다리지 않고, <b>시가 대비 갭업 + 갭 유지(시가 이탈 안 함) + 비약세 국면</b>이면 롱.</p>
 *
 * <ul>
 *   <li>갭 = (오늘시가−전일종가)/전일종가 (`SignalResult.gapPct`, MarketSignalService가 일봉 open으로 계산).</li>
 *   <li>갭 유지 = 현재 등락률(changeRate) ≥ 갭 — 즉 현재가 ≥ 시가(개장 후 시가 아래로 안 밀림).</li>
 *   <li>약세장(entryTrend=BEAR) 제외 — 약세장 갭업은 불트랩 잦음(E 돌파와 동일 이유).</li>
 *   <li>과대갭(&gt;maxGap) 제외 — 상한가 추격 회피.</li>
 * </ul>
 *
 * <p>개장 창 밖·비갭 종목은 {@link #preScreen}에서 걸러 전 종목 폭평가 없음. ⚠️ 폴링 구조상 체결은 첫 스캔(~09:00~07)의
 * 현재가(시가 근접)라 "시초가 정확 체결"은 아님(개장 창 근사). v1 섀도우(화이트리스트 미포함 → 실주문 0, perf-gate 검증).</p>
 */
@Component
public class OpeningGapStrategy implements TradingStrategy {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime WINDOW_START = LocalTime.of(9, 0);

    private final boolean enabled;
    private final double minGap;       // 최소 갭업%
    private final double maxGap;       // 최대 갭%(상한가 추격 회피)
    private final double minScore;     // 추천 점수 게이트
    private final LocalTime windowEnd; // 개장 창 종료(09:00~이 시각)
    private final double maxIndexGap;  // 지수 갭 상한%(이 이상 갭업한 날은 진입 보류). 0=비활성

    public OpeningGapStrategy(@Value("${stockadvisor.signal.opening-gap-enabled:true}") boolean enabled,
                              @Value("${stockadvisor.signal.opening-gap-min-gap:2.0}") double minGap,
                              @Value("${stockadvisor.signal.opening-gap-max-gap:10.0}") double maxGap,
                              @Value("${stockadvisor.signal.opening-gap-min-score:40.0}") double minScore,
                              @Value("${stockadvisor.signal.opening-gap-window-end:09:30}") String windowEnd,
                              @Value("${stockadvisor.signal.opening-gap-max-index-gap:0}") double maxIndexGap) {
        this.enabled = enabled;
        this.minGap = minGap;
        this.maxGap = maxGap;
        this.minScore = minScore;
        this.windowEnd = LocalTime.parse(windowEnd);
        this.maxIndexGap = maxIndexGap;
    }

    private boolean inWindow() {
        return inWindow(LocalTime.now(SEOUL));
    }

    private boolean inWindow(LocalTime now) {
        return !now.isBefore(WINDOW_START) && !now.isAfter(windowEnd);
    }

    @Override
    public String name() {
        return "OPENING_GAP_K";
    }

    @Override
    public String label() {
        return "개장갭 (K)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        return reject(ctx, LocalTime.now(SEOUL));
    }

    /** 시간 주입형(테스트용). */
    String reject(StrategyContext ctx, LocalTime now) {
        if (!enabled) return "DISABLED";
        if (!inWindow(now)) return "OUT_OF_WINDOW";           // 개장 창 밖
        SignalResult s = ctx.signal();
        double gap = s.gapPct();
        if (gap < minGap) return "NO_GAP";                    // 갭업 부족
        if (gap > maxGap) return "GAP_TOO_BIG";               // 과대갭(상한가 추격) 회피
        if (indexGapDay(ctx.indexGapPct(), maxIndexGap)) return "INDEX_GAP_DAY";  // 지수 통째 갭업일 제외
        if ("BEAR".equals(ctx.entryTrend())) return "REGIME_BEAR";  // 약세장 갭업 제외
        if (s.changeRate() < gap) return "FADING";            // 현재가<시가(갭 못 지킴)
        if (ctx.recScore() < minScore) return "SCORE";
        return null;                                          // 갭업 유지 + 비약세 → 개장 롱
    }

    /**
     * 지수 통째 갭업일 판정(순수) — 지수 갭 ≥ maxIndexGap이면 true(진입 보류).
     *
     * <p>근거(2026-08-14 실측): KOSPI가 +2.6%, KOSDAQ이 +1.6~2.0% <b>갭업 개장</b>한 날 K가 09:01~09:06에 7건 진입 →
     * 오전 되돌림(코스닥은 +1.6%→−1.3%로 3%p 반전)에 4건이 손절선(−5.3%) 직행, <b>−120,050원(계좌 −1.13%)</b>.
     * 지수가 통째로 갭업하면 개별 종목 갭업은 "종목 고유 촉매"가 아니라 시장 갭의 반영이라, 갭이 되돌려질 때
     * 종목 선택과 무관하게 전부 같이 무너진다 — K의 전제("어제까지 국면 + <b>오늘 종목</b> 시초가 갭")가 성립하지 않는 날.</p>
     *
     * <p>지수 갭 미상(장전·휴장·조회실패)이면 false = 필터 미적용(degrade open — 데이터 실패로 매매를 막지 않음).
     * maxIndexGap ≤ 0이면 비활성.</p>
     */
    static boolean indexGapDay(Double indexGapPct, double maxIndexGap) {
        if (maxIndexGap <= 0 || indexGapPct == null) return false;
        return indexGapPct >= maxIndexGap;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // 갭이 트리거 — 볼륨 게이트 우회
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        return inWindow() && signal.gapPct() >= minGap;   // 개장 창 + 갭업 후보만(전 종목 폭증 방지)
    }

    @Override
    public boolean alerts() {
        return false;   // v1 섀도우(화이트리스트 미포함 → 실주문 0)
    }

    @Override
    public boolean tracksControl() {
        return false;   // 볼륨무관 계열과 동일 — 후속 부하 절감
    }
    // 종목당 하루 1회는 파이프라인 공통 dedup(existsByStrategyAndStockCodeAndAlertDate)이 처리 — 별도 플래그 불요.
}
