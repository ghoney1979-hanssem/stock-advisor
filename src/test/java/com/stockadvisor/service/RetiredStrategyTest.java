package com.stockadvisor.service;

import com.stockadvisor.strategy.StrategyContext;
import com.stockadvisor.strategy.StrategyScope;
import com.stockadvisor.strategy.TradingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 폐기 — {@link StrategyEvaluator#selectStrategies}.
 *
 * <p>폐기는 "탈락 사유를 DISABLED로 두는 것"이 아니라 <b>평가 목록에서 빼는 것</b>이어야 한다.
 * 사유만 남기면 {@code tracksControl()=true} 전략에선 그 사유가 대조군으로 매일 쌓여,
 * 표본·스캔 부하를 없애려는 폐기의 목적이 정확히 뒤집힌다.</p>
 */
@DisplayName("전략 폐기(평가 목록 제외)")
class RetiredStrategyTest {

    private static TradingStrategy fake(String name, StrategyScope scope) {
        return new TradingStrategy() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public boolean shouldEnter(StrategyContext ctx) { return false; }
            @Override public StrategyScope scope() { return scope; }
        };
    }

    private static final List<TradingStrategy> ALL = List.of(
            fake("OPENING_GAP_K", StrategyScope.MARKET_SCAN),
            fake("BREAKOUT_E", StrategyScope.MARKET_SCAN),
            fake("REVERSAL_L", StrategyScope.MARKET_SCAN),
            fake("MOMENTUM_A", StrategyScope.DISCLOSURE));

    private static List<String> names(List<TradingStrategy> ss) {
        return ss.stream().map(TradingStrategy::name).toList();
    }

    @Test
    void 폐기된_전략은_평가목록에서_빠진다() {
        List<TradingStrategy> got = StrategyEvaluator.selectStrategies(
                ALL, StrategyScope.MARKET_SCAN, Set.of("OPENING_GAP_K", "BREAKOUT_E"));
        assertThat(names(got)).containsExactly("REVERSAL_L");
    }

    @Test
    void 폐기목록이_비면_종전과_같다() {
        assertThat(names(StrategyEvaluator.selectStrategies(ALL, StrategyScope.MARKET_SCAN, Set.of())))
                .containsExactly("OPENING_GAP_K", "BREAKOUT_E", "REVERSAL_L");
        assertThat(names(StrategyEvaluator.selectStrategies(ALL, StrategyScope.MARKET_SCAN, null)))
                .containsExactly("OPENING_GAP_K", "BREAKOUT_E", "REVERSAL_L");
    }

    @Test
    void scope_필터는_그대로_적용된다() {
        // 폐기 필터를 넣으면서 기존 scope 분기가 깨지지 않아야 한다(공시 경로 vs 스캔 경로).
        assertThat(names(StrategyEvaluator.selectStrategies(ALL, StrategyScope.DISCLOSURE, Set.of())))
                .containsExactly("MOMENTUM_A");
        assertThat(names(StrategyEvaluator.selectStrategies(ALL, StrategyScope.DISCLOSURE, Set.of("MOMENTUM_A"))))
                .isEmpty();
    }

    @Test
    void 없는_이름은_무시된다() {
        // 오타·이미 삭제된 전략명이 들어와도 조용히 무시(설정 실수로 전 전략이 멈추는 것보다 낫다).
        assertThat(names(StrategyEvaluator.selectStrategies(ALL, StrategyScope.MARKET_SCAN, Set.of("NOT_A_STRATEGY"))))
                .containsExactly("OPENING_GAP_K", "BREAKOUT_E", "REVERSAL_L");
    }
}
