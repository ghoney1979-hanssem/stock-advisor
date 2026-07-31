package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 H — 변동성 수축 돌파 (섀도우, <b>거래량 무관</b>).
 *
 * <p>E(신고가 돌파)는 거래량 급증을 요구해 "볼륨 붙기 전 조용한 수축→팽창"을 놓친다. 이 전략은 볼륨 무관하게
 * <b>NR7 수축 돌파 이벤트</b>(전일이 최근 7일 중 일중변동폭 최소 + 현재가 &gt; 전일 고가)로 롱 — 수축 후 상방 팽창 초입 포착.</p>
 *
 * <p><b>부하 안전장치</b>: (1) NR7+돌파는 이벤트라 하루 소수 종목만. (2) {@link #preScreen}으로 돌파 종목만 비싼 평가.
 * (3) {@link #tracksControl()}=false. (4) 섀도우. 지표는 이미 조회한 일봉으로 계산({@code SignalResult.squeezeBreakout}) — 추가 KIS 0.</p>
 */
@Component
public class VolatilitySqueezeStrategy implements TradingStrategy {

    private final boolean enabled;

    public VolatilitySqueezeStrategy(@Value("${stockadvisor.signal.squeeze-breakout-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "SQUEEZE_BREAKOUT_H";
    }

    @Override
    public String label() {
        return "변동성 수축 돌파 (H)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (ctx.inverse()) return "INVERSE";                      // 인버스는 전용 전략(I) — 중복 방지
        if (!ctx.signal().squeezeBreakout()) return "NO_SQUEEZE"; // NR7 수축 돌파 이벤트가 아니면 제외
        return null;                                              // 건전성·유동성은 상위 evaluateStock에서 필터됨
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // 수축 돌파가 트리거(볼륨 무관)
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        return signal.squeezeBreakout();   // 돌파 종목만 비싼 평가 진입(폭증 방지)
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
