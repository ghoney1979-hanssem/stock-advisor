package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 F — 이동평균 추세추종 (섀도우, <b>거래량 무관</b>).
 *
 * <p>현 라인업(B 횡보·C/D 역추세·E 돌파·A 공시)은 약세·역추세 편향이라 <b>순수 추세추종/강세장</b> 전략이 비어 있다.
 * 이 전략은 <b>MA20 상향 돌파 이벤트</b>(전일 종가 ≤ MA20, 현재가 &gt; MA20 — 오늘 처음 위로 뚫음)를 트리거로 롱.</p>
 *
 * <p><b>부하 안전장치</b>: (1) 트리거가 "상태(MA 위)"가 아니라 "이벤트(상향 돌파)"라 하루 소수 종목만 매칭 →
 * 다운스트림(quote/추천) 폭증 없음. (2) {@link #preScreen}으로 돌파 종목만 비싼 평가 진입(볼륨 게이트 우회).
 * (3) {@link #tracksControl()}=false로 대조군 미추적 → 후속추적 누적 부하 억제. (4) 섀도우(alerts=false·화이트리스트 미포함).</p>
 *
 * <p>MA20 계산은 {@code MarketSignalService}가 이미 조회한 일봉으로 수행({@code SignalResult.maCrossUp}) — 추가 KIS 0.</p>
 */
@Component
public class MovingAverageStrategy implements TradingStrategy {

    private final boolean enabled;
    /** [보완 필터①] 분봉 신선도 확인 — MA돌파가 "지금도 상승중·거래활발"일 때만 진입(죽은 돌파 회피). 기본 off(현행 무변경). */
    private final boolean requireFresh;
    /** [보완 필터②] 돌파 강도 버퍼(%) — 현재가가 MA20보다 이 %p 이상 위일 때만 진입(간신히 넘은 whipsaw 제외). 0=off. */
    private final double breakoutBufferPct;
    /** [보완 필터③] 진입시점 지수흐름(mom30)&lt;0이면 보류. 기본 off. */
    private final boolean requireRisingFlow;

    public MovingAverageStrategy(
            @Value("${stockadvisor.signal.ma-trend-enabled:true}") boolean enabled,
            @Value("${stockadvisor.signal.ma-trend-require-fresh:false}") boolean requireFresh,
            @Value("${stockadvisor.signal.ma-trend-breakout-buffer-pct:0.0}") double breakoutBufferPct,
            @Value("${stockadvisor.signal.ma-trend-require-rising-flow:false}") boolean requireRisingFlow) {
        this.enabled = enabled;
        this.requireFresh = requireFresh;
        this.breakoutBufferPct = breakoutBufferPct;
        this.requireRisingFlow = requireRisingFlow;
    }

    @Override
    public String name() {
        return "MA_TREND_F";
    }

    @Override
    public String label() {
        return "이동평균 추세추종 (F)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (ctx.inverse()) return "INVERSE";              // 인버스는 전용 전략(I)이 담당 — 중복 방지
        if (!ctx.signal().maCrossUp()) return "NO_CROSS"; // MA20 상향 돌파 이벤트가 아니면 제외
        // [보완 필터②] 돌파 강도 — MA를 간신히 넘은 whipsaw 제외(승자 진입등락률 6.06 vs 패자 4.91). 0=off.
        if (breakoutBufferPct > 0 && ctx.signal().maDistPct() < breakoutBufferPct) return "WEAK_BREAKOUT";
        // [보완 필터①] 분봉 신선도 — 돌파가 이미 식었으면(모멘텀↓·거래 위축) 제외(MFE 3.33 vs MAE -4.34 되돌림 회피). off면 미적용.
        if (requireFresh && !ctx.signal().maBreakoutFresh()) return "NOT_FRESH";
        // [보완 필터③] 흐름↓ 스킵(마지막 게이트) — F 조건을 다 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "F 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        String flow = flowReject(ctx.indexMom30(), requireRisingFlow);
        if (flow != null) return flow;
        return null;                                      // 건전성·유동성은 상위 evaluateStock에서 이미 필터됨
    }

    /**
     * 흐름↓ 스킵 판정(순수) — 진입 시점 지수 mom30 &lt; 0이면 {@code "FLOW_DOWN"}.
     *
     * <p>근거(2026-08-25 {@code flow-analysis}, <b>lag30·lag60 양쪽 일관</b>): F는 흐름↓ net <b>−0.95%(n=387)/−0.95%(n=388)</b>
     * vs 흐름↑ <b>−0.47%(n=572)/−0.46%(n=572)</b> — 추세추종이라 지수가 내리는 중의 MA20 상향돌파는 대체로 가짜 돌파(false breakout)다.
     * A·G·J에 같은 기준(양 lag 일관 + 표본 충분)으로 적용한 필터와 동일 형태.</p>
     *
     * <p>⚠️ <b>흐름 미산출(null)이면 미적용</b>(degrade open — 개장 ~30분·조회실패).
     * ⚠️ 흐름 방향의 유불리는 <b>전략마다 반대</b>다(B는 딥바잉이라 흐름↓ 우위) — 전역 게이트로 만들지 말 것.
     * ⚠️ F는 LIVE 화이트리스트 제외 상태(2026-08-21)라 <b>즉시 손익 효과는 0</b>이고, 재편입 판단용 섀도우 품질 개선이 목적.</p>
     */
    static String flowReject(Double indexMom30, boolean require) {
        if (!require || indexMom30 == null) return null;
        return indexMom30 < 0.0 ? "FLOW_DOWN" : null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // 볼륨이 아니라 MA 돌파가 트리거
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        return signal.maCrossUp();   // 돌파 종목만 비싼 평가 진입(폭증 방지)
    }

    @Override
    public boolean tracksControl() {
        return false;   // 전 종목 대상 → 대조군 미추적(후속추적 누적 부하 억제, 부하 안전장치)
    }

    @Override
    public boolean alerts() {
        return false;   // v1 섀도우(실주문 0, perf-gate 검증)
    }
}
