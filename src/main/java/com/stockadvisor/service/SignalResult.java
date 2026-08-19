package com.stockadvisor.service;

/**
 * 종목의 현재 시장 신호 원시 지표. (전략 판단은 각 전략 빈이 이 지표를 해석해 수행)
 *
 * @param volumeRatio    당일/시간보정평균 거래량 배수
 * @param changeRate     당일 등락률(%)
 * @param closePrice     당일 현재가(종가) — 가상 매수가
 * @param todayVolume    당일 누적 거래량
 * @param volumeSpike    거래량 급증 충족 여부(volumeRatio ≥ 설정 배수)
 * @param freshActive    [A] 분봉 신선도+활성도 충족(상승추이 후보일 때만 계산, 아니면 false)
 * @param reboundActive  [C] 당일 하락이어도 최근 분봉이 반등(모멘텀↑·거래활발)인지 — 역추세 후보일 때만 계산, 아니면 false
 * @param priorHigh      [E] 직전 breakoutLookback 거래일(오늘 제외) 최고가(고가 우선, 없으면 종가). 신고가 돌파 판정용. 데이터 없으면 0.
 * @param maCrossUp      [F] MA20 상향 돌파 이벤트 — 직전 확정종가 ≤ MA20 이고 현재가 > MA20(오늘 처음 위로 뚫음). 추세추종 트리거. 데이터 부족 시 false.
 * @param rsiCrossUp     [G] RSI(14) 과매도(30) 상향 돌파 이벤트 — 어제 RSI ≤ 30, 오늘 RSI > 30(과매도서 회복). 역추세 트리거. 데이터 부족 시 false.
 * @param squeezeBreakout [H] 변동성 수축 돌파 — 전일이 최근 7일 중 일중변동폭 최소(NR7) + 현재가 > 전일 고가(수축 후 상방 팽창). 데이터 부족 시 false.
 * @param atrPct          진입 종목 ATR% (변동성, 일봉 TR 평균/현재가×100). 셋업 분석용. 데이터 없으면 0.
 * @param distFromHighPct 직전 고가(priorHigh) 대비 현재가 거리%((close−priorHigh)/priorHigh×100, 보통 ≤0). 데이터 없으면 0.
 * @param ret5dPct        최근 5거래일 수익률%((close−5일전종가)/5일전종가×100). 단기 모멘텀 상태. 데이터 없으면 0.
 * @param maBreakoutFresh [F] MA돌파 후보의 분봉 신선도+활성도 충족(maCrossUp 후보일 때만 계산, 아니면 false).
 *                        F 흐름확인 필터(SIGNAL_MA_TREND_REQUIRE_FRESH)가 "죽은 돌파" 회피에 사용.
 * @param maDistPct       [F] 현재가의 MA20 대비 이격%((close−ma20)/ma20×100). 돌파 강도(버퍼) 판정용. maCrossUp이면 보통 소폭 +. 데이터 없으면 0.
 */
public record SignalResult(
        double volumeRatio,
        double changeRate,
        long closePrice,
        long todayVolume,
        boolean volumeSpike,
        boolean freshActive,
        boolean reboundActive,
        long priorHigh,
        boolean maCrossUp,
        boolean rsiCrossUp,
        boolean squeezeBreakout,
        double atrPct,
        double distFromHighPct,
        double ret5dPct,
        double gapPct,          // 개장갭%((오늘시가−전일종가)/전일종가×100). 개장갭 전략(K)용. 데이터 없으면 0.
        boolean maBreakoutFresh,
        double maDistPct,
        // [H] 스퀴즈 돌파 후보의 분봉 신선도+활성도 충족(squeezeBreakout 후보일 때만 계산, 아니면 false).
        // H 돌파확인 필터(SIGNAL_SQUEEZE_REQUIRE_CONFIRM)가 "죽은/페이드 돌파" 회피에 사용(F maBreakoutFresh와 동일 취지).
        boolean squeezeBreakoutFresh,
        // 직전 거래일 확정 종가(일봉 rows[1]) — 이미 조회한 일봉에서 꺼내므로 추가 KIS 0.
        // UniverseSnapshotService가 "전일 스냅샷의 종가 타깃"을 정확히 채우는 데 쓴다(근사 관측가 대체). 없으면 0.
        long prevClose,
        // prevClose 가 속한 영업일(yyyyMMdd) — 종가를 채울 때 스냅샷 일자와 일치하는지 검증하는 키.
        // 일봉이 밀려도(장전·휴장) 엉뚱한 날짜에 쓰이지 않게 한다. 없으면 null.
        String prevBusinessDate
) {
    /** 기존 11-인자 호환 — 셋업/갭/MA/H feature 0 기본. 테스트·구 호출 무변경 유지용. */
    public SignalResult(double volumeRatio, double changeRate, long closePrice, long todayVolume,
                        boolean volumeSpike, boolean freshActive, boolean reboundActive, long priorHigh,
                        boolean maCrossUp, boolean rsiCrossUp, boolean squeezeBreakout) {
        this(volumeRatio, changeRate, closePrice, todayVolume, volumeSpike, freshActive, reboundActive,
                priorHigh, maCrossUp, rsiCrossUp, squeezeBreakout, 0, 0, 0, 0, false, 0, false, 0, null);
    }

    /** 기존 15-인자 호환(gapPct까지) — MA/H feature 0 기본. 테스트·구 호출 무변경 유지용. */
    public SignalResult(double volumeRatio, double changeRate, long closePrice, long todayVolume,
                        boolean volumeSpike, boolean freshActive, boolean reboundActive, long priorHigh,
                        boolean maCrossUp, boolean rsiCrossUp, boolean squeezeBreakout,
                        double atrPct, double distFromHighPct, double ret5dPct, double gapPct) {
        this(volumeRatio, changeRate, closePrice, todayVolume, volumeSpike, freshActive, reboundActive,
                priorHigh, maCrossUp, rsiCrossUp, squeezeBreakout, atrPct, distFromHighPct, ret5dPct, gapPct, false, 0, false, 0, null);
    }

    /** 기존 17-인자 호환(maDistPct까지) — H feature false 기본. 테스트·구 호출 무변경 유지용. */
    public SignalResult(double volumeRatio, double changeRate, long closePrice, long todayVolume,
                        boolean volumeSpike, boolean freshActive, boolean reboundActive, long priorHigh,
                        boolean maCrossUp, boolean rsiCrossUp, boolean squeezeBreakout,
                        double atrPct, double distFromHighPct, double ret5dPct, double gapPct,
                        boolean maBreakoutFresh, double maDistPct) {
        this(volumeRatio, changeRate, closePrice, todayVolume, volumeSpike, freshActive, reboundActive,
                priorHigh, maCrossUp, rsiCrossUp, squeezeBreakout, atrPct, distFromHighPct, ret5dPct, gapPct,
                maBreakoutFresh, maDistPct, false, 0, null);
    }

    /** 기존 18-인자 호환(squeezeBreakoutFresh까지) — 전일종가 미상(0/null) 기본. 테스트·구 호출 무변경 유지용. */
    public SignalResult(double volumeRatio, double changeRate, long closePrice, long todayVolume,
                        boolean volumeSpike, boolean freshActive, boolean reboundActive, long priorHigh,
                        boolean maCrossUp, boolean rsiCrossUp, boolean squeezeBreakout,
                        double atrPct, double distFromHighPct, double ret5dPct, double gapPct,
                        boolean maBreakoutFresh, double maDistPct, boolean squeezeBreakoutFresh) {
        this(volumeRatio, changeRate, closePrice, todayVolume, volumeSpike, freshActive, reboundActive,
                priorHigh, maCrossUp, rsiCrossUp, squeezeBreakout, atrPct, distFromHighPct, ret5dPct, gapPct,
                maBreakoutFresh, maDistPct, squeezeBreakoutFresh, 0, null);
    }
}
