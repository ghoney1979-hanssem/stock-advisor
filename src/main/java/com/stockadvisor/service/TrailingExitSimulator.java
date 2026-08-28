package com.stockadvisor.service;

/**
 * 트레일링 청산 경로 시뮬레이터(<b>순수 정적</b> — DB 없이 테스트).
 *
 * <p>규칙(2026-08-28 사용자 지정): <b>수익률 {@code armPct}(5%) 이상 도달 후, 고점 대비 {@code dropPct}(2%)
 * 하락하면 청산</b>. 도달 전에는 트레일이 걸리지 않고, {@code maxIdx}(보유 상한 1개월)에 도달하면 종가 청산.</p>
 *
 * <p>⚠️ <b>하루 안의 고가·저가 순서를 알 수 없다는 게 일봉 시뮬의 근본 한계</b>다(이 시스템의 MFE/MAE 분석이
 * 겪는 것과 같은 문제). 그래서 <b>보수적 규약</b>을 쓴다:</p>
 * <ol>
 *   <li>먼저 <b>전일까지의 고점</b>으로 만든 스톱을 오늘 저가가 깼는지 본다 — 오늘 고가로 스톱을 먼저 올리지 않는다
 *       (그러면 "고가가 먼저 왔다"고 가정하는 셈이라 결과가 낙관적으로 부풀려진다).</li>
 *   <li>그 다음에 오늘 고가로 고점·무장 여부를 갱신한다.</li>
 * </ol>
 *
 * <p>⚠️ <b>갭 하락은 스톱 가격에 못 판다</b> — 시가가 이미 스톱 아래면 시가로 체결된다. 이걸 빼면
 * 하락장 손실이 조직적으로 과소평가된다(물타기·트레일링 전략의 위험이 정확히 거기 있다).</p>
 *
 * <p>⚠️ <b>손절이 없다</b>(사용자 규칙에 없음). 무장 전 하락은 보유 상한까지 그대로 간다 —
 * 결과의 최대낙폭 분포로 그 대가가 드러나야 한다.</p>
 */
public final class TrailingExitSimulator {

    private TrailingExitSimulator() {}

    public enum Reason { TRAIL, GAP_THROUGH_STOP, MAX_HOLD }

    /**
     * @param exitIdx   청산 시점 인덱스
     * @param exitPrice 청산가
     * @param armed     트레일이 무장됐었나(= 한 번이라도 +armPct 도달)
     * @param peakPct   보유 중 최고 수익률%(MFE) — 무장 문턱 튜닝 근거
     * @param troughPct 보유 중 최저 수익률%(MAE) — 손절 부재의 대가
     */
    public record Exit(int exitIdx, double exitPrice, Reason reason, boolean armed,
                       double peakPct, double troughPct) {}

    /**
     * @param entryIdx 진입 인덱스(그 날 <b>종가</b>로 산다)
     * @param maxIdx   보유 상한 인덱스(포함) — 여기 도달하면 종가 청산
     */
    public static Exit run(int[] open, int[] high, int[] low, int[] close,
                           int entryIdx, int maxIdx, double armPct, double dropPct) {
        double entry = close[entryIdx];
        double peak = entry;
        double trough = entry;
        boolean armed = false;

        for (int t = entryIdx + 1; t <= maxIdx; t++) {
            // ① 전일까지의 고점으로 만든 스톱을 먼저 판정한다(오늘 고가로 스톱을 올리기 전에).
            if (armed) {
                double stop = peak * (1 - dropPct / 100.0);
                if (open[t] <= stop) {
                    // 갭 하락 — 스톱 가격에 못 판다. 시가 체결.
                    return done(t, open[t], Reason.GAP_THROUGH_STOP, true, entry, peak, Math.min(trough, low[t]));
                }
                if (low[t] <= stop) {
                    return done(t, stop, Reason.TRAIL, true, entry, peak, Math.min(trough, low[t]));
                }
            }
            // ② 그 다음에 오늘 고가로 고점·무장 갱신.
            if (high[t] > peak) peak = high[t];
            if (low[t] < trough) trough = low[t];
            if (!armed && peak >= entry * (1 + armPct / 100.0)) armed = true;
        }
        return done(maxIdx, close[maxIdx], Reason.MAX_HOLD, armed, entry, peak, trough);
    }

    private static Exit done(int idx, double price, Reason reason, boolean armed,
                             double entry, double peak, double trough) {
        return new Exit(idx, price, reason, armed,
                (peak - entry) / entry * 100, (trough - entry) / entry * 100);
    }
}
