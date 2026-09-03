package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 P — 눌림목 멀티데이 트레일(2026-09-03, 사용자 지정).
 *
 * <p><b>진입은 C와 완전히 같고 청산만 다르다.</b> 그래서 조건을 복제하지 않고 {@link MeanReversionStrategy}가
 * 읽는 것과 <b>같은 프로퍼티</b>({@code mean-reversion-*})를 그대로 읽는다 — 복제하면 나중에 C의 임계를 바꿀 때
 * 둘이 조용히 갈라지고, 그러면 "청산만 다른 대조 실험"이라는 이 전략의 존재 이유가 사라진다.</p>
 *
 * <p><b>청산</b>(코드가 아니라 {@code PositionExitService}의 멀티데이 분기): 수익률 +{@code arm-pct}(5%)에
 * 한 번이라도 도달한 뒤 <b>고점 대비 −{@code drop-pct}(2%)</b>면 매도, 미발동이면 <b>{@code max-hold-days}
 * (15거래일)</b> 백스톱.</p>
 *
 * <p><b>근거</b>(2026-09-03, C의 실제 일봉 경로 {@code outcome_daily_mark} 완주 코호트 155건 시뮬, net):
 * 요청 규칙 <b>+2.40%</b>인데 최대기여일(20260626, 점유 86.5%) 제외 <b>+0.25%</b> · ±20% 절사 후 제외
 * <b>+2.07%</b>. 같은 표본에서 단순보유는 D+1 +5.57%→<b>−0.13%</b> · D+5 +3.80%→<b>−5.30%</b> ·
 * D+15 −2.85%→<b>−3.12%</b>로 <b>전부 부호가 뒤집힌다</b> — 즉 <b>네 방식 중 클러스터 보정을 견디는 건 이것뿐</b>이고,
 * 트레일링이 6/26 의존을 걷어내는 역할을 한다.</p>
 *
 * <p>⚠️ <b>그러나 6/26을 빼면 표본이 21건·12거래일뿐</b>이고 개별 값이 −20.4%~+12.0%로 요동친다.
 * 이 시스템 기준으로는 <b>판정 불가에 가깝다</b>. ⚠️ 그리고 <b>C의 선정 자체는 대조군에 진 기록</b>이 있다
 * (2026-08-25 LIVE 제외: {@code DROP_RANGE} edge +0.52 · {@code NO_REBOUND} +0.83 = C가 거른 게 C가 산 것보다
 * 나았다). 이 전략이 이긴다면 그건 <b>선정이 좋아서가 아니라 청산이 구제해서</b>일 가능성이 크고, 그 경우
 * 같은 청산을 다른 선정에 붙이는 게 다음 수순이다.</p>
 *
 * <p>⚠️ 8/28 멀티데이 백테스트(10년·111코호트)에서 <b>같은 청산 규칙이 −0.39~−0.99%p/월로 손해</b>였다.
 * 단 그때 선정은 F-Score/모멘텀이었고 <b>C가 아니다</b> — 위 시뮬과 모순이 아니라 <b>선정에 따라 갈린다</b>는 뜻이다.</p>
 */
@Component
public class MultidayReversionStrategy implements TradingStrategy {

    /** C와 같은 진입 임계를 쓴다(복제 금지 — 갈라지면 대조 실험이 깨진다). */
    private final SignalProperties props;
    private final boolean enabled;

    public MultidayReversionStrategy(SignalProperties props,
                                     @Value("${stockadvisor.signal.multiday-reversion-enabled:false}")
                                     boolean enabled) {
        this.props = props;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "MULTIDAY_REVERSION_P";
    }

    @Override
    public String label() {
        return "눌림목 멀티데이 트레일 (P)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    /** C({@link MeanReversionStrategy#rejectReason})와 동일한 판정 — 사유 문자열까지 같게 둬 대조군 비교가 맞물리게 한다. */
    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        double change = ctx.signal().changeRate();
        boolean inDropRange = change <= -props.meanReversionMinDrop()
                && change >= -props.meanReversionMaxDrop();
        if (!inDropRange) return "DROP_RANGE";
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        if (props.meanReversionRequireRebound() && !ctx.signal().reboundActive()) return "NO_REBOUND";
        if (ctx.recScore() < props.meanReversionMinScore()) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    /**
     * 조용한 섀도우 — 신규 전략은 알림을 내지 않는다(측정-먼저).
     * ⚠️ LIVE 주문이 접수되면 {@code StrategyEvaluator}의 "실주문 시 신호 알림 강제" 규칙이 별도로 발송한다.
     */
    @Override
    public boolean alerts() {
        return false;
    }
}
