package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전략별 시간기반 청산 보유시간 제공자.
 *
 * <p>2026-09-08: 과거엔 {@code ExitTimingService}가 계산한 보유시간별 평균 net 수익 곡선에서 표본이 충분한 마크 중
 * 평균수익 최대 마크를 자동 채택했다("적응형"). 사용자 판단으로 그 튜닝이 실효가 없다고 확인돼 폐기 —
 * 이제는 <b>전략별 고정값(설정)</b>만 반환한다. 지정 없는 전략은 전역 {@code policy.timeExitHoldMinutes()}.</p>
 *
 * <p>⚠️ 여기서 정한 값은 <b>실제 청산 시점</b>일 뿐 아니라 {@link StrategyPerformanceGate}의 <b>채점 horizon</b>과
 * {@link PolicyGate}의 <b>진입 마감시각</b>("진입시각+보유 ≤ session-end")을 함께 결정한다 — 세 소비처가 한 값으로
 * 묶여 있는 것은 여전히 의도다(어긋나면 "검증한 적 없는 청산"으로 실주문이 나간다). 단, 현재 LIVE 전략은 전부
 * 멀티데이/스윙/인버스 청산이 이 값보다 우선하므로(둘 다 청산 경로·게이트 horizon·진입 마감 규칙에서 이 값을
 * 아예 안 씀) 실거래 영향은 없고, 아직 LIVE에 없는 전략(F·H·N·M 등)의 실제 청산과 그 전략들의 분석용 exit
 * horizon 산정에만 쓰인다.</p>
 */
@Service
public class StrategyHoldTimeProvider {

    private static final List<String> STRATEGIES = List.of("MOMENTUM_A", "VOLUME_LEADING_B", "MEAN_REVERSION_C");

    private final TradingPolicyProperties policy;

    // 전략별 고정 보유시간(csv "STRATEGY:분") — 미지정 전략은 전역 time-exit-hold-minutes.
    @org.springframework.beans.factory.annotation.Value(
            "${stockadvisor.trading.hold-minutes-per-strategy:}")
    private String holdMinutesPerStrategyCsv = "";
    private volatile Map<String, Integer> holdMinutesPerStrategy;

    // 가시화(describe)용 전략 목록 — 필드주입(생성자 무churn, 기존 단위테스트 영향 없음).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<com.stockadvisor.strategy.TradingStrategy> strategies;

    public StrategyHoldTimeProvider(TradingPolicyProperties policy) {
        this.policy = policy;
    }

    /**
     * @param strategy    전략명
     * @param holdMinutes 적용 보유시간(분)
     * @param fixed       true=전략별 지정값, false=전역 fallback값
     */
    public record HoldInfo(String strategy, int holdMinutes, boolean fixed) {}

    /** 해당 전략의 청산 보유시간(분). 전략별 지정 없으면 전역 고정값. */
    public int holdMinutes(String strategy) {
        Integer v = perStrategy().get(strategy);
        return v != null ? v : policy.timeExitHoldMinutes();
    }

    /** 전 전략 현재 적용 보유시간(가시화/관리 API용). */
    public List<HoldInfo> describe() {
        Map<String, Integer> m = perStrategy();
        List<HoldInfo> out = new ArrayList<>();
        for (String s : knownStrategies(m)) {
            Integer v = m.get(s);
            out.add(v != null ? new HoldInfo(s, v, true)
                    : new HoldInfo(s, policy.timeExitHoldMinutes(), false));
        }
        return out;
    }

    /** 가시화 대상 전략명 — 등록된 전략 빈 ∪ csv 지정 전략(둘 중 하나에만 있어도 노출). */
    private List<String> knownStrategies(Map<String, Integer> perStrategy) {
        java.util.SortedSet<String> names = new java.util.TreeSet<>(STRATEGIES);
        if (strategies != null) for (com.stockadvisor.strategy.TradingStrategy s : strategies) names.add(s.name());
        names.addAll(perStrategy.keySet());
        return new ArrayList<>(names);
    }

    private Map<String, Integer> perStrategy() {
        Map<String, Integer> m = holdMinutesPerStrategy;
        if (m == null) {
            m = parseHoldMinutes(holdMinutesPerStrategyCsv);
            holdMinutesPerStrategy = m;
        }
        return m;
    }

    /**
     * "STRATEGY:분,STRATEGY2:분" 파싱(순수). 값이 숫자가 아니거나 ≤0이면 그 항목은 무시(=전역값 사용).
     *
     * <p>⚠️ 오타·잘못된 값을 조용히 무시하는 것은 의도다 — 설정 실수로 보유시간이 0이 되면 진입 즉시
     * 청산되므로, 알 수 없는 값은 종전 동작(전역값)으로 degrade하는 편이 안전하다.</p>
     */
    static Map<String, Integer> parseHoldMinutes(String csv) {
        Map<String, Integer> m = new HashMap<>();
        if (csv == null) return m;
        for (String part : csv.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim();
            if (k.isEmpty()) continue;
            try {
                int v = Integer.parseInt(kv[1].trim());
                if (v > 0) m.put(k, v);
            } catch (NumberFormatException ignored) {
                // degrade — 전역값으로 되돌아간다
            }
        }
        return m;
    }
}
