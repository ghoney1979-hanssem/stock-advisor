package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 M — 조용한 추세지속 (섀도우, <b>거래량 무관</b>, 2026-08-29).
 *
 * <p><b>왜 만들었나</b>: {@code /universe-analysis}(전 종목 분모, 8거래일, 저가주·이상치 제외 후) 에서
 * 양의 lift가 나온 구간이 한 방향을 가리킨다 — <b>거래량배수 &lt;0.5(+0.66%p) · ret5d 0~5(+0.52) ·
 * 당일 0~2(+0.49) · 고가거리 −5~0(+0.36) · MA20 이격 0~3(+0.28)</b>. 반대로 급증(−0.57)·등락률 ≥10(−1.23)·
 * ret5d ≥10(−0.91)·MA20 이격 ≥10(−0.68)은 전부 음수다. 즉 <b>"관심을 받지 않은 채 추세 위에 조용히 얹혀
 * 있는 종목"</b>이 이기고, "관심을 받은 종목"이 진다 — 이 시스템이 일곱 번 독립적으로 확인한 방향과 같다.</p>
 *
 * <p>L(눌림 반전)과의 관계: L은 <b>MA20 아래·5일 약세</b>(되돌림을 산다), M은 <b>MA20 위·5일 소폭 강세</b>
 * (지속을 산다). 둘이 공유하는 건 <b>저거래량</b> 하나다. 둘의 성과가 갈리면 "저거래량이 좋은 것"인지
 * "반전이 좋은 것"인지가 분리된다 — 그게 이 전략의 두 번째 목적이다.</p>
 *
 * <p><b>⚠️ 근거의 두께</b>: 유니버스 lift 8거래일이고 lift는 반사실이 아니다(발굴 세션 교훈 2). 그래서
 * <b>대조군을 처음부터 붙인다</b> — {@code StrategyEvaluator.quietContinuationControl}이 판별 사유만
 * 강제 기록(L의 2026-08-26 정책과 동일 구조). ATR(저변동성) 조건은 L과 같은 이유로 넣지 않았다
 * (저베타 아티팩트 혐의).</p>
 *
 * <p><b>검증 설계</b>: {@code alerts()=false} + 화이트리스트 미포함 → 실주문 0. 판정은 거래일 10일 +
 * 상승일/하락일 양쪽 표본 + 정렬된 진입-대조군 edge로. L이 8/25 하루로 부풀었던 전례가 있으니
 * 클러스터 가드를 통과한 수치만 볼 것.</p>
 */
@Component
public class QuietContinuationStrategy implements TradingStrategy {

    private final boolean enabled;
    private final double maxVolumeRatio;   // 이 배수 이상이면 '관심 받은 종목'이라 대상 아님(정체성)
    private final double minMaDistPct;     // MA20 위 최소 이격(%) — 추세 위에 있을 것
    private final double maxMaDistPct;     // MA20 위 최대 이격(%) — 그 이상은 과열(이격 ≥10 lift 음수)
    private final double minRet5dPct;      // 최근 5일 수익률 하한 — 추세가 살아 있을 것
    private final double maxRet5dPct;      // 최근 5일 수익률 상한 — 이미 달린 종목 제외
    private final double minChangePct;     // 당일 등락률 하한 — 하락 중 진입 금지
    private final double maxChangePct;     // 당일 등락률 상한 — 급등 추격 금지
    private final double minScore;

    public QuietContinuationStrategy(
            @Value("${stockadvisor.signal.quiet-enabled:true}") boolean enabled,
            @Value("${stockadvisor.signal.quiet-max-volume-ratio:0.5}") double maxVolumeRatio,
            @Value("${stockadvisor.signal.quiet-min-ma-dist-pct:0.0}") double minMaDistPct,
            @Value("${stockadvisor.signal.quiet-max-ma-dist-pct:3.0}") double maxMaDistPct,
            @Value("${stockadvisor.signal.quiet-min-ret5d-pct:0.0}") double minRet5dPct,
            @Value("${stockadvisor.signal.quiet-max-ret5d-pct:5.0}") double maxRet5dPct,
            @Value("${stockadvisor.signal.quiet-min-change-pct:0.0}") double minChangePct,
            @Value("${stockadvisor.signal.quiet-max-change-pct:2.0}") double maxChangePct,
            @Value("${stockadvisor.signal.quiet-min-score:40.0}") double minScore) {
        this.enabled = enabled;
        this.maxVolumeRatio = maxVolumeRatio;
        this.minMaDistPct = minMaDistPct;
        this.maxMaDistPct = maxMaDistPct;
        this.minRet5dPct = minRet5dPct;
        this.maxRet5dPct = maxRet5dPct;
        this.minChangePct = minChangePct;
        this.maxChangePct = maxChangePct;
        this.minScore = minScore;
    }

    @Override
    public String name() {
        return "QUIET_CONTINUATION_M";
    }

    @Override
    public String label() {
        return "조용한 추세지속 (M)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (ctx.inverse()) return "INVERSE";                       // 인버스는 전용 전략(I)
        SignalResult s = ctx.signal();
        // ① 관심을 받지 않았을 것 — 정체성(preScreen과 같은 경계)
        if (s.volumeRatio() >= maxVolumeRatio) return "VOLUME_UP";
        // ② MA20 위, 그러나 과열 아닐 것 — 정체성(preScreen과 같은 경계)
        double dist = s.maDistPct();
        if (dist < minMaDistPct) return "BELOW_MA";
        if (dist > maxMaDistPct) return "EXTENDED";
        // ③ 5일 추세가 살아 있되 이미 달리지 않았을 것 — 판별 축(대조군 기록)
        if (s.ret5dPct() < minRet5dPct) return "WEAK_5D";
        if (s.ret5dPct() > maxRet5dPct) return "RAN_5D";
        // ④ 당일 하락 중·급등 추격 금지 — 판별 축(대조군 기록)
        if (s.changeRate() < minChangePct) return "DOWN_DAY";
        if (s.changeRate() > maxChangePct) return "CHASING";
        if (ctx.recScore() < minScore) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // ⚠️ 핵심 — 볼륨 게이트를 통과하면 "관심 받지 않은 종목"이라는 전제가 깨진다
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        // 전 종목 평가를 막는 사전필터 — 저거래량 + MA20 위 완만한 이격 후보만 비싼 평가로 넘긴다.
        return signal.volumeRatio() < maxVolumeRatio
                && signal.maDistPct() >= minMaDistPct
                && signal.maDistPct() <= maxMaDistPct;
    }

    @Override
    public boolean alerts() {
        return false;   // 순수 섀도우 — Discord 미발송
    }

    @Override
    public boolean tracksControl() {
        // false — 일괄 기록은 VOLUME_UP(급증 종목 전량)까지 남겨 대조군을 배로 불린다.
        // 판별 사유만 StrategyEvaluator.quietContinuationControl 이 강제 기록한다.
        return false;
    }
}
