package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 국면연동 리스크 관리 설정 (레이어 3, {@code stockadvisor.trading.risk}).
 *
 * <p>"하락장 리스크↓"의 핵심 — 시장 국면({@link com.stockadvisor.service.MarketRegimeService})에 따라
 * <b>① 총노출 상한</b>(약세·고변동일수록 가용자본 비중↓)과 <b>② 서킷브레이커</b>(지수 급락 시 신규진입 중단 +
 * 보유 청산 가속)를 강제한다. 전략 성과게이트(개별 전략 검증)와 직교 — 이건 포트폴리오 전체 리스크.</p>
 *
 * @param enabled               리스크 관리 사용 여부(기본 true). false면 노출/서킷 미적용.
 * @param bullExposurePct       강세장 총노출 상한(순자산 대비 %). 기본 100.
 * @param neutralExposurePct    중립장 총노출 상한(%). 기본 60.
 * @param bearExposurePct       약세장 총노출 상한(%). 기본 30.
 * @param highVolExposureMult   고변동 국면이면 위 상한에 곱하는 계수(0~1). 기본 0.5.
 * @param crashHaltPct          지수(코스피/코스닥) 장중 등락률이 -이 값(%) 이하면 서킷브레이커 발동(신규진입 중단 + 청산 가속). 0이면 비활성. 기본 3.0.
 * @param maxPositionsPerSector 한 업종(섹터)당 최대 동시 보유 종목 수 — 초과 진입 차단(동반급락 분산). 0이면 무제한. 기본 3.
 * @param catastrophicStopPct   개별 포지션 손절 하한(%) — 매수가 대비 -이 값(%) 이하로 떨어지면 보유시간 무관 즉시 청산.
 *                              ⚠️ 튜닝용 SL이 아니라 <b>재난 방지용 넓은 바닥</b>(데이터 기반 청산은 별개로 유지). 0이면 비활성. 기본 7.0.
 * @param reboundPct            서킷 재개 임계 — 발동(≤-crashHaltPct) 후 지수가 <b>장중 저점 대비 +이 값(%p) 반등</b>하면 재개.
 *                              절대수준(-1%) 복귀를 안 기다리고 저점 반등을 신호로 봄(예 -6%→-4%면 +2%p 반등 → 재개). 기본 2.0.
 * @param breadthRiskoffAdvPct  시장폭(breadth) 리스크오프 — 해당 시장 <b>상승종목 비율(%)이 이 값 미만</b>이면(아래 중앙값 조건과 AND)
 *                              신규진입 차단. 지수(시총가중)가 못 보는 광범위 투매 감지(2026-07-13 실측: 코스닥 지수 서킷 미발동인데
 *                              중앙 종목 -4.4% 붕괴). <b>진입 차단 전용 — 강제청산 없음</b>(청산 가속은 지수 서킷만). 0이면 비활성. 기본 15.0.
 * @param breadthRiskoffMedianPct 시장폭 리스크오프의 중앙 등락률 조건 — 중앙값이 <b>-이 값(%) 이하</b>일 때만(위 상승비율과 AND, 오발동 방지). 기본 3.0.
 * @param openingExposureCapPct 개장 창({@code openingWindowMinutes}) 동안의 총노출 상한(순자산 대비 %) — 국면 상한과 <b>min</b>으로 결합.
 *                              <b>0이면 비활성(기존 동작)</b>. 2026-08-14 실측 근거: 총노출 한도(순자산×50%)가 <b>09:07:44에 100% 소진</b>돼
 *                              LIVE 15건이 전부 09:01~09:24 27분 창에 몰렸고, 그 구간이 갭업 고점이라 −188,665원(계좌 −1.78%)을 냈다.
 *                              이후 발생한 진입 신호 30건은 예산 소진으로 전량 스킵 — 오후 회복 구간에 참여하지 못했다.
 *                              리스크 예산이 "선착순"으로 배분돼 개장 갭에 전량 베팅되는 구조를 시간 분산으로 완화한다.
 * @param openingWindowMinutes  개장(09:00, KRX 정규장 개시) 이후 위 상한을 적용할 분. 기본 30.
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.risk")
public record RiskProperties(
        boolean enabled,
        double bullExposurePct,
        double neutralExposurePct,
        double bearExposurePct,
        double highVolExposureMult,
        double crashHaltPct,
        int maxPositionsPerSector,
        double catastrophicStopPct,
        double reboundPct,
        double breadthRiskoffAdvPct,
        double breadthRiskoffMedianPct,
        double openingExposureCapPct,
        int openingWindowMinutes
) {
    public RiskProperties {
        if (bullExposurePct <= 0) bullExposurePct = 100;
        if (neutralExposurePct <= 0) neutralExposurePct = 60;
        if (bearExposurePct <= 0) bearExposurePct = 30;
        if (highVolExposureMult <= 0) highVolExposureMult = 0.5;
        if (crashHaltPct < 0) crashHaltPct = 3.0;
        if (maxPositionsPerSector < 0) maxPositionsPerSector = 3;
        if (catastrophicStopPct < 0) catastrophicStopPct = 7.0;
        if (reboundPct <= 0) reboundPct = 2.0;
        if (breadthRiskoffAdvPct < 0) breadthRiskoffAdvPct = 15.0;
        if (breadthRiskoffMedianPct <= 0) breadthRiskoffMedianPct = 3.0;
        if (openingExposureCapPct < 0) openingExposureCapPct = 0;      // 0=비활성(기본, 기존 동작 유지)
        if (openingWindowMinutes <= 0) openingWindowMinutes = 30;
    }
    // ⚠️ @ConfigurationProperties 레코드에 보조(호환) 생성자를 두면 Spring이 생성자 바인딩 대신
    //    JavaBean 바인딩으로 폴백해 기동 실패(NoSuchMethodException: <init>()) — 생성자는 canonical 하나만 유지.
}
