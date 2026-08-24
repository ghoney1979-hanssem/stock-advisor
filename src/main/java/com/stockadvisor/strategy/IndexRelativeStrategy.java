package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 전략 D — 지수상대 역추세 (섀도우, 롱온리 "짝꿍 매매" 변형).
 *
 * <p>공매도가 어려운 개인 자기매매 환경에서 페어 트레이딩의 정신(상대가치 회귀)을 롱온리로 구현.
 * 숏 다리 대신 <b>해당 시장 지수</b>를 무포지션 기준선으로 삼아, 종목이 지수 대비 과하게 뒤처졌을 때
 * (잔차 = 종목등락률 − 지수등락률 ≤ -minGap) 상대 회귀를 노려 롱 진입한다.</p>
 *
 * <ul>
 *   <li>C(절대 역추세)와의 차이: C는 "당일 절대 -3~-12% 하락"만 보지만, D는 <b>지수 대비 상대 부진</b>을 본다.
 *       → 시장이 같이 빠져 동반 하락한 종목(회귀 약함)을 걸러내고, 시장 대비 혼자 뒤처진 종목(회귀 강함)만.
 *       상대 개념이라 방향성 의존이 낮아(준헤지) 약세·횡보장 보완에 적합.</li>
 *   <li>떨어지는 칼날 회피: 절대 하락(≤ -meanReversionMinDrop)까지 겹친 후보는 분봉 반등확인(reboundActive) 요구.
 *       (지수 대비만 부진하고 절대론 상승/횡보면 낙하 아님 → 반등확인 불요.)</li>
 *   <li>공통(volumeSpike·건전성·유동성·저가주·점수)은 {@code StrategyEvaluator}에서 이미 필터됨.</li>
 * </ul>
 *
 * <p>지수 등락률 미조회(시장 미상/조회 실패) 시 잔차 계산 불가 → 진입 안 함(NO_INDEX).</p>
 */
@Component
public class IndexRelativeStrategy implements TradingStrategy {

    private final SignalProperties props;
    private final Set<String> allowedRegimes;   // 진입 허용 국면(csv). 빈값=제약 없음(종전 동작)

    public IndexRelativeStrategy(SignalProperties props,
                                 @Value("${stockadvisor.signal.index-relative-allowed-regimes:BULL,NEUTRAL,BEAR}")
                                 String allowedRegimes) {
        this.props = props;
        this.allowedRegimes = parseRegimes(allowedRegimes);
    }

    private static Set<String> parseRegimes(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).map(v -> v.toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String name() {
        return "INDEX_RELATIVE_D";
    }

    @Override
    public String label() {
        return "지수상대 역추세 (D)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        Double index = ctx.marketChange();
        if (index == null) return "NO_INDEX";                 // 지수 미조회 → 잔차 계산 불가
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        double change = ctx.signal().changeRate();
        // 절대 폭락(정리매매·악재)은 상대부진과 무관하게 제외
        if (change <= -props.indexRelativeMaxDrop()) return "TOO_DEEP";
        // 핵심: 지수 대비 과소(잔차 ≤ -minGap)만 진입
        double residual = change - index;                     // 음수 = 지수보다 부진
        if (residual > -props.indexRelativeMinGap()) return "GAP";
        // 절대 하락(≤ -minDrop) 후보면 분봉 반등확인 요구(reboundActive는 절대하락 후보에만 계산됨).
        if (props.indexRelativeRequireRebound()
                && change <= -props.meanReversionMinDrop()
                && !ctx.signal().reboundActive()) return "NO_REBOUND";
        if (ctx.recScore() < props.indexRelativeMinScore()) return "SCORE";
        // 국면 제한(2026-08-24) — K와 같은 처방(제외가 아니라 국면 제한). 아래 regimeReject 주석 참조.
        String regimeReject = regimeReject(ctx.entryTrend(), allowedRegimes);
        if (regimeReject != null) return regimeReject;
        // 흐름↓ 스킵(마지막 게이트) — 다른 D 조건을 모두 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "D 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        // 흐름 미산출(개장 ~30분·조회실패)이면 null → 미적용(degrade open, 기존 흐름 가드와 동일 원칙).
        if (props.indexRelativeRequireRisingFlow()
                && ctx.indexMom30() != null
                && ctx.indexMom30() < 0.0) return "FLOW_DOWN";
        return null;
    }

    /**
     * 국면 허용 판정(순수) — 허용 목록 밖이면 {@code "REGIME_<국면>"}, 통과면 null.
     *
     * <p>근거(2026-08-24 `feature-mining` 국면 분해, 40일·close·대조군 커버리지 100%): D의 진입-대조군 edge가
     * <b>BULL +0.62%p(대조군 n=1,142) · NEUTRAL +1.51%p(n=869) ↔ BEAR −0.48%p(n=1,712)</b>로 갈린다.
     * BEAR에선 진입 net −1.83%(n=135)로 절대치도 최악이다. 대조군 표본이 시스템 내 최강급이라 근거가 강하다.</p>
     *
     * <p>⚠️ K(8/21)와 같은 교훈 — <b>풀링 edge(+0.84)만 보고 "D는 괜찮다"고 넘기면 BEAR 구간의 출혈을 놓친다.</b>
     * 제외가 아니라 국면 제한이 답인 전략이다(F·H는 세 국면 전부 음수라 제외가 답이었다).</p>
     *
     * <p>⚠️ <b>국면 미상(null)은 통과</b>(degrade open — 국면 산출 실패로 매매를 막지 않는다. K와 동일 원칙).
     * ⚠️ D는 K와 달리 <b>전일 확정 라벨이 아니라 현재(intraday 보정된) 라벨</b>을 쓴다 —
     * {@code trading.prior-day-regime-strategies}에 D는 없고, D는 개장 창 전용이 아니라 하루 종일 도는 전략이라
     * 지금의 국면이 판단 대상이다.</p>
     */
    static String regimeReject(String entryTrend, Set<String> allowed) {
        if (allowed.isEmpty() || entryTrend == null) return null;
        if (allowed.contains(entryTrend)) return null;
        return "REGIME_" + entryTrend;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;   // 공시 무관, 전 종목 스캔
    }

    @Override
    public boolean alerts() {
        return false;   // 검증 단계: 조용한 섀도우(가상매수/성과기록은 됨, Discord 알림은 미발송). 검증 후 켜기.
    }
}
