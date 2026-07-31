package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 신호 판정 기준 설정.
 *
 * @param lookbackDays              거래량 평균 산정 기간(일)
 * @param volumeMultiplier          거래량 급증 배수 (당일 거래량 ≥ 시간보정 평균 × 이 값)
 * @param minChangeRate             최소 당일 등락률(%) — 상승추이 판정
 * @param minOpinionScore           알림 대상 최소 추천 점수(중립 이상)
 * @param observationWindow         공시 감지 후 신호 재평가 유효 기간
 * @param intradayWindowMinutes     분봉 신선도/활성도 판정 윈도우(분)
 * @param intradayMinMomentum       최근 윈도우 최소 모멘텀(%) — 지금도 상승 중인지
 * @param intradayMinActivityRatio  최근 분당 거래량 / 당일 평균 분당 거래량 최소비 — 지금도 활발한지
 * @param volumeLeadingMinChange    [전략B] 진입 최소 당일 등락률(%) — 이 값 미만(하락 중)은 제외(분산 회피). 기본 0.0(하락 컷)
 * @param volumeLeadingMaxChange    [전략B] 거래량 선행 판정용 가격 횡보 상한(%) — 이 값 초과(이미 급등)는 제외
 * @param volumeLeadingInverseMaxChange [전략B·인버스 전용] 인버스 ETF는 하락장에 이미 몇 % 급등한 채 스캔되므로 일반 상한(1%)으론 ALREADY_UP 탈락.
 *                                  인버스에 한해 이 완화된 상한(%)을 적용해 포착률↑(하한/DIRECTION_DOWN 컷은 동일 유지). 기본 8.0
 * @param meanReversionMinDrop      [전략C] 역추세 진입용 최소 당일 하락폭(%, 양수로 입력)
 * @param meanReversionMaxDrop      [전략C] 역추세 허용 최대 하락폭(%) — 초과 폭락은 제외(정리매매 등)
 * @param meanReversionMinScore     [전략C] 진입 최소 추천 점수 — 승자/패자 분석상 점수 높을수록 수익(기본 55, minOpinionScore보다 엄격)
 * @param meanReversionRequireRebound [전략C] 분봉 반등확인 요구 여부 — 당일 하락이어도 최근 분봉이 반등(모멘텀↑·거래활발)할 때만 진입(떨어지는 칼날 회피)
 * @param indexRelativeMinGap       [전략D] 지수상대 역추세 진입 임계 — 잔차(종목등락률−지수등락률) ≤ -이값(%p)이면 진입(지수 대비 과소·상대 회귀 노림, 롱온리)
 * @param indexRelativeMaxDrop      [전략D] 당일 절대 하락폭 이 값(%) 초과 폭락은 제외(정리매매·악재)
 * @param indexRelativeMinScore     [전략D] 진입 최소 추천 점수(건전성)
 * @param indexRelativeRequireRebound [전략D] 절대하락(≤-meanReversionMinDrop) 후보면 분봉 반등확인 요구(떨어지는 칼날 회피)
 * @param breakoutLookback          [전략E] 신고가 돌파 판정용 직전 최고가 산정 기간(거래일). 오늘 제외 이 기간의 고가 최댓값 초과면 돌파
 * @param breakoutMinScore          [전략E] 진입 최소 추천 점수(건전성)
 * @param breakoutBufferPct         [전략E] 돌파 버퍼(%) — 현재가 ≥ 직전최고가 ×(1+이값/100)일 때만 진입(0=신고가 갱신 즉시, 노이즈 억제용 여유)
 * @param minPrice                  저가주 제외 기준(원) 미만이면 진입 안 함
 * @param sessionStart              연속매매 시작(HH:mm) — 이전(동시호가)엔 평가 안 함
 * @param sessionEnd                연속매매 종료(HH:mm) — 이후(마감 동시호가)엔 평가 안 함
 * @param controlTracking           대조군 추적 — 거래량 급증했지만 전략조건/점수서 탈락한 종목도 (알림·주문 없이) 수익률 기록.
 *                                  진입 vs 미진입 비교로 필터 품질 검증/개선. 기본 true. ⚠️ 추적 표본 증가(KIS 부하↑).
 */
@ConfigurationProperties(prefix = "stockadvisor.signal")
public record SignalProperties(
        int lookbackDays,
        double volumeMultiplier,
        double minChangeRate,
        double minOpinionScore,
        Duration observationWindow,
        int intradayWindowMinutes,
        double intradayMinMomentum,
        double intradayMinActivityRatio,
        double volumeLeadingMinChange,
        double volumeLeadingMaxChange,
        double volumeLeadingInverseMaxChange,
        double meanReversionMinDrop,
        double meanReversionMaxDrop,
        double meanReversionMinScore,
        boolean meanReversionRequireRebound,
        double indexRelativeMinGap,
        double indexRelativeMaxDrop,
        double indexRelativeMinScore,
        boolean indexRelativeRequireRebound,
        int breakoutLookback,
        double breakoutMinScore,
        double breakoutBufferPct,
        long minPrice,
        String sessionStart,
        String sessionEnd,
        boolean controlTracking
) {
}
