package com.stockadvisor.service;

import java.util.Map;

/**
 * 일별 가중 edge(<b>순수 정적</b> — DB 없이 테스트).
 *
 * <p><b>왜 필요한가(2026-09-10 발견 · 2026-09-14 실측 확인)</b>: 기존 edge는 전 구간을 통째로 평균한
 * <b>pooled</b>였다 — 진입군과 대조군의 <b>날짜 구성</b>이 다르면(진입이 특정 날에 몰림) 이 값이 왜곡된다
 * (Simpson 역설). 기간 정렬({@code overlapWindow}, 2026-08-25·08-27)은 "창이 겹치는가"만 보고
 * <b>창 안의 날짜 구성이 같은가는 보지 않는다</b>.</p>
 *
 * <p>⚠️ <b>실측 규모(2026-09-14, close, 20260701~20260821 · 같은 행에 가중만 변경)</b>: 가중 방식만으로
 * edge가 최대 <b>2.06%p</b> 움직이고 부호가 뒤집혔다 — F −1.04→<b>+1.02</b> · H +0.06→+0.56 ·
 * C −0.33→+0.23 · B −0.18→+0.10 · D +0.53→<b>−0.48</b> · G +0.75→<b>−1.07</b>.
 * 즉 2026-08-21~25에 edge를 근거로 내린 B·F·H·C의 LIVE 제외는 <b>근거가 무효</b>이고(E만 양쪽 음수로 유지),
 * 반대로 유지 중이던 D·G는 일별가중에서 음수다. <b>왜곡은 양방향</b>이다.</p>
 *
 * <p>⚠️ 그렇다고 일별가중이 곧 진실은 아니다 — 대조군이 없는 날은 통째로 버려지므로
 * {@code daysBoth}가 {@code daysEntered}보다 크게 적으면 <b>"절반의 날만 본 값"</b>이다. 실측에서 가장 크게
 * 움직인 전략들이 하필 커버리지가 얇았고(F 14/29일 · H 14/25일 · G 12/30일), 커버리지가 완전한
 * B·E(33/33일)는 결과가 거의 안 바뀌었다. <b>두 지표를 병기하고 커버리지를 함께 읽는 것</b>이 이 클래스의 목적이다.</p>
 */
final class DailyWeightedEdge {

    /**
     * @param weightedPct 진입건수 가중 일별 edge(%p) — pooled와 단위가 같아 직접 비교된다. <b>주지표</b>.
     *                    가중을 진입건수로 두는 이유: edge는 결국 <b>진입 1건당</b> 경제성이기 때문.
     * @param equalPct    거래일 동일가중 edge(%p) — {@code weightedPct}와 부호·크기가 어긋나면
     *                    <b>추정이 불안정하다는 신호</b>다(실측 G −1.07 ↔ +0.31).
     * @param daysBoth    양쪽 모두 표본이 있는 거래일 수 = edge가 실제로 계산된 날.
     * @param daysEntered 진입군에 표본이 있는 거래일 수. {@code daysBoth}와 벌어질수록 신뢰도가 낮다.
     */
    record Result(Double weightedPct, Double equalPct, int daysBoth, int daysEntered) {}

    static final Result EMPTY = new Result(null, null, 0, 0);

    private DailyWeightedEdge() {
    }

    /**
     * 일자별로 먼저 평균을 낸 뒤 그 차이를 가중평균한다(= 날짜 구성 차이를 상쇄).
     *
     * @param entered           진입군 일별 {건수, net합}
     * @param other             비교군 일별 {건수, net합}
     * @param enteredMinusOther true면 {@code 진입−비교}(feature-mining 규약: 양수=진입이 가치 추가),
     *                          false면 {@code 비교−진입}(control-analysis 규약: 양수=거른 게 더 나았다).
     *                          <b>두 서비스의 부호 규약이 반대</b>라 호출측이 명시한다.
     */
    static Result of(Map<String, double[]> entered, Map<String, double[]> other, boolean enteredMinusOther) {
        if (entered == null || other == null) return EMPTY;
        int daysEntered = 0;
        for (double[] v : entered.values()) if (v != null && v[0] > 0) daysEntered++;

        double wNum = 0, wDen = 0, eqSum = 0;
        int daysBoth = 0;
        for (Map.Entry<String, double[]> e : entered.entrySet()) {
            double[] a = e.getValue();
            double[] b = other.get(e.getKey());
            if (a == null || b == null || a[0] <= 0 || b[0] <= 0) continue;
            double diff = (a[1] / a[0]) - (b[1] / b[0]);
            if (!enteredMinusOther) diff = -diff;
            wNum += diff * a[0];
            wDen += a[0];
            eqSum += diff;
            daysBoth++;
        }
        if (daysBoth == 0 || wDen <= 0) return new Result(null, null, 0, daysEntered);
        return new Result(round2(wNum / wDen), round2(eqSum / daysBoth), daysBoth, daysEntered);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
