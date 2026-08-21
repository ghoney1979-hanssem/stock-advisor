package com.stockadvisor.service;

import com.stockadvisor.domain.UniverseSnapshot;
import com.stockadvisor.repository.UniverseSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 유니버스 횡단면 분석 — <b>P(승자|feature)</b>를 base rate 대비 lift로 측정한다.
 *
 * <p><b>왜 이 분석만 다른가</b>: {@code feature-mining}·{@code control-analysis}가 읽는
 * {@link com.stockadvisor.domain.TradeOutcome}은 진입분이든 대조군이든 전부 <b>거래량 급증 모집단 안</b>에서만
 * 뽑힌다. 그래서 그 데이터로는 "거래량 급증이라는 스크리닝 자체가 옳은가"를 물을 수 없다 — 분모가 없기 때문이다.
 * {@link UniverseSnapshot}은 정해진 시각마다 워치리스트 <b>전 종목</b>을 남기므로, 이 서비스가 그 분모 위에서
 * feature 구간별 성과를 base rate와 비교한다(cross-sectional factor research).</p>
 *
 * <p><b>lift가 주지표</b>: bucket net − 전체 base net. 절대 net이 아니라 lift로 봐야 하는 이유는, 어떤 날은
 * 시장 전체가 −2%라 모든 bucket이 음수로 나오기 때문이다. 그런 날의 −1.5% bucket은 나쁜 게 아니라
 * <b>상대적으로 좋은</b> 것이고, 매매 가능한 엣지는 그 상대치에 있다.</p>
 *
 * <p>⚠️ <b>여기의 수익은 "그 시점에 샀다면"의 가정치</b>다 — 실제 진입 가능성(호가·유동성), 청산 규칙,
 * 실측 슬리피지가 반영돼 있지 않다. 왕복비용과 tick 기반 추정 슬리피지는 차감한다(net).</p>
 *
 * <p>⚠️ 사후 타깃은 이후 전수 스캔이 채우므로 +90분은 실제 90~102분 근사다(일관된 방향의 근사라 횡단면
 * 비교에는 무해). 종가는 2026-08-19부터 <b>익일 일봉의 확정 종가</b>로 채워 근사가 아니다 — 그 이전
 * 수집분은 순회 순서에 종속된 편향 표본이라 해석에 주의.</p>
 */
@Service
public class UniverseAnalysisService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final double MAX_DAY_SHARE_PCT = 80.0;

    /** 이 날짜부터 종가가 익일 확정 종가로 채워진다(그 이전은 순회 순서 편향 표본). */
    static final String CLOSE_FIX_DATE = "20260819";

    private final UniverseSnapshotRepository repository;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;

    public UniverseAnalysisService(UniverseSnapshotRepository repository,
                                   ExecutionCostModel executionCostModel,
                                   @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.repository = repository;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
    }

    /**
     * @param sharePct   이 bucket이 유니버스에서 차지하는 비중(%) — 희소할수록 과적합 위험
     * @param liftNetPct bucket net − base net(%p). <b>이 분석의 주지표</b>
     * @param liftWinPct bucket 승률 − base 승률(%p)
     * @param clustered  단일일 점유율이 문턱을 넘으면 true(이벤트 1개를 표본 n개로 오인 방지)
     */
    public record Bucket(String feature, String range, int n, double sharePct, int distinctDays,
                         double maxDaySharePct, double netAvgPct, double winRatePct,
                         double liftNetPct, double liftWinPct, boolean clustered) {}

    public record FeatureSlice(String feature, List<Bucket> buckets) {}

    public record UniverseReport(int rows, int scored, String horizon, String market, String snapTime,
                                 String since, String until, int minSamples, int distinctDays,
                                 double baseNetPct, double baseWinRatePct,
                                 List<Bucket> highlights, List<Bucket> avoid, List<FeatureSlice> features,
                                 List<String> caveats) {}

    /** 숫자 feature bin 정의: 이름·추출자·경계(오름차순). */
    private record NumBin(String name, ToDoubleFunction<UniverseSnapshot> f, double[] edges) {}

    /** 범주형 feature 정의. */
    private record CatBin(String name, Function<UniverseSnapshot, String> f) {}

    private static final List<NumBin> NUM = List.of(
            new NumBin("당일등락률%", UniverseSnapshot::getChangeRate, new double[]{-3, 0, 2, 5, 10}),
            new NumBin("거래량배수", UniverseSnapshot::getVolumeRatio, new double[]{0.5, 1, 2, 4, 8}),
            new NumBin("종목갭%", UniverseSnapshot::getGapPct, new double[]{-2, 0, 2, 5}),
            new NumBin("ATR%", UniverseSnapshot::getAtrPct, new double[]{2, 4, 7}),
            new NumBin("고가거리%", UniverseSnapshot::getDistHighPct, new double[]{-20, -10, -5, 0}),
            new NumBin("ret5d%", UniverseSnapshot::getRet5dPct, new double[]{-5, 0, 5, 10}),
            new NumBin("MA20이격%", UniverseSnapshot::getMaDistPct, new double[]{-10, -3, 0, 3, 10})
    );

    private static final List<CatBin> CAT = List.of(
            // ⚠️ 이 축이 이 분석의 존재 이유 — 시스템 전체가 "거래량 급증"으로 후보를 좁히는데,
            //    그 스크리닝이 실제로 승률을 올리는지는 급증 밖 표본이 없어 지금까지 물을 수 없었다.
            new CatBin("거래량급증", s -> s.isVolumeSpike() ? "급증(게이트통과)" : "미급증"),
            new CatBin("MA돌파", s -> s.isMaCrossUp() ? "Y" : "N"),
            new CatBin("RSI돌파", s -> s.isRsiCrossUp() ? "Y" : "N"),
            new CatBin("스퀴즈돌파", s -> s.isSqueezeBreakout() ? "Y" : "N"),
            new CatBin("시장", s -> s.getMarket() == null ? "미상" : s.getMarket()),
            new CatBin("스냅시각", UniverseSnapshot::getSnapTime)
    );

    /**
     * @param horizon m90(+90분) / close(당일종가) / nextClose(익일종가)
     * @param since   snapDate 하한(yyyyMMdd, 포함). null이면 lookbackDays 기준
     * @param until   snapDate 상한(yyyyMMdd, 포함). null이면 제한 없음
     */
    public UniverseReport analyze(int lookbackDays, String horizon, String market, String snapTime,
                                  int minSamples, String since, String until) {
        String cutoff = (since != null && !since.isBlank())
                ? since
                : LocalDate.now(SEOUL).minusDays(lookbackDays).format(YYYYMMDD);
        String h = horizon == null || horizon.isBlank() ? "close" : horizon;

        List<UniverseSnapshot> rows = new ArrayList<>();
        List<Double> nets = new ArrayList<>();
        int total = 0;
        for (UniverseSnapshot s : repository.findAll()) {
            String d = s.getSnapDate();
            if (d == null || d.compareTo(cutoff) < 0) continue;
            if (until != null && !until.isBlank() && d.compareTo(until) > 0) continue;
            if (market != null && !market.isBlank() && !market.equals(s.getMarket())) continue;
            if (snapTime != null && !snapTime.isBlank() && !snapTime.equals(s.getSnapTime())) continue;
            total++;
            Double net = net(s, h);
            if (net == null) continue;      // 사후 타깃 미수집 → 채점 제외
            rows.add(s);
            nets.add(net);
        }

        if (rows.isEmpty()) {
            return new UniverseReport(total, 0, h, market, snapTime, cutoff, until, minSamples, 0,
                    0, 0, List.of(), List.of(), List.of(),
                    List.of("채점 가능한 표본 없음 — 해당 horizon의 사후 타깃이 아직 안 채워졌을 수 있음"));
        }

        double baseNet = nets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double baseWin = 100.0 * nets.stream().filter(v -> v > 0).count() / nets.size();
        int distinctDays = (int) rows.stream().map(UniverseSnapshot::getSnapDate).distinct().count();

        List<FeatureSlice> features = new ArrayList<>();
        for (NumBin nb : NUM) {
            features.add(slice(nb.name(), rows, nets,
                    s -> FeatureMiningService.binLabel(nb.f().applyAsDouble(s), nb.edges()),
                    minSamples, baseNet, baseWin));
        }
        for (CatBin cb : CAT) {
            features.add(slice(cb.name(), rows, nets, cb.f(), minSamples, baseNet, baseWin));
        }

        List<Bucket> all = features.stream().flatMap(f -> f.buckets().stream())
                .filter(b -> !b.clustered()).toList();
        List<Bucket> highlights = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::liftNetPct).reversed()).limit(15).toList();
        List<Bucket> avoid = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::liftNetPct)).limit(10).toList();

        return new UniverseReport(total, rows.size(), h, market, snapTime, cutoff, until, minSamples, distinctDays,
                round2(baseNet), round2(baseWin), highlights, avoid, features, caveats(h, distinctDays, cutoff));
    }

    private FeatureSlice slice(String feature, List<UniverseSnapshot> rows, List<Double> nets,
                               Function<UniverseSnapshot, String> keyOf, int minSamples,
                               double baseNet, double baseWin) {
        Map<String, List<Integer>> byBucket = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            byBucket.computeIfAbsent(keyOf.apply(rows.get(i)), k -> new ArrayList<>()).add(i);
        }
        List<Bucket> buckets = new ArrayList<>();
        byBucket.forEach((range, idx) -> {
            if (idx.size() < minSamples) return;
            double sum = 0;
            int wins = 0;
            Map<String, Integer> byDay = new LinkedHashMap<>();
            for (int i : idx) {
                double v = nets.get(i);
                sum += v;
                if (v > 0) wins++;
                byDay.merge(rows.get(i).getSnapDate(), 1, Integer::sum);
            }
            int n = idx.size();
            double net = sum / n;
            double win = 100.0 * wins / n;
            double maxShare = FeatureMiningService.maxSharePct(byDay, n);
            buckets.add(new Bucket(feature, range, n, round2(100.0 * n / rows.size()), byDay.size(),
                    round2(maxShare), round2(net), round2(win),
                    round2(net - baseNet), round2(win - baseWin), maxShare > MAX_DAY_SHARE_PCT));
        });
        buckets.sort(Comparator.comparingDouble(Bucket::liftNetPct).reversed());
        return new FeatureSlice(feature, buckets);
    }

    /** 가정 진입 net(%) — 왕복비용 + tick 기반 추정 슬리피지 차감. 사후 타깃 미수집이면 null. */
    private Double net(UniverseSnapshot s, String horizon) {
        Long exit = switch (horizon) {
            case "m90" -> s.getPrice90m();
            case "nextClose" -> s.getPriceNextClose();
            default -> s.getPriceClose();
        };
        if (exit == null || s.getPrice() <= 0) return null;
        double slip = executionCostModel.estimateRoundTripSlippagePct(s.getPrice());
        return (double) (exit - s.getPrice()) / s.getPrice() * 100 - roundTripCostPct - slip;
    }

    /** 해석을 그르치기 쉬운 지점을 응답에 실어 보낸다(수치만 보고 오독하는 것 방지). */
    private List<String> caveats(String horizon, int distinctDays, String cutoff) {
        List<String> out = new ArrayList<>();
        if (distinctDays < 10) {
            out.add("⚠️ 거래일 " + distinctDays + "일뿐 — lift 부호가 며칠 사이 뒤집힐 수 있다. "
                    + "since/until로 전·후반을 갈라 부호가 유지되는지 반드시 확인할 것");
        }
        if ("m90".equals(horizon)) {
            out.add("+90분 타깃은 이후 전수 스캔(12분 주기)이 채워 실제 90~102분 근사 — "
                    + "절대 수익 해석 시 유의(횡단면 비교엔 무해)");
        }
        if ("close".equals(horizon) && cutoff.compareTo(CLOSE_FIX_DATE) < 0) {
            out.add("⚠️ " + CLOSE_FIX_DATE + " 이전 종가는 순회 순서에 종속된 편향 표본(KOSPI 36%/KOSDAQ 92%) — "
                    + "since=" + CLOSE_FIX_DATE + " 로 잘라서 볼 것");
        }
        out.add("여기의 수익은 '그 시점에 샀다면'의 가정치 — 실제 진입 가능성·청산 규칙·실측 슬리피지 미반영");
        return out;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
