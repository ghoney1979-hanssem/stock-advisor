package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시장 국면 엔진 설정 ({@code stockadvisor.market-regime}).
 *
 * <p>지수 일봉 전용 API 의존을 피하려 <b>지수 추종 ETF를 프록시</b>로 쓴다(검증된 일별시세 {@code fetchDailyPrices} 재사용).
 * KOSPI≈KODEX 200(069500), KOSDAQ≈KODEX 코스닥150(229200). 추후 진짜 지수 일봉 API로 교체 가능.</p>
 *
 * @param kospiProxyCode  KOSPI 추세 프록시 종목코드(기본 069500 KODEX 200)
 * @param kosdaqProxyCode KOSDAQ 추세 프록시 종목코드(기본 229200 KODEX 코스닥150)
 * @param maPeriod        추세 이동평균 기간(일). 기본 20.
 * @param slopeLookback   MA 기울기 측정 기간(일 전 대비). 기본 5.
 * @param volPeriod       실현변동성 측정 기간(일간수익률 개수). 기본 20.
 * @param volHighPct      일간수익률 표준편차(%)가 이 값 이상이면 고변동. 기본 2.0.
 * @param volLowPct       일간수익률 표준편차(%)가 이 값 미만이면 저변동. 기본 1.0.
 * @param refreshMinutes  국면 재계산 캐시 주기(분). 일봉 기반이라 자주 안 바뀜. 기본 60.
 */
@ConfigurationProperties(prefix = "stockadvisor.market-regime")
public record MarketRegimeProperties(
        String kospiProxyCode,
        String kosdaqProxyCode,
        int maPeriod,
        int slopeLookback,
        int volPeriod,
        double volHighPct,
        double volLowPct,
        int refreshMinutes
) {
    public MarketRegimeProperties {
        if (kospiProxyCode == null || kospiProxyCode.isBlank()) kospiProxyCode = "069500";
        if (kosdaqProxyCode == null || kosdaqProxyCode.isBlank()) kosdaqProxyCode = "229200";
        if (maPeriod <= 1) maPeriod = 20;
        if (slopeLookback <= 0) slopeLookback = 5;
        if (volPeriod <= 1) volPeriod = 20;
        if (volHighPct <= 0) volHighPct = 2.0;
        if (volLowPct <= 0) volLowPct = 1.0;
        if (refreshMinutes <= 0) refreshMinutes = 60;
    }
}
