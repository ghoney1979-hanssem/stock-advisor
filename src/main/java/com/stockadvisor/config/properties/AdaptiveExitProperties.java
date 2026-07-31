package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 적응형 시간기반 청산 설정 ({@code stockadvisor.trading.adaptive-exit}).
 *
 * <p>전략별 보유시간을 고정값({@code trading.time-exit-hold-minutes}) 대신, {@code ExitTimingService}가 계산한
 * <b>평균 net 수익 최대 보유시간(권장 청산시점)</b>으로 자동 설정한다. 표본이 {@code minSamples} 미만인 마크는
 * 신뢰할 수 없어 제외하고, 자격 마크가 없으면 고정값으로 fallback(데이터 부족 시 안전).</p>
 *
 * <p>매분 청산 점검마다 재계산하지 않도록 {@code refreshMinutes} 주기로 캐싱한다.</p>
 *
 * @param enabled        적응형 사용 여부(기본 true). false면 고정 time-exit-hold-minutes 사용.
 * @param minSamples     보유시간 마크 채택 최소 표본(미만이면 그 마크 제외). 기본 20.
 * @param refreshMinutes 권장 보유시간 재계산 캐시 주기(분). 기본 30.
 * @param maxHoldMinutes 보유시간 상한(분) — 종가(EOD) 권장이거나 과대 마크일 때 캡. 기본 300.
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.adaptive-exit")
public record AdaptiveExitProperties(
        boolean enabled,
        int minSamples,
        int refreshMinutes,
        int maxHoldMinutes
) {
    public AdaptiveExitProperties {
        if (minSamples < 0) minSamples = 20;
        if (refreshMinutes <= 0) refreshMinutes = 30;
        if (maxHoldMinutes <= 0) maxHoldMinutes = 300;
    }
}
