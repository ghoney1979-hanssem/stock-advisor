package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 L — 눌림 반전 (섀도우, <b>거래량 무관</b>, 2026-08-21).
 *
 * <p><b>왜 만들었나</b>: 기존 10개 전략은 A(공시상승)·B(거래량선행)·E/F/H(돌파)·K(갭업)처럼 대부분
 * <b>모멘텀·급증을 사는</b> 방향이고, C/D/G/J의 역추세도 "급증한 종목 중에서" 고른다. 그런데 네 갈래의
 * 독립 증거가 <b>이 시장에서는 단기 반전이 우세</b>함을 가리킨다:</p>
 * <ol>
 *   <li>유니버스 횡단면(전 종목 분모): 거래량 급증 lift <b>−0.20%p</b>(m90, 4거래일 전부 음수),
 *       반대로 <b>MA20 아래·저거래량·ret5d 낮음</b>이 양의 lift.</li>
 *   <li>거래량배수와 수익의 단조 역방향: 2~4 −0.78% · 8~15 −1.42% · ≥15 −2.65%.</li>
 *   <li>B의 볼륨 하한 원복 근거였던 대조군 `WEAK_VOLUME` <b>+0.33%</b> &gt; 진입분 −0.50%.</li>
 *   <li>전역 진입 필터 3종(등락률 ≥10%·거래량 ≥15배·초소형주)이 전부 edge 음수로 검증됨 —
 *       "이미 오른 걸 사는 것"이 전략 종류와 무관하게 졌다.</li>
 * </ol>
 *
 * <p><b>가설</b>: 20일선 <b>아래</b>로 눌린 + <b>거래량이 붙지 않은</b> + 최근 5일 약세인 종목의 되돌림.
 * 즉 기존 파이프라인이 사는 것의 <b>거울상</b>이다. 이 전략이 이기면 스크리닝 방향 자체를 재검토해야 하고,
 * 지면 "반전 우세"라는 위 네 증거가 무조건 매수 평균의 착시였다는 뜻이라 그것대로 결론이 선다.</p>
 *
 * <p><b>⚠️ 검증 설계</b>: {@code alerts()=false} 섀도우 + 화이트리스트 미포함 → <b>실주문 0</b>.
 * {@code tracksControl()=false}인데, 이 전략은 대조군이 따로 필요 없다 — <b>{@code UniverseSnapshot}이
 * 이미 전 종목 분모</b>라 {@code /universe-analysis}로 같은 조건 구간의 base rate와 직접 비교된다
 * (오늘 발굴 세션의 교훈: 반사실은 lift가 아니라 대조군/분모로만 잰다).</p>
 *
 * <p>⚠️ <b>과적합 경계</b>: 유니버스 lift는 4거래일 표본이고 하락 편향 구간이라 저베타 효과와 완전히
 * 분리되지 않았다. 그래서 그중 <b>ATR(저변동성) 조건은 일부러 넣지 않았다</b> — 저ATR lift가 폭락일에
 * 최대(+0.61)라 베타 아티팩트 혐의가 가장 짙다. 위 증거 ②③④는 40거래일 표본이라 그쪽에 무게를 뒀다.</p>
 */
@Component
public class ReversalStrategy implements TradingStrategy {

    private final boolean enabled;
    private final double maxVolumeRatio;   // 이 배수 이상이면 '급증'이라 대상 아님(모멘텀의 거울상)
    private final double minPullbackPct;   // 20일선 아래 최소 이격(%) — 이만큼은 눌려야 함
    private final double maxPullbackPct;   // 20일선 아래 최대 이격(%) — 그 이상은 추세붕괴로 보고 제외
    private final double maxRet5dPct;      // 최근 5일 수익률 상한 — 이미 오른 종목 제외
    private final double maxChangePct;     // 당일 등락률 상한 — 급등 추격 방지
    private final double maxDropPct;       // 당일 낙폭 하한 — 떨어지는 칼날 회피
    private final double minScore;

    public ReversalStrategy(@Value("${stockadvisor.signal.reversal-enabled:true}") boolean enabled,
                            @Value("${stockadvisor.signal.reversal-max-volume-ratio:1.0}") double maxVolumeRatio,
                            @Value("${stockadvisor.signal.reversal-min-pullback-pct:3.0}") double minPullbackPct,
                            @Value("${stockadvisor.signal.reversal-max-pullback-pct:10.0}") double maxPullbackPct,
                            @Value("${stockadvisor.signal.reversal-max-ret5d-pct:-5.0}") double maxRet5dPct,
                            @Value("${stockadvisor.signal.reversal-max-change-pct:2.0}") double maxChangePct,
                            @Value("${stockadvisor.signal.reversal-max-drop-pct:5.0}") double maxDropPct,
                            @Value("${stockadvisor.signal.reversal-min-score:40.0}") double minScore) {
        this.enabled = enabled;
        this.maxVolumeRatio = maxVolumeRatio;
        this.minPullbackPct = minPullbackPct;
        this.maxPullbackPct = maxPullbackPct;
        this.maxRet5dPct = maxRet5dPct;
        this.maxChangePct = maxChangePct;
        this.maxDropPct = maxDropPct;
        this.minScore = minScore;
    }

    @Override
    public String name() {
        return "REVERSAL_L";
    }

    @Override
    public String label() {
        return "눌림 반전 (L)";
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
        // ① 거래량이 붙지 않았을 것 — 이 전략의 정체성(급증을 사는 나머지 전략의 거울상)
        if (s.volumeRatio() >= maxVolumeRatio) return "VOLUME_UP";
        // ② 20일선 아래 눌림 구간(음의 이격). 얕으면 눌림이 아니고, 깊으면 추세붕괴.
        double dist = s.maDistPct();
        if (dist > -minPullbackPct) return "NOT_PULLBACK";
        if (dist < -maxPullbackPct) return "BROKEN_TREND";
        // ③ 최근 5일 약세 — 되돌릴 하락이 있어야 반전 대상
        if (s.ret5dPct() > maxRet5dPct) return "NOT_WEAK";
        // ④ 당일 급등 추격 금지(전역 필터와 같은 사상, 훨씬 낮은 문턱)
        if (s.changeRate() > maxChangePct) return "CHASING";
        // ⑤ 당일 폭락은 제외 — "이유 있는 하락"과 떨어지는 칼날 회피(C의 max-drop과 같은 사상)
        if (s.changeRate() < -maxDropPct) return "TOO_DEEP";
        if (ctx.recScore() < minScore) return "SCORE";
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // ⚠️ 핵심 — 볼륨 게이트를 통과하면 이 전략의 전제가 깨진다(급증이 아닌 것을 산다)
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        // 전 종목 평가를 막는 사전필터 — 눌림 구간 + 저거래량 후보만 비싼 평가로 넘긴다.
        return signal.volumeRatio() < maxVolumeRatio
                && signal.maDistPct() <= -minPullbackPct
                && signal.maDistPct() >= -maxPullbackPct;
    }

    @Override
    public boolean alerts() {
        return false;   // v1 섀도우(실주문 0, Discord 미발송) — 측정 먼저
    }

    @Override
    public boolean tracksControl() {
        return false;   // 대조군 불요 — UniverseSnapshot이 전 종목 분모라 /universe-analysis로 반사실 비교
    }
}
