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
import java.util.function.Function;

/**
 * 전략별 가상매수 결과를 "수익 vs 손실"로 나눠, 진입 시점 feature가 어떻게 다른지 분석한다.
 * (어떤 조건의 종목이 수익을 냈는지 사후 진단)
 *
 * <p>수익/손실 분류·평균수익률·국면별 통계는 왕복 매매비용(stockadvisor.cost.round-trip-pct, 기본 0.18%)
 * 차감 순수익 기준. (numericFeatures의 avgWin/avgLoss는 feature 값 자체라 비용과 무관 — 그룹 경계만 net&gt;0으로 이동.)</p>
 */
@Service
public class OutcomeAnalysisService {

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final ExitHorizonPriceResolver exitResolver;   // horizon="exit"(게이트 동일 청산시점) 지원 — 반사실 비교 정합
    private final double roundTripCostPct;   // 왕복 매매비용(수수료+거래세) — 슬리피지는 ExecutionCostModel 별도 가산

    public OutcomeAnalysisService(TradeOutcomeRepository tradeOutcomeRepository,
                                  ExecutionCostModel executionCostModel,
                                  ExitHorizonPriceResolver exitResolver,
                                  @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.exitResolver = exitResolver;
        this.roundTripCostPct = roundTripCostPct;
    }

    public record NumericFeature(String name, Double avgWin, Double avgLoss, Double diff) {}
    public record CategoryWinRate(String value, int samples, double winRatePct) {}
    public record RegimeStat(String regime, int samples, Double avgReturnPct, Double winRatePct) {}
    public record StrategyAnalysis(String strategy, String horizon, int analyzed,
                                   int winners, int losers, double winRatePct, double avgReturnPct,
                                   List<NumericFeature> numericFeatures,
                                   List<CategoryWinRate> byMarket, List<CategoryWinRate> bySector,
                                   List<RegimeStat> byMarketRegime, double roundTripCostPct) {}

    /** @param horizon exit(게이트 동일=권장청산마크·스윙 nextClose)/close(당일종가)/nextClose(D+1)/d2/d3/p10/p30 */
    public List<StrategyAnalysis> analyze(String horizon) {
        Map<String, List<TradeOutcome>> byStrategy = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            byStrategy.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        }

        List<StrategyAnalysis> result = new ArrayList<>();
        byStrategy.forEach((strategy, all) -> {
            // horizon="exit"이면 게이트와 동일하게 전략별 청산시점 가격으로(스윙=nextClose). 그 외는 종가 필드.
            String effHz = "exit".equals(horizon) ? exitResolver.horizonFor(strategy, "exit") : horizon;
            java.util.function.Function<TradeOutcome, Long> px = exitResolver.priceFor(strategy, effHz);
            // 결과 가격이 수집됐고 진입 feature가 있는 건만 분석 대상
            List<TradeOutcome> wins = new ArrayList<>();
            List<TradeOutcome> losses = new ArrayList<>();
            Map<String, double[]> regimeAgg = new LinkedHashMap<>();   // 국면 -> [sumReturn, wins, count]
            double sumReturn = 0;
            int analyzed = 0;
            for (TradeOutcome o : all) {
                if (o.isControl()) continue;   // 대조군(미진입)은 진입분 분석에서 제외(control-analysis에서 별도 비교)
                Long price = px.apply(o);
                if (price == null || o.getBuyPrice() <= 0 || o.getEntryChangeRate() == null) continue;
                double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()   // 진입시 실측 스프레드
                        : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());   // 없으면 tick 추정
                double ret = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - (roundTripCostPct + slip);   // 순수익(수수료+슬리피지)
                sumReturn += ret;
                analyzed++;
                if (ret > 0) wins.add(o); else losses.add(o);
                double[] rg = regimeAgg.computeIfAbsent(regimeOf(o), k -> new double[3]);
                rg[0] += ret; if (ret > 0) rg[1] += 1; rg[2] += 1;
            }
            if (analyzed == 0) {
                result.add(new StrategyAnalysis(strategy, horizon, 0, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), roundTripCostPct));
                return;
            }
            List<NumericFeature> numeric = new ArrayList<>();
            numeric.add(feature("진입등락률%", wins, losses, TradeOutcome::getEntryChangeRate));
            numeric.add(feature("뉴스1h건수", wins, losses,
                    o -> o.getEntryNewsCnt1h() == null ? null : o.getEntryNewsCnt1h().doubleValue()));
            numeric.add(feature("뉴스경과분", wins, losses,
                    o -> o.getEntryNewsAgeMin() == null ? null : o.getEntryNewsAgeMin().doubleValue()));
            numeric.add(feature("체결강도%", wins, losses, TradeOutcome::getEntryExecStrength));
            numeric.add(feature("거래량배수", wins, losses, TradeOutcome::getEntryVolumeRatio));
            numeric.add(feature("추천점수", wins, losses, TradeOutcome::getEntryRecScore));
            numeric.add(feature("PER", wins, losses, TradeOutcome::getEntryPer));
            numeric.add(feature("PBR", wins, losses, TradeOutcome::getEntryPbr));
            numeric.add(feature("시총(억)", wins, losses, o -> o.getEntryMarketCap() == null ? null : o.getEntryMarketCap().doubleValue()));

            List<RegimeStat> byRegime = new ArrayList<>();
            regimeAgg.forEach((regime, acc) -> {
                int n = (int) acc[2];
                byRegime.add(new RegimeStat(regime, n, round2(acc[0] / n), round2(100.0 * acc[1] / n)));
            });

            result.add(new StrategyAnalysis(strategy, horizon, analyzed,
                    wins.size(), losses.size(), round2(100.0 * wins.size() / analyzed),
                    round2(sumReturn / analyzed),
                    numeric,
                    categoryWinRate(wins, losses, TradeOutcome::getEntryMarket),
                    categoryWinRate(wins, losses, TradeOutcome::getEntrySector),
                    byRegime, roundTripCostPct));
        });
        return result;
    }

    /** 진입시점 시장 등락률로 국면 분류. */
    private String regimeOf(TradeOutcome o) {
        Double c = o.getEntryMarketChange();
        if (c == null) return "미상";
        return c >= 0 ? "상승장" : "하락장";
    }

    private NumericFeature feature(String name, List<TradeOutcome> wins, List<TradeOutcome> losses,
                                   Function<TradeOutcome, Double> f) {
        Double aw = avg(wins, f);
        Double al = avg(losses, f);
        Double diff = (aw != null && al != null) ? round2(aw - al) : null;
        return new NumericFeature(name, aw, al, diff);
    }

    private Double avg(List<TradeOutcome> list, Function<TradeOutcome, Double> f) {
        double sum = 0; int n = 0;
        for (TradeOutcome o : list) {
            Double v = f.apply(o);
            if (v == null) continue;
            sum += v; n++;
        }
        return n == 0 ? null : round2(sum / n);
    }

    /** 카테고리(시장/업종)별 승률. */
    private List<CategoryWinRate> categoryWinRate(List<TradeOutcome> wins, List<TradeOutcome> losses,
                                                  Function<TradeOutcome, String> key) {
        Map<String, int[]> agg = new LinkedHashMap<>();   // value -> [wins, total]
        for (TradeOutcome o : wins) {
            String k = orNull(key.apply(o));
            agg.computeIfAbsent(k, x -> new int[2]);
            agg.get(k)[0]++; agg.get(k)[1]++;
        }
        for (TradeOutcome o : losses) {
            String k = orNull(key.apply(o));
            agg.computeIfAbsent(k, x -> new int[2]);
            agg.get(k)[1]++;
        }
        List<CategoryWinRate> out = new ArrayList<>();
        agg.forEach((v, wt) -> out.add(new CategoryWinRate(v, wt[1], round2(100.0 * wt[0] / wt[1]))));
        out.sort((a, b) -> Integer.compare(b.samples(), a.samples()));
        return out;
    }

    private String orNull(String s) {
        return (s == null || s.isBlank()) ? "(미상)" : s;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
