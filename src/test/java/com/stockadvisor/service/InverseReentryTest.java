package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인버스 재진입 자격(순수 판정) — 쿨다운 경과 + 활성 실포지션 없음일 때만 같은 날 재평가 허용.
 * (2026-07-14 배경: 오전 인버스 청산 직후 코스닥 -6% 재붕괴를 하루 1회 dedup 때문에 놓침.)
 */
class InverseReentryTest {

    /** 진입(비 control) 행 — alertTime 은 생성 시각(Instant.now()). */
    private TradeOutcome entryRow() {
        return new TradeOutcome("INVERSE_INDEX_I", null, "114800", "20260714", 1_100);
    }

    private TradeOutcome controlRow() {
        TradeOutcome o = entryRow();
        o.markControl("INDEX_NOT_WEAK");
        return o;
    }

    @Test
    void 쿨다운_경과_그리고_무포지션이면_재진입_허용() {
        List<TradeOutcome> prior = List.of(entryRow());
        Instant later = Instant.now().plus(Duration.ofMinutes(30));   // 마지막 진입 후 30분(≥ 쿨다운 20분)

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, later, false)).isTrue();
    }

    @Test
    void 쿨다운_미경과면_차단() {
        List<TradeOutcome> prior = List.of(entryRow());
        Instant soon = Instant.now().plus(Duration.ofMinutes(5));     // 5분 < 20분

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, soon, false)).isFalse();
    }

    @Test
    void 활성_포지션이_있으면_차단() {
        List<TradeOutcome> prior = List.of(entryRow());
        Instant later = Instant.now().plus(Duration.ofMinutes(60));

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, later, true)).isFalse();
    }

    @Test
    void 비활성_0이면_항상_차단_기존_하루1회() {
        List<TradeOutcome> prior = List.of(entryRow());
        Instant later = Instant.now().plus(Duration.ofMinutes(999));

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 0, later, false)).isFalse();
    }

    @Test
    void 하루_사이클_상한에_도달하면_쿨다운_경과여도_차단() {
        // 2026-08-18 실측: 251340이 10차까지 재진입해 7왕복 −4,521원(왕복비용 0.22%×n이 방향을 이긴다).
        List<TradeOutcome> prior = List.of(entryRow(), entryRow(), entryRow());   // 이미 3사이클
        Instant later = Instant.now().plus(Duration.ofMinutes(60));

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, later, false, 3)).isFalse();
        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, later, false, 0)).isTrue();   // 0=무제한(종전)
    }

    @Test
    void 사이클_상한_계산에_대조군_행은_세지_않는다() {
        // control 행은 '진입'이 아니므로 상한 소진에 포함되면 안 된다(승격 경로가 막히는 부작용 방지).
        List<TradeOutcome> prior = List.of(entryRow(), controlRow(), controlRow());
        Instant later = Instant.now().plus(Duration.ofMinutes(60));

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, later, false, 3)).isTrue();
    }

    @Test
    void 진입행이_없고_control만이면_쿨다운_무관_허용() {
        // 이 경로는 pending 필터의 승격 분기가 먼저 잡지만, 방어적으로도 통과여야 함
        List<TradeOutcome> prior = List.of(controlRow());

        assertThat(StrategyEvaluator.inverseReentryEligible(prior, 20, Instant.now(), false)).isTrue();
    }
}
