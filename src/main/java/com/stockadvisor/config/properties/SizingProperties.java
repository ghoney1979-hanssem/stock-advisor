package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 변동성기반(ATR) 포지션 사이징 설정 (레이어 3.1, {@code stockadvisor.trading.sizing}).
 *
 * <p>고정 "순자산×maxOrderPct" 대신 <b>위험예산(순자산 대비 %)을 ATR(평균진폭)로 나눠</b> 수량을 정한다.
 * 변동성 큰 종목은 적게·작은 종목은 많이 사서 거래당 리스크(예상 손실폭)를 균등화. 단 1주문 상한(maxOrderPct)을
 * 천장으로 둬 절대 초과하지 않는다. ATR 미산출/비활성이면 고정 사이징으로 fallback.</p>
 *
 * <p>수량 = min( 순자산×riskPerTradePct% / (atrMult×ATR) , 순자산×maxOrderPct% / 가격 ).</p>
 *
 * @param atrEnabled      ATR 사이징 사용 여부(기본 true). false면 고정 maxOrderPct 사이징.
 * @param atrPeriod       ATR 평균 기간(일). 기본 14.
 * @param atrMult         손절폭 추정 배수(stop ≈ atrMult×ATR). 클수록 보수적(수량↓). 기본 2.0.
 * @param riskPerTradePct 거래당 위험예산 = 순자산 × 이 비율(%). 기본 0.5(순자산의 0.5%만 리스크).
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.sizing")
public record SizingProperties(
        boolean atrEnabled,
        int atrPeriod,
        double atrMult,
        double riskPerTradePct
) {
    public SizingProperties {
        if (atrPeriod <= 1) atrPeriod = 14;
        if (atrMult <= 0) atrMult = 2.0;
        if (riskPerTradePct <= 0) riskPerTradePct = 0.5;
    }
}
