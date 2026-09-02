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
    private final boolean requireFresh;        // 분봉 신선도·활성도 요구. 기본 on(종전 동작)

    public MomentumStrategy(SignalProperties props,
                            @Value("${stockadvisor.signal.momentum-require-rising-flow:false}")
                            boolean requireRisingFlow,
                            @Value("${stockadvisor.signal.momentum-require-fresh:true}")
                            boolean requireFresh) {
        this.props = props;
        this.requireRisingFlow = requireRisingFlow;
        this.requireFresh = requireFresh;
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
        String base = baseReject(ctx, requireFresh, props.minChangeRate());
        if (base != null) return base;
        if (ctx.recScore() < props.minOpinionScore()) return "SCORE";
        // 흐름↓ 스킵(마지막 게이트) — A 조건을 다 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "A 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        return flowReject(ctx.indexMom30(), requireRisingFlow);
    }

    /**
     * A의 기본 조건(순수) — {@code requireFresh}면 종전대로 {@code freshActive} 하나로, 아니면 그 구성요소를 분해해
     * <b>거래량급증 + 상승만</b> 요구한다.
     *
     * <p>⚠️ <b>분해가 이 메서드의 존재 이유다.</b> {@code freshActive = 분봉신선·활발 AND 거래량급증 AND 상승}으로
     * <b>세 조건이 묶여 있어서</b>, 신선도를 끄겠다고 이 플래그 하나를 통째로 무시하면 A의 정체성인 <b>"상승을 산다"</b>까지
     * 같이 사라진다(하락 중인 공시 종목도 진입하게 된다). 그건 필터 완화가 아니라 다른 전략이다.</p>
     *
     * <p><b>근거</b>(2026-09-02 `control-analysis`, close, 기간 정렬): A의 차단분 {@code NOT_FRESH}가
     * <b>−0.33%(n=295, 44거래일)</b>로 같은 창 진입분 <b>−0.83%(n=86)</b>보다 <b>+0.52%p 낫다</b> —
     * 즉 신선도 요구가 더 나은 후보를 버리고 있다. F가 2026-08-14에 정확히 같은 근거
     * ({@code NOT_FRESH} +0.47% vs 진입 −1.35%)로 {@code ma-trend-require-fresh}를 원복한 전례가 있고,
     * A의 차단분은 44거래일에 걸쳐 있어 단일일 클러스터도 아니다.</p>
     *
     * <p>⚠️ 진입 n=86으로 얇다(A는 공시 경로라 진입 자체가 드물다). ⚠️ 기대효과는 흑자 전환이 아니라
     * <b>출혈 감소</b>다 — 어느 쪽이든 A는 음수다. ⚠️ 끄면 {@code NO_VOLUME}/{@code NOT_RISING}이 새 대조군
     * 사유로 남아 되돌릴 근거가 계속 쌓인다.</p>
     */
    static String baseReject(StrategyContext ctx, boolean requireFresh, double minChangeRate) {
        if (requireFresh) return ctx.signal().freshActive() ? null : "NOT_FRESH";
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        return ctx.signal().changeRate() < minChangeRate ? "NOT_RISING" : null;
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
