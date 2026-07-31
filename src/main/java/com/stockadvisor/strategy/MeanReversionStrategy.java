package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.stereotype.Component;

/**
 * 전략 C — 눌림목/역추세 (섀도우).
 * 추천 우량(점수 ≥ meanReversionMinScore)인데 당일 급락(-minDrop% 이상) + 거래량 급증(투매)이고
 * 최근 분봉이 반등 중(reboundActive)일 때만 진입. — 떨어지는 칼날을 그대로 잡지 않도록 분봉 반등을 확인한다.
 *
 * <p>점수 게이트(40→55)와 분봉 반등확인은 승자/패자 분석으로 추가됨(승자 추천점수↑, C는 기존에 분봉확인 없이 진입).</p>
 */
@Component
public class MeanReversionStrategy implements TradingStrategy {

    private final SignalProperties props;

    public MeanReversionStrategy(SignalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "MEAN_REVERSION_C";
    }

    @Override
    public String label() {
        return "눌림목·역추세 (C)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        double change = ctx.signal().changeRate();
        // -minDrop ~ -maxDrop 범위의 '눌림목'만. 그 이상 폭락(정리매매·악재)은 제외.
        boolean inDropRange = change <= -props.meanReversionMinDrop()
                && change >= -props.meanReversionMaxDrop();
        if (!inDropRange) return "DROP_RANGE";
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        // 분봉 반등확인: 켜져 있으면 최근 분봉이 반등 중일 때만 진입(떨어지는 칼날 회피).
        if (props.meanReversionRequireRebound() && !ctx.signal().reboundActive()) return "NO_REBOUND";
        if (ctx.recScore() < props.meanReversionMinScore()) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;   // 공시 무관, 전 종목 스캔
    }

    @Override
    public boolean alerts() {
        return true;
    }
}
