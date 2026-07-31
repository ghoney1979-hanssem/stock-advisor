package com.stockadvisor.service;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * C(눌림목·역추세) 핵심 엣지의 일봉 백테스트.
 *
 * <p>전 종목 ~30거래일 일봉에서, 어느 날 종가 등락률이 −minDrop ~ −maxDrop이고 그날 거래량이 직전 평균 ×volMult 이상이면
 * (= C의 일봉 근사 진입) 그날 종가에 샀다고 가정하고 D+1/D+2/D+3 거래일 종가 수익(net)을 집계한다.</p>
 *
 * <p>⚠️ 한계: ① 일봉이라 분봉 반등확인·시간보정 거래량·추천점수·건전성/유동성 필터 미반영(실제 C보다 느슨)
 * ② ~30거래일이라 최근 한 국면 위주 ③ 현재 워치리스트만(상장폐지 생존편향) ④ 종가 매수·매도 가정(슬리피지 일부만).
 * → 핵심 엣지의 빠른 1차 판독용. 포워드 검증을 대체하지 않음.</p>
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final KisApiClient kisApiClient;
    private final CompanyRepository companyRepository;
    private final SignalProperties props;
    private final ExecutionCostModel executionCostModel;
    private final double roundTripCostPct;

    public BacktestService(KisApiClient kisApiClient, CompanyRepository companyRepository,
                           SignalProperties props, ExecutionCostModel executionCostModel,
                           @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.kisApiClient = kisApiClient;
        this.companyRepository = companyRepository;
        this.props = props;
        this.executionCostModel = executionCostModel;
        this.roundTripCostPct = roundTripCostPct;
    }

    public record HorizonStat(String horizon, int samples, double avgNetReturnPct, double winRatePct) {}
    public record Backtest(String strategy, int stocksScanned, int entries,
                           double lowChangePct, double highChangePct,
                           double volMultiplier, int volLookback, List<HorizonStat> horizons,
                           double roundTripCostPct, String note) {}

    /** 누적기(전략 한 종목 처리 결과). */
    public record Partial(int entries, double[] sum, int[] wins, int[] cnt) {}

    /** C(눌림목) 일봉 백테스트 — 등락률 −maxDrop ~ −minDrop + 거래량 급증. */
    public Backtest meanReversion(Integer limit) {
        return run("MEAN_REVERSION_C", limit,
                -props.meanReversionMaxDrop(), -props.meanReversionMinDrop(), props.volumeMultiplier(),
                "C 일봉 근사(분봉반등·점수·건전성 미반영)");
    }

    /** B(거래량 선행) 일봉 백테스트 — 등락률 횡보(min~maxChange) + 거래량 급증. ⚠️ B는 인트라데이성이라 일봉 근사가 더 거칢. */
    public Backtest volumeLeading(Integer limit) {
        return run("VOLUME_LEADING_B", limit,
                props.volumeLeadingMinChange(), props.volumeLeadingMaxChange(), props.volumeMultiplier(),
                "B 일봉 근사 ⚠️ 본래 인트라데이(횡보+분봉 거래량급증)라 일봉 재현 한계 큼");
    }

    /** 공통 백테스트: 등락률 [lowChange, highChange] + 거래량 급증일 진입 → D+1~3 종가 net. @param limit null=전체. */
    private Backtest run(String strategy, Integer limit, double lowChange, double highChange, double volMult, String note) {
        int volLookback = Math.max(5, props.lookbackDays());
        double[] sum = new double[3];
        int[] wins = new int[3], cnt = new int[3];
        int scanned = 0, entries = 0;

        List<String> codes = companyRepository.findAllStockCodes();
        if (limit != null && limit > 0 && limit < codes.size()) codes = codes.subList(0, limit);

        for (String code : codes) {
            scanned++;
            try {
                List<KisDailyPriceResponse.DailyPrice> rows = kisApiClient.fetchDailyPrices(code).output();
                if (rows == null || rows.size() < volLookback + 2) continue;
                List<double[]> bars = new ArrayList<>();   // 최신일 우선 → 오래된→최신 [close, volume]
                for (int j = rows.size() - 1; j >= 0; j--) {
                    double c = parse(rows.get(j).closePrice());
                    double v = parse(rows.get(j).accumulatedVolume());
                    if (c > 0) bars.add(new double[]{c, v});
                }
                Partial p = runBarsBand(bars, lowChange, highChange, volMult, volLookback, this::costPct);
                entries += p.entries();
                for (int h = 0; h < 3; h++) { sum[h] += p.sum()[h]; wins[h] += p.wins()[h]; cnt[h] += p.cnt()[h]; }
            } catch (Exception ignore) { /* 종목별 실패 스킵 */ }
        }

        List<HorizonStat> hs = new ArrayList<>();
        String[] hl = {"D+1종가", "D+2종가", "D+3종가"};
        for (int h = 0; h < 3; h++) {
            hs.add(new HorizonStat(hl[h], cnt[h],
                    cnt[h] == 0 ? 0 : round2(sum[h] / cnt[h]),
                    cnt[h] == 0 ? 0 : round2(100.0 * wins[h] / cnt[h])));
        }
        log.info("{} 일봉 백테스트: 종목 {} / 진입 {} / D+1 표본 {}", strategy, scanned, entries, cnt[0]);
        return new Backtest(strategy, scanned, entries, lowChange, highChange, volMult, volLookback, hs,
                roundTripCostPct, note + ", 최근 ~30거래일 1국면, 종가매수 가정 — 핵심엣지 1차 판독용");
    }

    /**
     * 종가 시계열(오래된→최신, [close, volume])에 C 일봉 조건을 적용해 D+1~3 net 수익을 누적(순수 함수).
     * @param costFn 매수가 → 왕복비용(%)
     */
    public static Partial runBars(List<double[]> bars, double minDrop, double maxDrop, double volMult,
                                  int volLookback, java.util.function.DoubleUnaryOperator costFn) {
        return runBarsBand(bars, -maxDrop, -minDrop, volMult, volLookback, costFn);   // C: −maxDrop ≤ change ≤ −minDrop
    }

    /**
     * 종가 시계열에 "등락률 [lowChange, highChange] + 거래량 급증" 진입을 적용해 D+1~3 net 수익 누적(순수 함수).
     * C는 (−maxDrop, −minDrop), B는 (minChange, maxChange) 밴드.
     */
    public static Partial runBarsBand(List<double[]> bars, double lowChange, double highChange, double volMult,
                                      int volLookback, java.util.function.DoubleUnaryOperator costFn) {
        double[] sum = new double[3];
        int[] wins = new int[3], cnt = new int[3];
        int entries = 0;
        int n = bars.size();
        for (int i = volLookback; i < n - 1; i++) {
            double close = bars.get(i)[0], prev = bars.get(i - 1)[0];
            if (prev <= 0) continue;
            double change = (close - prev) / prev * 100;
            if (change < lowChange || change > highChange) continue;   // lowChange ≤ change ≤ highChange
            double volAvg = 0;
            for (int k = i - volLookback; k < i; k++) volAvg += bars.get(k)[1];
            volAvg /= volLookback;
            if (volAvg <= 0 || bars.get(i)[1] < volMult * volAvg) continue;   // 거래량 급증
            entries++;
            double cst = costFn.applyAsDouble(close);
            for (int h = 1; h <= 3; h++) {
                if (i + h < n) {
                    double ret = (bars.get(i + h)[0] - close) / close * 100 - cst;
                    sum[h - 1] += ret; if (ret > 0) wins[h - 1]++; cnt[h - 1]++;
                }
            }
        }
        return new Partial(entries, sum, wins, cnt);
    }

    private double costPct(double buy) {
        return roundTripCostPct + executionCostModel.estimateRoundTripSlippagePct((long) buy);
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
