package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전략 성과 기반 LIVE 진입 게이트 설정 ({@code stockadvisor.trading.perf-gate}).
 *
 * <p>A/B/C 전략별로 최근 성과(net 평균수익률)를 평가해, 기준 미달 전략은 <b>실주문(LIVE)만</b> 자동 차단한다.
 * 가상매수({@code TradeOutcome}) 기록·Discord 알림·관찰은 계속되므로, 끈 전략도 그림자 성과가 계속 쌓여
 * 회복되면 자동으로 다시 켜진다. {@code TRADING_LIVE_STRATEGIES}(수동 화이트리스트)의 동적·자동 보강.</p>
 *
 * <p>표본이 {@code minSamples} 미만이면 "검증 안 됨"으로 보고 차단(fail-closed) — 미검증 전략에 실돈을 태우지 않는다.
 * 그림자 표본은 LIVE 차단 중에도 누적되므로 충분히 쌓이면 게이트가 열린다.</p>
 *
 * @param enabled        게이트 사용 여부(기본 true). false면 성과와 무관하게 통과(화이트리스트만 적용).
 * @param lookbackDays   성과 평가 기간(최근 N 캘린더일, alertDate 기준). 기본 20.
 * @param minSamples     이 표본 수 미만이면 미검증으로 보고 LIVE 차단(fail-closed). 기본 20.
 * @param minNetAvgPct   net 평균수익률(%)이 이 값 미만이면 LIVE 차단. 기본 0.0(net 플러스 요구).
 * @param horizon        성과 측정 시점 — close(당일종가)/nextClose/d2/d3/p10/p30. 기본 close.
 * @param regimeConditional 국면조건부 평가(레이어 2). true면 "현재 시장 국면과 같은 국면에서 진입한 표본"만으로
 *                          net평균 계산 → 강세장에선 강세장 성과로, 약세장에선 약세장 성과로 게이팅. 기본 true.
 *                          (현재 국면 미산출 시 국면 무관 전체 표본으로 fallback.)
 * @param regimeMarketSplit (market,trend) 2차원 분리 — true면 같은 국면 + <b>같은 시장(KOSPI/KOSDAQ)</b> 진입분만 집계.
 *                          더 정밀하나 표본이 시장×국면으로 쪼개져 minSamples 도달이 느려짐(fail-closed). 시장 미상이면 trend만. 기본 true.
 *
 * <p><b>보수적 국면무관 fallback</b>(②엄격바+③축소사이징+④자동졸업): 현재 국면 표본이 {@code minSamples} 미만이면
 * (예: 갓 시작된 강세장 — 강세 표본 0) 그대로 fail-closed 하는 대신, {@code fallbackEnabled}이면 <b>국면 무관 전체 표본</b>
 * (같은 시장, 전 국면 pool)으로 재평가한다. 단 ② 더 엄격한 바({@code fallbackMinSamples} &gt; minSamples,
 * {@code fallbackMinNetAvgPct} &gt; minNetAvgPct)를 요구하고, ③ 통과해도 사이징을 {@code fallbackSizeMult}배로 축소하며,
 * ④ 해당 국면 표본이 {@code minSamples}에 도달하면 자동으로 엄격(국면조건부) 경로로 졸업한다. 기본 off(opt-in).</p>
 *
 * @param fallbackEnabled     국면 표본 부족 시 국면무관 fallback 허용(기본 false=기존 fail-closed).
 * @param fallbackMinSamples  fallback pool(전국면) 최소 표본 — minSamples보다 크게(더 엄격). 기본 50.
 * @param fallbackMinNetAvgPct fallback 통과 net 기준(%) — minNetAvgPct보다 높게(더 엄격). 기본 0.5.
 * @param fallbackSizeMult    fallback 진입 시 사이징 배수(축소진입). 기본 0.5(절반). {@code OrderService.submitEntry}가 적용.
 * @param inverseMinSamples   INVERSE 버킷 전용 최소 표본 — 인버스 표본은 폭락일에만 쌓여 minSamples(30) 도달이 느려 별도 하향(기본 10). net 기준(minNetAvgPct)은 동일(표본만 완화).
 * @param flowConditional     국면+흐름 조건부(3차원, 2026-07-15) — 현재 (시장,국면,흐름부호) 버킷 표본이 flowMinSamples 이상이면
 *                            <b>그 버킷만으로 판정</b>(흐름까지 반영), 미만이면 국면 버킷으로 자연 fallback(기존 동작).
 *                            흐름 미산출(개장 ~30분, mom30 없음)이면 자동으로 국면만 적용. 기본 true.
 * @param flowMinSamples      흐름 버킷 최소 표본(기본 30) — 미만이면 국면 버킷 판정.
 * @param inverseBootstrapSizeMult INVERSE 부트스트랩 — 표본이 inverseMinSamples 미만이어도 <b>이 배수로 축소한 사이징으로 실주문 허용</b>
 *                            (예: 0.3 = 일반 비중의 30% ≈ 순자산 1.5%/종목 — 적은 비용으로 실표본 수집). 표본이 차면 자동 졸업:
 *                            net ≥ minNetAvgPct면 정상 사이징(제한 해제), 미달이면 차단(부트스트랩으로 성과 미달을 우회 불가). 0=비활성(fail-closed). 기본 0.3.
 */
@ConfigurationProperties(prefix = "stockadvisor.trading.perf-gate")
public record StrategyPerformanceProperties(
        boolean enabled,
        int lookbackDays,
        int minSamples,
        double minNetAvgPct,
        String horizon,
        boolean regimeConditional,
        boolean regimeMarketSplit,
        boolean fallbackEnabled,
        int fallbackMinSamples,
        double fallbackMinNetAvgPct,
        double fallbackSizeMult,
        int inverseMinSamples,
        double inverseBootstrapSizeMult,
        boolean flowConditional,
        int flowMinSamples,
        String inverseRealizedSince,   // INVERSE 버킷 실현 채점 시작일(yyyyMMdd, 빈값=제한없음) — 청산 로직 세대 교체 시 구로직 표본 제외
        double inverseMinNetAvgPct,    // INVERSE 버킷 전용 net 문턱(%) — 헤지 다리라 알파 문턱(min-net 0.3)보다 완화 가능(2026-07-24). ≤0이면 min-net 사용
        // 히스테리시스(2026-08-06): 게이트가 문턱(min-net) 근처에서 여닫이를 반복하며 "평균회귀 성과곡선"을 고점매수-저점매도
        // 하는 역선택(실측: G Δ−3.95·B Δ−1.81)을 막기 위한 비대칭 밴드. 열 땐 min-net(0.3), 닫을 땐 이 값(예 −0.2)까지
        // 유지 → 그 사이(밴드)는 직전 상태 유지(진동 억제). 활성 조건: closeNetAvgPct < minNetAvgPct(아니면 off=stateless).
        double closeNetAvgPct
) {
    public StrategyPerformanceProperties {
        if (lookbackDays <= 0) lookbackDays = 20;
        if (inverseRealizedSince == null) inverseRealizedSince = "";
        if (inverseMinNetAvgPct <= 0) inverseMinNetAvgPct = minNetAvgPct;
        if (horizon == null || horizon.isBlank()) horizon = "close";
        if (fallbackMinSamples <= 0) fallbackMinSamples = 50;
        if (fallbackSizeMult <= 0 || fallbackSizeMult > 1.0) fallbackSizeMult = 0.5;
        if (inverseMinSamples <= 0) inverseMinSamples = 10;
        if (inverseBootstrapSizeMult < 0 || inverseBootstrapSizeMult > 1.0) inverseBootstrapSizeMult = 0.3;
        if (flowMinSamples <= 0) flowMinSamples = 30;
    }
    // ⚠️ 보조(호환) 생성자 금지 — @ConfigurationProperties 레코드는 canonical 하나만(JavaBean 폴백 기동실패, 7/13 실측).
}
