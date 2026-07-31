package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.stereotype.Component;

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

    public IndexRelativeStrategy(SignalProperties props) {
        this.props = props;
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
        // 흐름↓ 스킵(마지막 게이트) — 다른 D 조건을 모두 통과한 후보만 흐름으로 최종 판정.
        // 그래야 FLOW_DOWN 대조군 = "D 조건 다 만족했으나 흐름↓" → ENTERED와 직접 비교 가능(필터 forward 검증).
        // 흐름 미산출(개장 ~30분·조회실패)이면 null → 미적용(degrade open, 기존 흐름 가드와 동일 원칙).
        if (props.indexRelativeRequireRisingFlow()
                && ctx.indexMom30() != null
                && ctx.indexMom30() < 0.0) return "FLOW_DOWN";
        return null;
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
