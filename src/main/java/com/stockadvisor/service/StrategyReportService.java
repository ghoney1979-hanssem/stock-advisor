package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 전략별 가상매수 성과 비교 리포트. horizon별 평균 수익률·승률·표본수를 집계한다.
 */
@Service
public class StrategyReportService {

    private final TradeOutcomeRepository tradeOutcomeRepository;

    public StrategyReportService(TradeOutcomeRepository tradeOutcomeRepository) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
    }

    /** horizon 한 구간 성과. */
    public record HorizonStat(int samples, Double avgReturnPct, Double winRatePct) {
    }

    /** 전략 한 개 성과. */
    public record StrategyPerformance(String strategy, int totalSamples, Map<String, HorizonStat> horizons) {
    }

    public List<StrategyPerformance> report() {
        Map<String, List<TradeOutcome>> byStrategy = new TreeMap<>();
        for (TradeOutcome o : tradeOutcomeRepository.findAll()) {
            byStrategy.computeIfAbsent(o.getStrategy(), k -> new java.util.ArrayList<>()).add(o);
        }

        List<StrategyPerformance> result = new java.util.ArrayList<>();
        byStrategy.forEach((strategy, outcomes) -> {
            Map<String, HorizonStat> horizons = new LinkedHashMap<>();
            horizons.put("+5min", stat(outcomes, TradeOutcome::getPrice5min));
            horizons.put("+10min", stat(outcomes, TradeOutcome::getPrice10min));
            horizons.put("+30min", stat(outcomes, TradeOutcome::getPrice30min));
            horizons.put("당일종가", stat(outcomes, TradeOutcome::getPriceClose));
            horizons.put("익일종가", stat(outcomes, TradeOutcome::getPriceNextClose));
            result.add(new StrategyPerformance(strategy, outcomes.size(), horizons));
        });
        return result;
    }

    /** 특정 horizon 가격이 수집된 표본에 대해 평균 수익률·승률 계산. */
    private HorizonStat stat(List<TradeOutcome> outcomes, Function<TradeOutcome, Long> priceAt) {
        int n = 0, wins = 0;
        double sumReturn = 0;
        for (TradeOutcome o : outcomes) {
            Long price = priceAt.apply(o);
            if (price == null || o.getBuyPrice() <= 0) continue;
            double ret = (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100;
            sumReturn += ret;
            if (price > o.getBuyPrice()) wins++;
            n++;
        }
        if (n == 0) {
            return new HorizonStat(0, null, null);
        }
        return new HorizonStat(n, round2(sumReturn / n), round2(100.0 * wins / n));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
