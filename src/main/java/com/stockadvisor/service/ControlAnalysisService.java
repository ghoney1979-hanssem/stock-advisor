package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 대조군 분석 — "진입한 종목 vs 진입 안 한 종목(탈락 사유별)"의 net 성과 비교.
 *
 * <p>최종 목표: 알림 안 나간 종목까지 분석해 <b>더 수익 좋은 전략을 발굴</b>. 예컨대 전략 B에서 "DIRECTION_DOWN(하락 컷)"
 * 으로 탈락시킨 종목들의 평균수익이 진입분보다 높다면, 그 필터가 오히려 수익을 깎고 있다는 신호 → 게이트 완화 검토.</p>
 *
 * <p>net = (price-buyPrice)/buyPrice*100 − (왕복비용 + 진입시 슬리피지). 진입분은 control=false, 대조군은 control=true.</p>
 */
@Service
public class ControlAnalysisService {

    private static final int MIN_SAMPLES = 10;   // 진단 신뢰 최소 진입 표본
    private static final int EXIT_MARK_TOLERANCE_MIN = 30;   // perf-gate와 동일 — 권장 청산마크 ±근접 대체
    // 단일일 클러스터 가드(2026-08-21) — feature-mining·strategy-gate가 이미 쓰던 문턱과 동일하게 맞춤.
    // 계기: C가 verdict "OK / net +4.71%(n=195)"로 유일한 흑자 전략으로 보고됐는데, 실제로는 195건 중
    // 134건(69%)이 2026-06-26 하루(그날 +7.52%)였다 — 같은 데이터가 엔드포인트에 따라 정반대 결론을 냈다.
    private static final double MAX_DAY_SHARE_PCT = 80.0;
    private static final int MIN_DISTINCT_DAYS = 3;

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final com.stockadvisor.repository.OutcomeSampleRepository outcomeSampleRepository;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final double roundTripCostPct;
    private final java.util.Set<String> swingStrategies;   // 스윙 전략은 nextClose(D+1)로 진단(그 외 exit=권장청산마크)

    public ControlAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
                                  ExecutionCostModel executionCostModel,
                                  com.stockadvisor.repository.OutcomeSampleRepository outcomeSampleRepository,
                                  StrategyHoldTimeProvider holdTimeProvider,
                                  @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct,
                                  @Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.outcomeSampleRepository = outcomeSampleRepository;
        this.holdTimeProvider = holdTimeProvider;
        this.roundTripCostPct = roundTripCostPct;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
    }

    /**
     * @param distinctDays     이 그룹 표본이 걸친 <b>서로 다른 진입일</b> 수 — 1이면 사실상 이벤트 1개(독립표본 아님)
     * @param maxDaySharePct   단일 거래일이 차지하는 최대 비중(%) — 건수 기준 클러스터 진단
     * @param topDay           net 합 기여가 가장 큰 거래일(yyyyMMdd)
     * @param netExTopDayPct   그 하루를 뺀 나머지 net 평균(%) — 표본이 그 날뿐이면 null
     * @param clustered        건수 편중(share/일수) <b>또는</b> LOO에서 net 부호가 뒤집히면 true
     * @param alignedFrom/alignedTo         이 탈락 사유와 ENTERED가 <b>겹치는 거래일 구간</b>(yyyyMMdd). ENTERED 자신은 null
     * @param alignedSamples/alignedNetPct  그 구간으로 자른 이 그룹의 표본·net
     * @param alignedEnteredSamples/alignedEnteredNetPct  같은 구간으로 자른 ENTERED의 표본·net
     * @param edgeVsEnteredPct  aligned(이 사유) − aligned(ENTERED). <b>양수면 "거른 게 더 나았다"</b>(필터 재검토 후보)
     *
     * <p>🐞 2026-08-27 추가(aligned*): 종전엔 <b>전체 구간</b> net끼리 비교했는데, 대조군 사유는 도입 시점이
     * 제각각이라(강제 기록 사유는 특히) <b>진입군보다 늦게 시작</b>한다 — 그러면 비교가 "조건 차이"가 아니라
     * <b>기간 차이</b>를 잰다. 실측 2026-08-27 REVERSAL_L: ENTERED n=209(4거래일, 8/25 급등일 포함) vs
     * {@code NOT_WEAK} n=61(<b>1거래일</b>)을 그대로 빼서 edge를 +1.85%p로 보고했는데, 같은 날로 맞추면
     * <b>+0.69%p</b>였다. {@code FeatureMiningService.overlapWindow}가 2026-08-25에 받은 것과 같은 수정이며,
     * 새 대조군 사유가 추가될 때마다 도입 직후엔 <b>구조적으로 부풀려지므로</b> 이 정렬이 필수다.</p>
     */
    public record Stat(String group, int samples, Double avgNetReturnPct, Double winRatePct,
                       int distinctDays, Double maxDaySharePct, String topDay, Double netExTopDayPct,
                       boolean clustered,
                       String alignedFrom, String alignedTo,
                       Integer alignedSamples, Double alignedNetPct,
                       Integer alignedEnteredSamples, Double alignedEnteredNetPct,
                       Double edgeVsEnteredPct) {}
    public record StrategyControl(String strategy, String horizon, Stat entered, List<Stat> rejectedByReason,
                                  String hint) {}

    /**
     * @param strategy   전략명
     * @param horizon    진단에 쓴 horizon(스윙=nextClose, 그 외 close)
     * @param enteredNet 진입분 net 평균(%)
     * @param verdict    LOSER_MISCALIBRATED / LOSER_REGIME / UNDERSAMPLED / OK
     * @param suggestion 조치 제안(사람이 읽는 진단)
     * @param outperformingRejects 진입분보다 나은 탈락 사유들(필터 재검토 후보)
     */
    public record Diagnosis(String strategy, String horizon, Double enteredNet, int samples,
                            String verdict, String suggestion, List<String> outperformingRejects) {}

    /** @param horizon exit(권장청산마크)/close/nextClose/d2/d3/p10/p30 */
    public List<StrategyControl> analyze(String horizon) {
        boolean exitMode = "exit".equals(horizon);
        Map<String, Map<Long, Long>> exitByStrat = exitMode ? new java.util.HashMap<>() : null;   // 전략별 (outcomeId→권장마크가)
        // strategy -> "ENTERED" 또는 "REJECT:사유" -> 누적(net합·승수·건수 + 진입일별 건수)
        Map<String, Map<String, Acc>> agg = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            Long price = exitMode
                    ? exitByStrat.computeIfAbsent(o.getStrategy(), this::buildExitPrices).get(o.getId())
                    : resultPrice(o, horizon);
            if (price == null || o.getBuyPrice() <= 0) continue;
            double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                    : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
            double net = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - roundTripCostPct - slip;
            String group = o.isControl() ? "REJECT:" + (o.getRejectReason() == null ? "기타" : o.getRejectReason()) : "ENTERED";
            agg.computeIfAbsent(o.getStrategy(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(group, k -> new Acc())
                    .add(net, o.getAlertDate());
        }

        List<StrategyControl> result = new ArrayList<>();
        agg.forEach((strategy, groups) -> {
            Acc enteredAcc = groups.get("ENTERED");
            Stat entered = toStat("ENTERED", enteredAcc);
            List<Stat> rejected = new ArrayList<>();
            // 각 탈락 사유는 ENTERED와 겹치는 거래일 구간으로 정렬해 edge를 함께 낸다(기간 차이를 조건 차이로 오독 방지).
            groups.forEach((g, a) -> { if (g.startsWith("REJECT:")) rejected.add(toStat(g.substring(7), a, enteredAcc)); });
            rejected.sort((x, y) -> Integer.compare(y.samples(), x.samples()));
            result.add(new StrategyControl(strategy, horizon == null ? "close" : horizon, entered, rejected,
                    buildHint(entered, rejected)));
        });
        return result;
    }

    /**
     * 자동 진단 — 각 전략을 <b>perf-gate와 동일한 horizon</b>(스윙=nextClose, 그 외=exit=권장청산마크)으로 판정 + 손실 원인 분류.
     * 손실전략 우선 정렬. (종가로 보면 인트라데이 전략을 오판 — 권장청산에서 나가는데 종가로 채점하던 불일치 해소, perf-gate와 잣대 통일.)
     */
    public List<Diagnosis> diagnose() {
        Map<String, StrategyControl> exit = index(analyze("exit"));       // 인트라데이 = 권장청산마크(perf-gate와 동일)
        Map<String, StrategyControl> next = index(analyze("nextClose"));  // 스윙 = 익일종가
        java.util.Set<String> strategies = new TreeSet<>();
        strategies.addAll(exit.keySet());
        strategies.addAll(next.keySet());

        List<Diagnosis> out = new ArrayList<>();
        for (String s : strategies) {
            boolean swing = swingStrategies.contains(s);
            StrategyControl sc = swing ? next.get(s) : exit.get(s);
            if (sc == null) sc = exit.get(s);
            if (sc == null) sc = next.get(s);
            if (sc != null) out.add(buildDiagnosis(sc));
        }
        // 손실전략 우선: MISCALIBRATED → REGIME → UNDERSAMPLED → OK, 같은 등급은 net 낮은 순
        out.sort((x, y) -> {
            int rx = verdictRank(x.verdict()), ry = verdictRank(y.verdict());
            if (rx != ry) return Integer.compare(rx, ry);
            double nx = x.enteredNet() == null ? 1e9 : x.enteredNet();
            double ny = y.enteredNet() == null ? 1e9 : y.enteredNet();
            return Double.compare(nx, ny);
        });
        return out;
    }

    private int verdictRank(String v) {
        return switch (v) {
            case "LOSER_MISCALIBRATED" -> 0;
            case "LOSER_REGIME" -> 1;
            case "UNDERSAMPLED" -> 2;
            case "CLUSTERED" -> 3;
            default -> 4;   // OK
        };
    }

    private Diagnosis buildDiagnosis(StrategyControl sc) {
        Stat e = sc.entered();
        if (e == null || e.avgNetReturnPct() == null || e.samples() < MIN_SAMPLES) {
            int n = e == null ? 0 : e.samples();
            return new Diagnosis(sc.strategy(), sc.horizon(), e == null ? null : e.avgNetReturnPct(), n,
                    "UNDERSAMPLED", String.format("표본 부족(%d<%d) — 수집·관측 지속", n, MIN_SAMPLES), List.of());
        }
        double net = e.avgNetReturnPct();
        // 단일일 클러스터 — net을 판정에 쓰지 않는다(이벤트 1개를 표본 n개로 오인하는 것 방지).
        // 수치는 그대로 실어 보내되 verdict으로 "믿지 말 것"을 표시한다.
        if (e.clustered()) {
            String loo = e.netExTopDayPct() == null ? "판정불가"
                    : String.format("%+.2f%%", e.netExTopDayPct());
            return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(), "CLUSTERED",
                    String.format("⚠️ 단일일 의존(net %+.2f%%, n%d, 거래일 %d, 최대비중 %.0f%%) — "
                                    + "최대기여일(%s) 제외 net %s로 결론이 뒤집힘. 판정 보류",
                            net, e.samples(), e.distinctDays(),
                            e.maxDaySharePct() == null ? 0 : e.maxDaySharePct(),
                            e.topDay() == null ? "-" : e.topDay(), loo),
                    List.of());
        }
        if (net >= 0) {
            return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(),
                    "OK", String.format("정상(net %+.2f%%, n%d) — 유지", net, e.samples()), List.of());
        }
        // 손실 — 진입분보다 나은 탈락사유(필터 재검토 후보) 수집
        List<String> better = new ArrayList<>();
        for (Stat r : sc.rejectedByReason()) {
            // 클러스터된 탈락 버킷은 비교 대상에서 제외 — "하루 이벤트가 만든 net"으로 필터를 흔들지 않는다.
            // 그리고 비교는 '겹치는 거래일 구간'으로 정렬된 edge로 한다(기간 차이 ≠ 조건 차이, 2026-08-27).
            if (r.samples() >= MIN_SAMPLES && !r.clustered()
                    && r.edgeVsEnteredPct() != null && r.edgeVsEnteredPct() > 0) {
                better.add(String.format("%s(%+.2f%% vs 진입 %+.2f%%, n%d, %s~%s)", r.group(),
                        r.alignedNetPct(), r.alignedEnteredNetPct(), r.alignedSamples(),
                        r.alignedFrom(), r.alignedTo()));
            }
        }
        if (!better.isEmpty()) {
            return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(), "LOSER_MISCALIBRATED",
                    String.format("🔴 손실(net %+.2f%%). 거른 게 진입보다 나음 → 진입조건/해당 필터 재검토", net), better);
        }
        return new Diagnosis(sc.strategy(), sc.horizon(), net, e.samples(), "LOSER_REGIME",
                String.format("🔴 손실(net %+.2f%%). 필터는 유효(거른 게 더 나쁨) → 국면 부적합(국면게이팅) 또는 진입 품질바 상향", net),
                List.of());
    }

    private Map<String, StrategyControl> index(List<StrategyControl> list) {
        Map<String, StrategyControl> m = new LinkedHashMap<>();
        for (StrategyControl c : list) m.put(c.strategy(), c);
        return m;
    }

    private Stat toStat(String group, Acc a) {
        return toStat(group, a, null);
    }

    /**
     * @param entered ENTERED 누적기 — null이 아니면 <b>겹치는 거래일 구간</b>으로 양쪽을 잘라 edge를 함께 낸다.
     *                ENTERED 자신을 만들 때는 null(자기 자신과 정렬할 대상이 없다).
     */
    private Stat toStat(String group, Acc a, Acc entered) {
        if (a == null || a.count == 0) {
            return new Stat(group, 0, null, null, 0, null, null, null, false,
                    null, null, null, null, null, null, null);
        }
        int n = a.count;
        int days = a.cntByDay.size();
        double net = a.sumNet / n;
        double share = 100.0 * a.cntByDay.values().stream().mapToInt(c -> c[0]).max().orElse(0) / n;

        // LOO — net 합 기여 최대인 하루를 빼고 다시 평균. 남는 표본이 없으면 판정 불가(null).
        String topDay = a.topDay();
        Double netExTop = null;
        if (topDay != null && days > 1) {
            int restN = n - a.cntByDay.get(topDay)[0];
            if (restN > 0) netExTop = (a.sumNet - a.sumByDay.get(topDay)[0]) / restN;
        }

        // 클러스터 판정: ① 건수 편중(비중/거래일 수) ② LOO에서 net 부호가 뒤집힘.
        // ②가 실전에서 결정적이다 — C는 ①(69% < 80%, 25거래일)을 통과하지만 06-26 하루를 빼면
        // net이 +4.71% → −1.46%로 부호가 뒤집힌다(=그 흑자는 전부 그 하루였다).
        boolean clustered = share > MAX_DAY_SHARE_PCT
                || days < MIN_DISTINCT_DAYS
                || (netExTop != null && Math.signum(net) != Math.signum(netExTop));

        // 기간 정렬 — 이 사유와 ENTERED가 겹치는 거래일 구간으로 양쪽을 자른 뒤에만 edge를 낸다.
        String[] win = entered == null ? null : overlapWindow(a.cntByDay.keySet(), entered.cntByDay.keySet());
        Integer aN = null, eN = null;
        Double aNet = null, eNet = null, edge = null;
        if (win != null) {
            double[] mine = windowed(a, win[0], win[1]);
            double[] theirs = windowed(entered, win[0], win[1]);
            aN = (int) mine[0];
            eN = (int) theirs[0];
            if (mine[0] > 0) aNet = round2(mine[1] / mine[0]);
            if (theirs[0] > 0) eNet = round2(theirs[1] / theirs[0]);
            if (aNet != null && eNet != null) edge = round2(aNet - eNet);
        }

        return new Stat(group, n, round2(net), round2(100.0 * a.wins / n),
                days, round2(share), topDay, round2(netExTop), clustered,
                win == null ? null : win[0], win == null ? null : win[1],
                aN, aNet, eN, eNet, edge);
    }

    /**
     * 두 그룹이 <b>겹치는 거래일 구간</b> [from,to] (순수) — 없으면 null.
     *
     * <p>yyyyMMdd는 고정폭이라 사전식 비교가 곧 시간순이다({@code FeatureMiningService.overlapWindow}와 동일 사상).
     * 교집합이 아니라 <b>구간</b>을 쓰는 이유: 그룹마다 신호가 나는 날이 달라 교집합을 쓰면 표본이 과하게 깎인다 —
     * 여기서 막으려는 건 "한쪽이 통째로 다른 기간"이지 "일부 날짜가 비는 것"이 아니다.</p>
     */
    static String[] overlapWindow(java.util.Set<String> daysA, java.util.Set<String> daysB) {
        if (daysA.isEmpty() || daysB.isEmpty()) return null;
        String loA = java.util.Collections.min(daysA), hiA = java.util.Collections.max(daysA);
        String loB = java.util.Collections.min(daysB), hiB = java.util.Collections.max(daysB);
        String from = loA.compareTo(loB) >= 0 ? loA : loB;
        String to = hiA.compareTo(hiB) <= 0 ? hiA : hiB;
        return from.compareTo(to) <= 0 ? new String[]{from, to} : null;   // 겹치는 구간 없음
    }

    /** 구간 [from,to] 안의 {건수, net합} (순수). 진입일 미상 표본은 집계에서 빠진다. */
    private static double[] windowed(Acc a, String from, String to) {
        double n = 0, sum = 0;
        for (Map.Entry<String, int[]> e : a.cntByDay.entrySet()) {
            String d = e.getKey();
            if (d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
            n += e.getValue()[0];
            double[] sv = a.sumByDay.get(d);
            if (sv != null) sum += sv[0];
        }
        return new double[]{n, sum};
    }

    /** 그룹별 누적기 — net 합·승수·건수 + <b>진입일별 건수</b>(클러스터 판정용). */
    private static final class Acc {
        double sumNet;
        int wins;
        int count;
        final Map<String, int[]> cntByDay = new LinkedHashMap<>();      // 일자 -> [건수]
        final Map<String, double[]> sumByDay = new LinkedHashMap<>();   // 일자 -> [net 합]

        void add(double net, String alertDate) {
            sumNet += net;
            if (net > 0) wins++;
            count++;
            if (alertDate == null) return;
            cntByDay.computeIfAbsent(alertDate, k -> new int[1])[0]++;
            sumByDay.computeIfAbsent(alertDate, k -> new double[1])[0] += net;
        }

        /** net 합 기여 절대값이 가장 큰 거래일 — 그 하루를 빼고도 결론이 유지되는지 보기 위함(perf-gate의 LOO와 동일 사상). */
        String topDay() {
            return sumByDay.entrySet().stream()
                    .max(java.util.Comparator.comparingDouble(e -> Math.abs(e.getValue()[0])))
                    .map(Map.Entry::getKey).orElse(null);
        }
    }

    /** 진입분보다 평균수익이 높은 탈락 사유가 있으면 "완화 검토" 힌트. */
    private String buildHint(Stat entered, List<Stat> rejected) {
        if (entered == null || entered.avgNetReturnPct() == null || entered.samples() == 0) {
            return "진입 표본 부족 — 비교 보류";
        }
        List<String> better = new ArrayList<>();
        for (Stat r : rejected) {
            // ⚠️ 비교는 반드시 '겹치는 거래일 구간'으로 정렬된 값으로 — 전체 구간끼리 빼면 기간 차이를 조건 차이로 오독한다.
            // 정렬값이 없으면(진입일 미상 등) 비교를 생략한다: 부풀려진 숫자로 필터를 흔드는 것보다 침묵이 낫다.
            if (r.samples() < 10 || r.edgeVsEnteredPct() == null || r.edgeVsEnteredPct() <= 0) continue;
            better.add(String.format("%s(%.2f%%>%.2f%%, n=%d, %s~%s)", r.group(),
                    r.alignedNetPct(), r.alignedEnteredNetPct(), r.alignedSamples(), r.alignedFrom(), r.alignedTo()));
        }
        return better.isEmpty() ? "진입분이 우위 — 현 필터 유지"
                : "⚠️ 미진입이 더 나음(필터 완화 검토): " + String.join(", ", better);
    }

    private Long resultPrice(TradeOutcome o, String horizon) {
        return switch (horizon == null ? "close" : horizon) {
            case "nextClose" -> o.getPriceNextClose();
            case "d2" -> o.getPriceD2();
            case "d3" -> o.getPriceD3();
            case "p10" -> o.getPrice10min();
            case "p30" -> o.getPrice30min();
            default -> o.getPriceClose();
        };
    }

    /** perf-gate와 동일 — 전략 권장 청산마크(holdMinutes) ±허용범위 내 outcome별 근접 마크가. */
    private Map<Long, Long> buildExitPrices(String strategy) {
        int exitMark = holdTimeProvider.holdMinutes(strategy);
        Map<Long, Long> price = new java.util.HashMap<>();
        Map<Long, Integer> bestDist = new java.util.HashMap<>();
        for (com.stockadvisor.domain.OutcomeSample s : outcomeSampleRepository.findByStrategyAndMarkMinutesBetween(
                strategy, exitMark - EXIT_MARK_TOLERANCE_MIN, exitMark + EXIT_MARK_TOLERANCE_MIN)) {
            int d = Math.abs(s.getMarkMinutes() - exitMark);
            Integer cur = bestDist.get(s.getOutcomeId());
            if (cur == null || d < cur) { bestDist.put(s.getOutcomeId(), d); price.put(s.getOutcomeId(), s.getPrice()); }
        }
        return price;
    }

    private Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** null 허용 오버로드 — LOO net은 표본이 한 날뿐이면 null(판정 불가). */
    private Double round2(Double v) {
        return v == null ? null : round2((double) v);
    }
}
