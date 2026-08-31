package com.stockadvisor.strategy;

import com.stockadvisor.service.SignalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전략 N — 깊은 눌림 (섀도우, <b>거래량 무관</b>, 2026-08-31).
 *
 * <p><b>L의 밴드 바로 바깥을 산다.</b> L(눌림 반전)은 20일선 아래 이격 −3~−10%만 대상으로 하고 그보다 깊으면
 * {@code BROKEN_TREND}(추세붕괴)로 제외하는데, 그 판정은 <b>한 번도 검증된 적이 없다</b> —
 * L의 {@code preScreen}이 {@code maDistPct >= -maxPullbackPct}로 깊은 후보를 평가 자체에서 걸러내기 때문에
 * {@code rejectReason}에 도달하지 못하고, 따라서 <b>{@code BROKEN_TREND} 대조군이 0행</b>이다(2026-08-31 실측).
 * 즉 이 구간은 진입도 대조군도 없는 <b>완전한 사각지대</b>였다.</p>
 *
 * <p><b>⚠️ 설계의 핵심 — 이건 pocket 추격이 아니라 L에 대한 대조 실험이다.</b> 이격 밴드를 뺀
 * <b>나머지 조건(저거래량·ret5d 약세·당일 추격금지·낙폭한도·점수)은 전부 L과 동일한 기본값</b>으로 둔다.
 * 변수를 하나만 바꿔야 두 전략의 성과 차이가 곧 <b>"L의 밴드를 넓혀야 하는가"</b>에 대한 답이 된다.
 * (M이 실패한 방식과의 차이가 여기다 — M은 L과 저거래량 하나만 공유하고 나머지가 전부 달라
 * 결과가 갈려도 무엇 때문인지 분해되지 않았다.)</p>
 *
 * <p><b>근거와 그 한계</b>: {@code /universe-analysis}(전 종목 분모, 저가주·이상치 가드 적용 후)에서
 * MA20이격이 <b>단조</b>이고 가장 깊은 구간이 최고다 — {@code <-10} lift <b>+0.42%p</b>(n=1,533) ·
 * {@code -10~-3} +0.30 · {@code -3~0} +0.20 (9거래일, close).
 * ⚠️ <b>그런데 이 lift는 안정적이지 않다</b>: 시간분할에서 8/19~8/26(6일) +0.47 → 8/27~(2일) <b>−0.03</b>으로
 * 부호가 뒤집힌다(뒤 창이 2일뿐이라 결정적이진 않다). ⚠️ 그리고 <b>lift는 반사실이 아니다</b> —
 * "MA20 아래가 좋다"와 "우리가 고른 MA20 아래가 우리가 거른 것보다 낫다"는 다른 명제이고,
 * 후자는 대조군으로만 잰다(발굴 세션 교훈, M이 lift만 믿고 도입됐다가 근거가 증발한 전례).</p>
 *
 * <p>→ 따라서 <b>실주문 0의 순수 측정</b>으로 시작한다: {@code alerts()=false} + 화이트리스트 미포함,
 * 그리고 <b>도입 시점부터 대조군</b>({@code StrategyEvaluator.deepPullbackControl}).
 * <b>판정 조건(사전 등록)</b>: 거래일 10일 이상 + 상승일/하락일 양쪽 표본 + 클러스터 가드 통과 +
 * <b>진입-대조군 edge &gt; 0</b>, 그리고 <b>L 대비 우위</b>. 이 중 하나라도 미달이면 밴드를 넓히지 않는다.
 * ⚠️ 반대로 <b>지면 그것도 결론이다</b> — L의 {@code BROKEN_TREND} 컷이 옳았음이 처음으로 검증된다.</p>
 *
 * <p>⚠️ 깊은 쪽 하한({@code max-pullback-pct}, 기본 25%)을 두는 이유: 관리종목·거래정지 직전·연속 하한가처럼
 * "되돌릴 하락"이 아니라 <b>구조적 붕괴</b>인 종목을 배제한다. 유동성·부실 필터가 이미 걸리지만
 * 이격 자체로도 상한을 둔다(L의 {@code TOO_DEEP}과 같은 사상).</p>
 */
@Component
public class DeepPullbackStrategy implements TradingStrategy {

    private final boolean enabled;
    private final double maxVolumeRatio;   // 이 배수 이상이면 '급증' — 눌림 계열의 정체성상 대상 아님(L과 동일)
    private final double minPullbackPct;   // 20일선 아래 최소 이격(%) — L의 밴드 바깥에서 시작
    private final double maxPullbackPct;   // 20일선 아래 최대 이격(%) — 그 이상은 구조적 붕괴로 제외
    private final double maxRet5dPct;      // 최근 5일 수익률 상한(L과 동일)
    private final double maxChangePct;     // 당일 등락률 상한(L과 동일)
    private final double maxDropPct;       // 당일 낙폭 하한(L과 동일)
    private final double minScore;

    public DeepPullbackStrategy(
            @Value("${stockadvisor.signal.deep-pullback-enabled:true}") boolean enabled,
            @Value("${stockadvisor.signal.deep-pullback-max-volume-ratio:1.0}") double maxVolumeRatio,
            @Value("${stockadvisor.signal.deep-pullback-min-pullback-pct:10.0}") double minPullbackPct,
            @Value("${stockadvisor.signal.deep-pullback-max-pullback-pct:25.0}") double maxPullbackPct,
            @Value("${stockadvisor.signal.deep-pullback-max-ret5d-pct:-5.0}") double maxRet5dPct,
            @Value("${stockadvisor.signal.deep-pullback-max-change-pct:2.0}") double maxChangePct,
            @Value("${stockadvisor.signal.deep-pullback-max-drop-pct:5.0}") double maxDropPct,
            @Value("${stockadvisor.signal.deep-pullback-min-score:40.0}") double minScore) {
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
        return "DEEP_PULLBACK_N";
    }

    @Override
    public String label() {
        return "깊은 눌림 (N)";
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
        // ── 정체성 사유(preScreen 경계와 같은 축) — 대조군 미기록 ──
        if (s.volumeRatio() >= maxVolumeRatio) return "VOLUME_UP";
        double dist = s.maDistPct();
        if (dist > -minPullbackPct) return "TOO_SHALLOW";          // L의 밴드(−3~−10) = N의 대상 아님
        if (dist < -maxPullbackPct) return "COLLAPSED";            // 구조적 붕괴(되돌릴 하락이 아님)
        // ── 판별 사유 — 대조군 기록 대상(이 축들이 실제 선별에 쓰였다) ──
        if (s.ret5dPct() > maxRet5dPct) return "NOT_WEAK";
        if (s.changeRate() > maxChangePct) return "CHASING";
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
        return false;   // ⚠️ 정체성 — 볼륨 게이트를 통과하면 전제가 깨진다(급증이 아닌 것을 산다). L과 동일.
    }

    @Override
    public boolean preScreen(String stockCode, SignalResult signal) {
        // 전 종목 평가를 막는 사전필터 — 깊은 눌림 + 저거래량 후보만 넘긴다.
        // ⚠️ 유니버스상 이 밴드는 L 밴드의 약 1/4 크기다(9거래일 n=1,533 vs 6,342) → 스캔 부하 증가는 제한적.
        return signal.volumeRatio() < maxVolumeRatio
                && signal.maDistPct() <= -minPullbackPct
                && signal.maDistPct() >= -maxPullbackPct;
    }

    @Override
    public boolean alerts() {
        return false;   // 섀도우 — Discord 미발송. 화이트리스트 미포함이라 실주문도 0.
    }

    @Override
    public boolean tracksControl() {
        // false 유지 — 일괄 기록은 VOLUME_UP·TOO_SHALLOW(정체성·preScreen 경계) 때문에 대조군이 무의미하게 커진다.
        // 대신 판별 사유만 StrategyEvaluator.deepPullbackControl 이 강제 기록한다(L의 2026-08-26 정책과 동일 구조).
        return false;
    }
}
