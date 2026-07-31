package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 업종 상대평가 설정 ({@code stockadvisor.valuation.sector}).
 *
 * <p>PER/PBR을 절대기준이 아니라 <b>해당 종목 업종의 중앙값 대비 비율</b>로 평가한다 — 테크/바이오처럼 업종 자체가
 * 고PER인 특성을 흡수(업종 평균보다 싸면 가점). 업종 중앙값은 {@code SectorValuationService}가 워치리스트 시세를
 * 집계해 산출(장전 배치 + 수동). 업종 표본이 {@code minStocks} 미만이면 신뢰 불가 → 절대기준으로 fallback.</p>
 *
 * @param enabled    업종 상대평가 사용 여부(기본 true). false면 항상 절대기준.
 * @param minStocks  업종 중앙값 신뢰 최소 종목 수(미만이면 그 업종은 절대기준 fallback). 기본 5.
 * @param goodRatio  (종목 PER/PBR ÷ 업종 중앙값)이 이 값 이하면 만점(업종 대비 충분히 쌈). 기본 0.8.
 * @param maxRatio   이 비율 이상이면 바닥점(업종 대비 비쌈). 기본 1.5.
 */
@ConfigurationProperties(prefix = "stockadvisor.valuation.sector")
public record SectorValuationProperties(
        boolean enabled,
        int minStocks,
        double goodRatio,
        double maxRatio
) {
    public SectorValuationProperties {
        if (minStocks < 1) minStocks = 5;
        if (goodRatio <= 0) goodRatio = 0.8;
        if (maxRatio <= goodRatio) maxRatio = Math.max(goodRatio + 0.1, 1.5);
    }
}
