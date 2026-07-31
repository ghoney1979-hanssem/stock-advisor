package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.stereotype.Component;

/**
 * 전략 E — 신고가 돌파 모멘텀 (섀도우, 공격적).
 *
 * <p>직전 {@code breakoutLookback}거래일 최고가(오늘 제외)를 <b>거래량 급증과 함께 돌파</b>하면 롱.
 * "강세를 산다"라 A(공시모멘텀 상승)를 제외한 B/C/D의 횡보·역추세·상대부진 편향과 <b>반대 방향</b> →
 * 전략 다양성(비상관)을 늘려 포트폴리오 다중검정 위험을 완화한다.</p>
 *
 * <ul>
 *   <li>공격적: 눌림목이 아니라 이미 오르는 추세의 초입을 추격 → 변동성·손실폭이 커, catastrophic stop을 타이트하게
 *       두는 것이 좋다(재난 방지). 검증(perf-gate) 통과 전엔 실주문이 안 나가므로 섀도우로 안전하게 시험 가능.</li>
 *   <li>직전 최고가는 {@code MarketSignalService}가 이미 조회한 일봉({@code SignalResult.priorHigh})을 재활용 → 추가 KIS 호출 0.</li>
 *   <li>공통(volumeSpike·건전성·유동성·저가주)은 {@code StrategyEvaluator}에서 이미 필터됨.</li>
 * </ul>
 */
@Component
public class BreakoutStrategy implements TradingStrategy {

    private final SignalProperties props;

    public BreakoutStrategy(SignalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "BREAKOUT_E";
    }

    @Override
    public String label() {
        return "신고가 돌파 모멘텀 (E)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        long priorHigh = ctx.signal().priorHigh();
        if (priorHigh <= 0) return "NO_HIGH";                 // 직전 최고가 산정 불가(데이터 부족)
        long price = ctx.signal().closePrice();
        double threshold = priorHigh * (1.0 + props.breakoutBufferPct() / 100.0);
        if (price < threshold) return "NOT_BREAKOUT";         // 아직 직전 N일 최고가 미돌파
        if (ctx.recScore() < props.breakoutMinScore()) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;   // 공시 무관, 전 종목 스캔
    }

    @Override
    public boolean alerts() {
        return false;   // 검증 단계: 조용한 섀도우(가상매수/성과기록만, Discord 미발송). 검증 후 켜기.
    }
}
