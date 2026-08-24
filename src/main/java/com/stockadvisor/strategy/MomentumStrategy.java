package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 A — 공시 모멘텀 확인형 (현행 라이브 전략, Discord 알림 O).
 * 거래량 급증 + 상승 + 분봉 신선·활발(freshActive) + 추천 중립이상.
 */
@Component
public class MomentumStrategy implements TradingStrategy {

    private final SignalProperties props;
    private final boolean requireRisingFlow;   // 진입시점 지수흐름(mom30)<0이면 보류. 기본 off

    public MomentumStrategy(SignalProperties props,
                            @Value("${stockadvisor.signal.momentum-require-rising-flow:false}")
                            boolean requireRisingFlow) {
        this.props = props;
        this.requireRisingFlow = requireRisingFlow;
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
        // 흐름↓ 스킵(마지막 게이트) — A 조건을 다 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "A 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        return flowReject(ctx.indexMom30(), requireRisingFlow);
    }

    /**
     * 흐름↓ 스킵 판정(순수) — 진입 시점 지수 mom30 &lt; 0이면 {@code "FLOW_DOWN"}.
     *
     * <p>근거(2026-08-24 `flow-analysis`, <b>lag30·lag60 양쪽 일관</b>): A는 흐름↓ net
     * <b>−1.85%(n=41)/−1.87%(n=41)</b> vs 흐름↑ <b>−0.35%(n=42)</b> — 격차 1.5%p로 G·J와 같은 방향이다
     * (지수가 내리는 중의 공시 모멘텀 추격은 촉매가 시장에 먹힌다).</p>
     *
     * <p>⚠️ <b>표본이 얇다</b>(n=41 — G의 217/236보다 훨씬 작다). A는 공시 경로라 진입 자체가 드물어
     * (40일 79건) 표본이 느리게 쌓인다 → 방향은 믿되 <b>차단분 대조군으로 계속 재검할 것</b>.
     * ⚠️ 기대효과는 흑자 전환이 아니라 출혈 감소다 — 흐름↑만 남겨도 A는 −0.35%로 여전히 음수.
     * ⚠️ 흐름 미산출(null)이면 미적용(degrade open). ⚠️ 흐름 유불리는 전략마다 반대다(B는 딥바잉) —
     * 전역 게이트로 만들지 말 것.</p>
     */
    static String flowReject(Double indexMom30, boolean require) {
        if (!require || indexMom30 == null) return null;
        return indexMom30 < 0.0 ? "FLOW_DOWN" : null;
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
