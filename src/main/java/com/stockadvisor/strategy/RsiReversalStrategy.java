package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 G — RSI 과매도 반등 (섀도우, <b>거래량 무관</b>).
 *
 * <p>C(역추세)는 거래량 급증 + 분봉 반등확인을 요구해 "조용한 과매도"를 놓친다. 이 전략은 볼륨 무관하게
 * <b>RSI(14)가 과매도(30)를 상향 돌파하는 이벤트</b>(어제 RSI ≤ 30, 오늘 RSI &gt; 30)로 롱 — 과매도에서 회복 시작을 포착.</p>
 *
 * <p><b>부하 안전장치</b>: (1) 트리거가 "RSI&lt;30 상태"가 아니라 "상향 돌파 이벤트"라 하루 소수 종목만 매칭.
 * (2) {@link #preScreen}으로 돌파 종목만 비싼 평가. (3) {@link #tracksControl()}=false. (4) 섀도우.
 * RSI는 {@code MarketSignalService}가 이미 조회한 일봉으로 계산({@code SignalResult.rsiCrossUp}) — 추가 KIS 0.</p>
 */
@Component
public class RsiReversalStrategy implements TradingStrategy {

    private final boolean enabled;
    private final boolean requireRisingFlow;   // 진입시점 지수흐름(mom30)<0이면 보류. 기본 off

    public RsiReversalStrategy(@Value("${stockadvisor.signal.rsi-reversal-enabled:true}") boolean enabled,
                               @Value("${stockadvisor.signal.rsi-reversal-require-rising-flow:false}") boolean requireRisingFlow) {
        this.enabled = enabled;
        this.requireRisingFlow = requireRisingFlow;
    }

    @Override
    public String name() {
        return "RSI_REVERSAL_G";
    }

    @Override
    public String label() {
        return "RSI 과매도 반등 (G)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (ctx.inverse()) return "INVERSE";                 // 인버스는 전용 전략(I) — 중복 방지
        if (!ctx.signal().rsiCrossUp()) return "NO_RSI_CROSS";
        // 흐름↓ 스킵(마지막 게이트) — G 조건을 다 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "G 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        String flow = flowReject(ctx.indexMom30(), requireRisingFlow);
        if (flow != null) return flow;
        return null;                                         // 건전성·유동성은 상위 evaluateStock에서 필터됨
    }

    /**
     * 흐름↓ 스킵 판정(순수) — 진입 시점 지수 mom30 &lt; 0이면 {@code "FLOW_DOWN"}.
     *
     * <p>근거(2026-08-21 `flow-analysis`, <b>lag30·lag60 양쪽 일관</b>): G는 흐름↓ net <b>−0.90%(n=217)/−0.88%(n=236)</b> vs 흐름↑ <b>−0.08%/−0.05%</b>(n=404/389) — 반등 계열이라 지수가 내리는 중의 과매도 반등은 "떨어지는 칼날"이 된다.</p>
     *
     * <p>⚠️ <b>흐름 미산출(null)이면 미적용</b>(degrade open — 개장 ~30분·조회실패. D의 기존 흐름 가드와 동일 원칙).
     * ⚠️ 흐름 방향의 유불리는 <b>전략마다 반대</b>다(B는 딥바잉이라 흐름↓ 우위) — 전역 게이트로 만들지 말 것.</p>
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
        return false;   // RSI 과매도 돌파가 트리거(볼륨 무관)
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        return signal.rsiCrossUp();   // 돌파 종목만 비싼 평가 진입(폭증 방지)
    }

    @Override
    public boolean tracksControl() {
        return false;   // 전 종목 대상 → 대조군 미추적(후속추적 누적 부하 억제)
    }

    @Override
    public boolean alerts() {
        return false;   // v1 섀도우(실주문 0, perf-gate 검증)
    }
}
