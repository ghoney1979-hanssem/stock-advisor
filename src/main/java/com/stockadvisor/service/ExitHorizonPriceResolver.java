package com.stockadvisor.service;

import com.stockadvisor.domain.OutcomeSample;
import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.OutcomeSampleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 반사실 net을 <b>동일 horizon</b>으로 맞추기 위한 공유 가격 resolver (2026-08-12, horizon 통일).
 *
 * <p>성과게이트가 검증하는 "실제 청산 시점"({@code exit}) 가격을 분석 엔드포인트도 똑같이 쓰게 한다 —
 * 게이트=exit, control-analysis=exit인데 outcome-analysis·feature-mining이 당일종가라 반사실 수치가
 * 서로 비교 불가였던 문제 해소. horizon="exit"이면 전략별 권장 청산 마크({@link StrategyHoldTimeProvider})의
 * {@link OutcomeSample} 가격(±허용범위 내 근접 마크)을, 스윙 전략은 swingHorizon(익일종가)을, 그 외 지정 horizon은
 * 해당 종가 필드를 반환한다.</p>
 *
 * <p>⚠️ {@link StrategyPerformanceGate}·{@link ControlAnalysisService}는 동일 로직을 자체 보유(검증된 money/판정 경로라
 * 이번엔 미이관). 이 resolver는 그 로직의 정본(canonical) — 향후 그 둘도 이걸로 수렴 권장.</p>
 */
@Service
public class ExitHorizonPriceResolver {

    /** 권장 청산마크 ±이 범위 내 근접 마크를 대체 사용(표본 기근 보정) — 게이트와 동일값. */
    private static final int TOLERANCE_MIN = 30;

    private final StrategyHoldTimeProvider holdTimeProvider;
    private final OutcomeSampleRepository outcomeSampleRepository;
    private final Set<String> swingStrategies;
    private final String swingHorizon;

    public ExitHorizonPriceResolver(StrategyHoldTimeProvider holdTimeProvider,
                                    OutcomeSampleRepository outcomeSampleRepository,
                                    @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv,
                                    @Value("${stockadvisor.trading.swing-horizon:nextClose}") String swingHorizon) {
        this.holdTimeProvider = holdTimeProvider;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
        this.swingHorizon = swingHorizon;
    }

    /** 전략의 검증 horizon: 스윙=swingHorizon(nextClose), 그 외=defaultHorizon(prod "exit"). */
    public String horizonFor(String strategy, String defaultHorizon) {
        if (swingStrategies.contains(strategy)) return swingHorizon;
        return defaultHorizon == null ? "close" : defaultHorizon;
    }

    /** exit 마크(분) — exit horizon이 아니면 -1(라벨용). */
    public int exitMark(String strategy, String horizon) {
        return "exit".equals(horizon) ? holdTimeProvider.holdMinutes(strategy) : -1;
    }

    /**
     * 전략·horizon에 대한 outcome→가격 함수. exit면 권장 청산마크 근접 {@link OutcomeSample} 가격(미수집이면 null=제외),
     * 그 외면 해당 종가 필드. exit 경로는 전략당 1회 쿼리라 caller가 전략별로 재사용할 것.
     */
    public Function<TradeOutcome, Long> priceFor(String strategy, String horizon) {
        if ("exit".equals(horizon)) {
            int mark = holdTimeProvider.holdMinutes(strategy);
            Map<Long, Long> byOutcome = new HashMap<>();
            Map<Long, Integer> bestDist = new HashMap<>();
            for (OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                    strategy, mark - TOLERANCE_MIN, mark + TOLERANCE_MIN)) {
                int d = Math.abs(s.getMarkMinutes() - mark);
                Integer cur = bestDist.get(s.getOutcomeId());
                if (cur == null || d < cur) {
                    bestDist.put(s.getOutcomeId(), d);
                    byOutcome.put(s.getOutcomeId(), s.getPrice());
                }
            }
            return o -> byOutcome.get(o.getId());
        }
        return o -> resultPrice(o, horizon);
    }

    /** 종가 필드 기반 horizon 가격 — close/nextClose/d2/d3/p10/p30. */
    public static Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            case "p10" -> o.getPrice10min();
            case "p30" -> o.getPrice30min();
            default -> o.getPriceClose();
        };
    }
}
