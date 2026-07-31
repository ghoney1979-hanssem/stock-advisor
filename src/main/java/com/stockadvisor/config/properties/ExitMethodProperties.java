package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 적응형 청산방식 설정 ({@code stockadvisor.trading.adaptive-exit-method}).
 *
 * <p>청산방식(시간기반/트레일링/VWAP이탈/추세전환)을 고정하지 않고, {@code ExitStrategyService}가 과거 가격경로로
 * 시뮬레이션한 <b>전략별 평균 net 수익 최대 방식</b>을 자동 채택한다(보유시간 적응형 {@code adaptive-exit}와 같은 철학).
 * 표본이 {@code minSamples} 미만이면 신뢰 불가로 시간기반(TIME)으로 fallback.</p>
 *
 * @param enabled        적응형 청산방식 사용 여부(기본 true). false면 항상 시간기반.
 * @param minSamples     방식 채택 최소 표본(미만이면 TIME fallback). 기본 30.
 * @param refreshMinutes 추천 재계산 캐시 주기(분). 기본 30.
 * @param trendConfirm   추세전환(TREND_REVERSAL) 청산 확인 횟수 — <b>N회 연속 하락</b>이어야 청산(단일 틱 휩쏘 방지). 기본 3.
 *                       라이브는 1분 점검이라 ≈N분 확인, 시뮬은 5분 마크라 ≈5N분(가격경로 프록시). 1이면 종전(첫 하락 즉시).
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.adaptive-exit-method")
public record ExitMethodProperties(
        boolean enabled,
        int minSamples,
        int refreshMinutes,
        int trendConfirm
) {
    public ExitMethodProperties {
        if (minSamples < 0) minSamples = 30;
        if (refreshMinutes <= 0) refreshMinutes = 30;
        if (trendConfirm < 1) trendConfirm = 3;
    }
}
