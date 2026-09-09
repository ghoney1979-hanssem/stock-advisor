package com.stockadvisor.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 화이트리스트 밖 전략의 추천 알림 차단 (2026-09-10, 사용자 요청).
 *
 * <p>화이트리스트에서 빠진 전략은 실주문을 못 내는데 신호 알림은 계속 나가, 읽는 사람이
 * "살 수 있는 추천"과 "기록만 남는 섀도우"를 구분할 수 없었다.</p>
 */
class AlertWhitelistTest {

    private static final List<String> PROD = List.of(
            "MOMENTUM_A", "MEAN_REVERSION_C", "INDEX_RELATIVE_D", "RSI_REVERSAL_G",
            "INVERSE_INDEX_I", "VALUE_REVERSAL_J", "REVERSAL_L", "MULTIDAY_REVERSION_P");

    @Test
    void 화이트리스트_안의_전략만_알림한다() {
        assertThat(StrategyEvaluator.alertWhitelisted(PROD, "MOMENTUM_A")).isTrue();
        assertThat(StrategyEvaluator.alertWhitelisted(PROD, "MEAN_REVERSION_C")).isTrue();
        // B는 2026-09-08 화이트리스트에서 제외됐다 — alerts()=true 지만 알림은 나가면 안 된다.
        assertThat(StrategyEvaluator.alertWhitelisted(PROD, "VOLUME_LEADING_B")).isFalse();
        assertThat(StrategyEvaluator.alertWhitelisted(PROD, "MA_TREND_F")).isFalse();
    }

    @Test
    void 화이트리스트가_비었으면_제약없음_종전동작() {
        // 코드 기본값·테스트 환경에서 알림이 통째로 사라지지 않게 degrade open.
        assertThat(StrategyEvaluator.alertWhitelisted(null, "VOLUME_LEADING_B")).isTrue();
        assertThat(StrategyEvaluator.alertWhitelisted(List.of(), "VOLUME_LEADING_B")).isTrue();
    }
}
