package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전략별 적응형 catastrophic 손절 설정 ({@code stockadvisor.trading.adaptive-stop}).
 *
 * <p>고정 손절({@code trading.risk.catastrophic-stop-pct}, 기본 −7%) 대신, {@code MaeAnalysisService}의
 * 전략별 <b>승자 MAE 분포(worst10 = 승자의 10분위 최대 역행)</b>로 손절선을 자동 설정한다. "승자가 거의 안 물리는"
 * 전략(B)은 타이트하게, "승자도 깊이 물리는" 전략(D)은 느슨하게 → 전략별 승자를 덜 죽이면서 손실을 자른다.</p>
 *
 * <p><b>fail-closed(데이터 검증 후 적용)</b>: 승자 표본이 {@code minSamples} 미만이면 채택하지 않고 고정 손절로 유지.
 * 채택값은 [{@code minStopPct}, {@code maxStopPct}]로 클램프(과도 타이트/느슨 방지). {@code refreshMinutes} TTL 캐시.</p>
 *
 * @param enabled        적응형 사용 여부(기본 true). false면 고정 catastrophic-stop-pct.
 * @param minSamples     전략별 승자 채택 최소 표본(미만이면 고정값 fallback). 기본 30.
 * @param minStopPct     손절선 하한%(이보다 타이트 금지). 기본 3.0.
 * @param maxStopPct     손절선 상한%(이보다 느슨 금지). 기본 10.0.
 * @param refreshMinutes 손절선 재계산 캐시 주기(분). 기본 30.
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.adaptive-stop")
public record AdaptiveStopProperties(
        boolean enabled,
        int minSamples,
        double minStopPct,
        double maxStopPct,
        int refreshMinutes
) {
    public AdaptiveStopProperties {
        if (minSamples <= 0) minSamples = 30;
        if (minStopPct <= 0) minStopPct = 3.0;
        if (maxStopPct <= 0) maxStopPct = 10.0;
        if (refreshMinutes <= 0) refreshMinutes = 30;
    }
}
