package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 J — 저평가 반등 (섀도우, <b>거래량 무관</b>). 펀더멘털(가치) 축.
 *
 * <p>"가치 + 촉매" 정석: <b>업종 대비 저평가</b>(PER 또는 PBR &lt; 업종 중앙값)인 종목이 <b>RSI 과매도에서 반등</b>하는
 * 순간 롱. G(RSI 반등)와 트리거 이벤트(RSI 상향돌파)는 공유하되, <b>저평가 종목으로 한정</b>해 가치 편향을 더한다.</p>
 *
 * <p><b>부하 안전장치</b>: (1) preScreen=RSI 상향돌파(이벤트)라 하루 소수만. (2) 저평가 판정은 {@code SectorValuationService}
 * 캐시(추가 KIS 0)로 evaluator가 미리 계산해 {@code ctx.undervalued()}에 실음. (3) {@link #tracksControl()}=false. (4) 섀도우.</p>
 */
@Component
public class ValueReversalStrategy implements TradingStrategy {

    private final boolean enabled;

    public ValueReversalStrategy(@Value("${stockadvisor.signal.value-reversal-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "VALUE_REVERSAL_J";
    }

    @Override
    public String label() {
        return "저평가 반등 (J)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (ctx.inverse()) return "INVERSE";                     // 인버스는 전용 전략(I)
        if (!ctx.signal().rsiCrossUp()) return "NO_RSI_CROSS";   // 반등 촉매(RSI 과매도 상향돌파) 없음
        if (!ctx.undervalued()) return "NOT_UNDERVALUED";        // 업종 대비 저평가 아님 → 가치 축 미충족
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // RSI 반등이 트리거(볼륨 무관)
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        return signal.rsiCrossUp();   // 반등 이벤트 종목만 비싼 평가 진입(저평가 판정은 그 다음)
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
