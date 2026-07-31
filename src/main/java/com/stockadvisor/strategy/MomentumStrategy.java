package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.stereotype.Component;

/**
 * 전략 A — 공시 모멘텀 확인형 (현행 라이브 전략, Discord 알림 O).
 * 거래량 급증 + 상승 + 분봉 신선·활발(freshActive) + 추천 중립이상.
 */
@Component
public class MomentumStrategy implements TradingStrategy {

    private final SignalProperties props;

    public MomentumStrategy(SignalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "MOMENTUM_A";
    }

    @Override
    public String label() {
        return "공시 모멘텀 확인형 (A)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        // freshActive 는 내부적으로 거래량급증 + 상승(+minChangeRate)을 이미 포함
        if (!ctx.signal().freshActive()) return "NOT_FRESH";
        if (ctx.recScore() < props.minOpinionScore()) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.DISCLOSURE;   // 공시가 촉매
    }

    @Override
    public boolean alerts() {
        return true;
    }
}
