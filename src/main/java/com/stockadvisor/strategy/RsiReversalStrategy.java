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

    public RsiReversalStrategy(@Value("${stockadvisor.signal.rsi-reversal-enabled:true}") boolean enabled) {
        this.enabled = enabled;
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
        return null;                                         // 건전성·유동성은 상위 evaluateStock에서 필터됨
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
