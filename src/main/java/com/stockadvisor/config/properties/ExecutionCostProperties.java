package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 체결비용(슬리피지·유동성) 모델 설정 (레이어 4, {@code stockadvisor.cost.execution}).
 *
 * <p>위탁수수료·거래세(고정 {@code round-trip-pct} 0.18%)와 별개로, <b>호가 스프레드(슬리피지)</b>와
 * <b>유동성</b>을 반영해 net 수치를 현실화한다. 호가창 API 없이 <b>KRX 호가단위(tick) 기반</b>으로 스프레드를
 * 추정(가격대별 tick/가격), 거래대금으로 유동성 필터.</p>
 *
 * @param enabled                 비용 모델 사용 여부(기본 true). false면 슬리피지 0·필터 통과(기존 동작).
 * @param spreadTicks             왕복 스프레드를 몇 tick으로 볼지(기본 1.0 — 1틱 왕복 횡단). 소형주는 ↑.
 * @param baseSlippagePct         스프레드로 안 잡히는 추가 슬리피지(시장충격 등) 왕복 %. 기본 0.0.
 * @param minTurnoverKrw          1일 거래대금(원) 하한 — 미만이면 거래불가(유동성 필터). 기본 5억.
 * @param maxRoundTripSlippagePct 추정 왕복 슬리피지(%)가 이 값 초과면 거래불가. 기본 1.0.
 * @param maxImpactPct            시장충격 상한(%) — 매수 주문이 최우선 매도호가 대비 이 값 넘게 가격을 밀면 그만큼 수량 캡(호가 잔량 walk). 0이면 비활성. 기본 0.5.
 */
@ConfigurationProperties(prefix = "stockadvisor.cost.execution")
public record ExecutionCostProperties(
        boolean enabled,
        double spreadTicks,
        double baseSlippagePct,
        long minTurnoverKrw,
        double maxRoundTripSlippagePct,
        double maxImpactPct
) {
    public ExecutionCostProperties {
        if (spreadTicks <= 0) spreadTicks = 1.0;
        if (baseSlippagePct < 0) baseSlippagePct = 0.0;
        if (minTurnoverKrw < 0) minTurnoverKrw = 500_000_000L;
        if (maxRoundTripSlippagePct <= 0) maxRoundTripSlippagePct = 1.0;
        if (maxImpactPct < 0) maxImpactPct = 0.5;
    }
}
