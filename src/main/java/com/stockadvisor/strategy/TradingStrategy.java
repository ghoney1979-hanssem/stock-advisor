package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;

/**
 * 매매(가상) 진입 전략. 여러 구현을 동시에 섀도우 paper-trade 로 돌려 수익률을 비교한다.
 */
public interface TradingStrategy {

    /** 전략 식별자(TradeOutcome.strategy 에 저장, 리포트 그룹키). */
    String name();

    /** 사람이 읽는 전략 이름(알림 메시지 표시용). */
    String label();

    /** 현재 컨텍스트에서 이 전략이 (가상) 진입할지 여부. */
    boolean shouldEnter(StrategyContext ctx);

    /**
     * 진입하지 않는 사유(대조군 분석용 — 알림 안 나간 종목이 왜 탈락했는지 태깅).
     * 진입 가능하면 null. 기본은 단순 boolean(REJECTED) — 각 전략이 세부 사유로 override.
     */
    default String rejectReason(StrategyContext ctx) {
        return shouldEnter(ctx) ? null : "REJECTED";
    }

    /** 평가 트리거 범위 (공시 발생 종목 / 워치리스트 전 종목 스캔). */
    StrategyScope scope();

    /** Discord 실시간 알림 발송 여부. 기본은 섀도우(미발송). */
    default boolean alerts() {
        return false;
    }

    /**
     * 거래량 급증(volumeSpike)을 진입 전제로 요구하는지. 기본 true — 대부분 전략의 공통 게이트.
     * false면 볼륨 미급증이어도 평가(예: 인버스=지수약세 트리거). ⚠️ 볼륨무관 전략은 {@link #preScreen}으로
     * 대상을 좁혀야 함(전 종목 풀평가 폭증 방지).
     */
    default boolean requiresVolumeSpike() {
        return true;
    }

    /**
     * 값싼 1차 필터 — quote/추천 등 비싼 호출 <b>전에</b>, 이미 확보한 (종목코드 + {@link SignalResult} 일봉지표)만으로
     * 이 전략이 이 종목을 볼 가치가 있는지 판정. {@code requiresVolumeSpike()=false} 전략이 볼륨 게이트를 우회할지
     * 결정하는 데 쓰임 → 전 종목 비싼-평가 폭증 방지. 기본 true(제한 없음). 예: MA전략은 MA상향돌파(이벤트)일 때만 true.
     */
    default boolean preScreen(String stockCode, SignalResult signal) {
        return true;
    }

    /**
     * 대조군(미진입) 추적 기록 여부. 기본 true. false면 이 전략은 진입분만 기록(대조군 미기록) —
     * 전 종목 대상 전략의 후속추적 누적 부하를 억제(부하 안전장치).
     */
    default boolean tracksControl() {
        return true;
    }
}
