package com.stockadvisor.service;

import com.stockadvisor.domain.Order;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 보유 점수(2026-07-23) — 미청산 포지션의 "계속 들고갈만한가"를 7신호 합의로 관찰하는 조회 전용 지표.
 *
 * <p>⚠️ <b>청산 결정에 연결하지 않는다.</b> 7/23 what-if 검증에서 신호 합의는 "마크 시점 승자 식별"에는
 * 강력(3+군이 +1.5~2%p 우위)했지만 <b>연장·조기청산 양방향 모두 엣지가 없었다</b>(권장 마크가 국소 최적).
 * 이 서비스는 ① 사용자 관찰(지금 상태의 근거 분해) ② 표본 축적 후 조건부 청산 재검의 재료로만 쓴다.</p>
 *
 * <p>비용: 조회 시에만 포지션당 분봉 1콜 + VWAP 1콜 + 체결강도 1콜(온디맨드 — 스케줄 부담 0).
 * 지수흐름·시장폭은 TTL 캐시 재사용.</p>
 */
@Service
public class HoldScoreService {

    private static final Logger log = LoggerFactory.getLogger(HoldScoreService.class);

    private final OrderRepository orderRepository;
    private final KisApiClient kisApiClient;
    private final MarketRegimeService marketRegimeService;
    private final MarketBreadthService marketBreadthService;
    private final CompanyRepository companyRepository;

    public HoldScoreService(OrderRepository orderRepository, KisApiClient kisApiClient,
                            MarketRegimeService marketRegimeService, MarketBreadthService marketBreadthService,
                            CompanyRepository companyRepository) {
        this.orderRepository = orderRepository;
        this.kisApiClient = kisApiClient;
        this.marketRegimeService = marketRegimeService;
        this.marketBreadthService = marketBreadthService;
        this.companyRepository = companyRepository;
    }

    public record Signal(String name, Boolean pass, String detail) {}

    public record HoldScore(long orderId, String stockCode, String name, String strategy,
                            long buyPrice, long price, double unrealizedPct,
                            int yes, int evaluated, String verdict, String note, List<Signal> signals) {}

    /** 현재 미청산 포지션 전부의 보유 점수. 포지션 없으면 빈 리스트. */
    public List<HoldScore> scores() {
        List<HoldScore> out = new ArrayList<>();
        for (Order pos : orderRepository.findOpenBuyPositions()) {
            try {
                out.add(scoreOf(pos));
            } catch (Exception ex) {
                log.debug("보유점수 산출 실패 [{}] {}: {}", pos.getStrategy(), pos.getStockCode(), ex.getMessage());
            }
        }
        return out;
    }

    private HoldScore scoreOf(Order pos) {
        String code = pos.getStockCode();
        long buy = (pos.getAvgFillPrice() != null && pos.getAvgFillPrice() > 0)
                ? pos.getAvgFillPrice() : pos.getRequestedPrice();
        long price = kisApiClient.fetchLatestClose(code);
        List<Signal> sigs = new ArrayList<>();

        // ①② 분봉 모멘텀·거래 활성도 (분봉 1콜 공유)
        List<com.stockadvisor.market.dto.KisMinuteCandleResponse.Candle> candles = null;
        try {
            candles = kisApiClient.fetchMinuteCandles(code).output2();
        } catch (Exception ignore) { /* 신호 미평가 */ }
        Double mom5 = candles == null ? null : MarketRegimeService.momPct(candles, 5);
        sigs.add(new Signal("모멘텀(5분)", mom5 == null ? null : mom5 > 0,
                mom5 == null ? "분봉 미가용" : String.format("%+.2f%%", mom5)));
        sigs.add(activitySignal(candles));

        // ③ VWAP 위 (1콜)
        Double vwap = null;
        try {
            vwap = kisApiClient.fetchVwapVolume(code).vwap();
        } catch (Exception ignore) { /* 신호 미평가 */ }
        sigs.add(new Signal("VWAP 위", vwap == null || vwap <= 0 ? null : price > vwap,
                vwap == null ? "미가용" : String.format("가격 %,d vs VWAP %,.0f", price, vwap)));

        // ④ 고점 되돌림 <1% (추적값 재사용)
        Long peak = pos.getPeakPrice();
        double peakBase = (peak != null && peak > 0) ? Math.max(peak, price) : Math.max(buy, price);
        double retrace = (peakBase - price) / peakBase * 100;
        sigs.add(new Signal("고점되돌림<1%", retrace < 1.0, String.format("고점대비 -%.2f%%", retrace)));

        // ⑤ 지수 mom30 (캐시)
        String market = marketOf(pos);
        Double idxMom = null;
        if (market != null) {
            var flow = marketRegimeService.intradayFlow(market);
            if (flow != null && flow.available()) idxMom = flow.mom30Pct();
        }
        sigs.add(new Signal("지수흐름(mom30)", idxMom == null ? null : idxMom >= 0,
                idxMom == null ? "미가용" : String.format("%+.2f%%", idxMom)));

        // ⑥ 시장폭 (캐시, 신선할 때만)
        Double breadth = (market != null && marketBreadthService.isFresh(40))
                ? marketBreadthService.breadthPct(market) : null;
        sigs.add(new Signal("시장폭≥50%", breadth == null ? null : breadth >= 50,
                breadth == null ? "미신선/미가용" : String.format("상승비율 %.0f%%", breadth)));

        // ⑦ 체결강도 ≥100 (1콜)
        Double strength = null;
        try {
            strength = kisApiClient.fetchCcnl(code).latestStrength();
        } catch (Exception ignore) { /* 신호 미평가 */ }
        sigs.add(new Signal("체결강도≥100", strength == null ? null : strength >= 100,
                strength == null ? "미가용" : String.format("%.0f", strength)));

        int yes = (int) sigs.stream().filter(s -> Boolean.TRUE.equals(s.pass())).count();
        int evaluated = (int) sigs.stream().filter(s -> s.pass() != null).count();
        String name = companyRepository.findById(code)
                .map(com.stockadvisor.domain.Company::getName).orElse(code);
        double ret = buy > 0 ? (double) (price - buy) / buy * 100 : 0;
        return new HoldScore(pos.getId(), code, name, pos.getStrategy(), buy, price, round2(ret),
                yes, evaluated, verdictOf(yes, evaluated),
                "관찰 전용 — 7/23 검증상 신호합의는 승자 식별력만 있고 연장/조기청산 엣지는 없음(권장 마크 청산 유지)",
                sigs);
    }

    private Signal activitySignal(List<com.stockadvisor.market.dto.KisMinuteCandleResponse.Candle> candles) {
        if (candles == null || candles.size() < 10) return new Signal("거래활성도", null, "분봉 부족");
        try {
            // KIS 분봉은 최신순 — 최근 3분 평균 vs 전체(당일 조회분) 평균
            double recent = candles.subList(0, 3).stream().mapToDouble(c -> Double.parseDouble(c.volume())).average().orElse(0);
            double all = candles.stream().mapToDouble(c -> Double.parseDouble(c.volume())).average().orElse(0);
            if (all <= 0) return new Signal("거래활성도", null, "거래량 0");
            double ratio = recent / all;
            return new Signal("거래활성도≥1x", ratio >= 1.0, String.format("최근/평균 %.2f배", ratio));
        } catch (Exception e) {
            return new Signal("거래활성도", null, "파싱 실패");
        }
    }

    private String marketOf(Order pos) {
        String m = pos.getMarket();
        if (m != null && !m.isBlank() && !"INVERSE".equals(m)) return m;
        if ("INVERSE".equals(m)) return null;   // 인버스는 지수 자체 포지션 — 신호 ⑤⑥ 제외
        return companyRepository.findById(pos.getStockCode())
                .map(com.stockadvisor.domain.Company::getMarket).orElse(null);
    }

    /** 합의 판정(순수) — 평가 가능 신호 과반수 기준 3단계. */
    static String verdictOf(int yes, int evaluated) {
        if (evaluated < 3) return "판정불가(신호 부족)";
        double r = (double) yes / evaluated;
        if (r >= 0.7) return "보유 근거 강함";
        if (r >= 0.45) return "중립";
        return "보유 근거 약함";
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
