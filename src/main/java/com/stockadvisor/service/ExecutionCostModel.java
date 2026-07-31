package com.stockadvisor.service;

import com.stockadvisor.config.properties.ExecutionCostProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 체결비용(슬리피지·유동성) 모델 (레이어 4 — 비용 현실화).
 *
 * <p>위탁수수료·거래세(고정 round-trip 0.18%)와 별개로, 모든 net 수치에 반영할 <b>슬리피지</b>를 추정하고
 * <b>유동성</b>으로 거래 가능 종목을 거른다. 호가창 API 없이 <b>KRX 호가단위(tick)</b>로 스프레드를 추정한다
 * (가격대별 tick / 가격 = 최소 왕복 횡단 비용). ⚠️ 소형주 실비용은 더 클 수 있어 {@code spread-ticks}로 보수화.</p>
 */
@Service
public class ExecutionCostModel {

    private final ExecutionCostProperties props;

    public ExecutionCostModel(ExecutionCostProperties props) {
        this.props = props;
    }

    /** 추정 왕복 슬리피지(%) — 스프레드(tick기반) + 추가. 비활성이면 0(비용 미반영). */
    public double estimateRoundTripSlippagePct(long price) {
        if (!props.enabled() || price <= 0) return 0.0;
        return roundTripSpreadPct(price) + props.baseSlippagePct();
    }

    /** 왕복 스프레드 비용(%) = spreadTicks × tick / 가격 × 100. */
    public double roundTripSpreadPct(long price) {
        if (price <= 0) return 0.0;
        return props.spreadTicks() * tickSize(price) / price * 100.0;
    }

    /**
     * 실측 호가 스프레드 기반 왕복 슬리피지(%) = (매도호가1-매수호가1)/중간가 × 100 + base.
     * 비활성/무효 호가면 null → 호출측이 tick 추정으로 fallback.
     */
    public Double roundTripSlippagePctFromSpread(long bestAsk, long bestBid) {
        if (!props.enabled() || bestAsk <= 0 || bestBid <= 0 || bestAsk < bestBid) return null;
        double mid = (bestAsk + bestBid) / 2.0;
        if (mid <= 0) return null;
        return (bestAsk - bestBid) / mid * 100.0 + props.baseSlippagePct();
    }

    /** 거래 가능 여부(외부 산출 슬리피지 사용): 거래대금 하한 + 슬리피지 상한. 비활성이면 항상 true. */
    public boolean tradable(long turnoverKrw, double roundTripSlippagePct) {
        if (!props.enabled()) return true;
        if (turnoverKrw < props.minTurnoverKrw()) return false;
        return roundTripSlippagePct <= props.maxRoundTripSlippagePct();
    }

    /** 시장충격 캡 활성 여부. */
    public boolean impactEnabled() {
        return props.enabled() && props.maxImpactPct() > 0;
    }

    /**
     * 매수 주문이 시장충격 상한(maxImpactPct) 내에서 흡수 가능한 최대 수량 — 매도호가 잠식(walk).
     * 최우선 매도호가 대비 +maxImpactPct% 이내 가격대 호가 잔량의 합. asks는 가격↑(best 먼저) 순.
     */
    public long maxSharesWithinImpact(List<com.stockadvisor.market.KisApiClient.Level> asks) {
        if (asks == null || asks.isEmpty()) return 0;
        long bestAsk = asks.get(0).price();
        if (bestAsk <= 0) return 0;
        long shares = 0;
        for (com.stockadvisor.market.KisApiClient.Level lv : asks) {
            double levelImpactPct = (double) (lv.price() - bestAsk) / bestAsk * 100.0;
            if (levelImpactPct > props.maxImpactPct() + 1e-9) break;   // 충격 상한 초과 가격대 → 중단
            shares += lv.qty();
        }
        return shares;
    }

    /**
     * 매수 shares 체결 시 시장충격(%) = 체결 VWAP가 최우선 매도호가 대비 몇 % 위인가.
     * 잔량 부족(shares > 총 호가잔량)이면 null(전량 체결 불가). 가시화/진단용.
     */
    public Double marketImpactPct(List<com.stockadvisor.market.KisApiClient.Level> asks, long shares) {
        if (asks == null || asks.isEmpty() || shares <= 0) return null;
        long bestAsk = asks.get(0).price();
        long remain = shares;
        long cost = 0;
        for (com.stockadvisor.market.KisApiClient.Level lv : asks) {
            long take = Math.min(remain, lv.qty());
            cost += take * lv.price();
            remain -= take;
            if (remain <= 0) break;
        }
        if (remain > 0) return null;   // 호가 잔량으로 전량 못 채움
        double vwap = (double) cost / shares;
        return (vwap - bestAsk) / bestAsk * 100.0;
    }

    /** 1일 거래대금(원) ≈ 누적거래량 × 현재가. */
    public long turnoverKrw(long volume, long price) {
        return Math.max(0, volume) * Math.max(0, price);
    }

    /** 거래 가능 여부(유동성 필터): 거래대금 하한 + 추정 슬리피지 상한. 비활성이면 항상 true. */
    public boolean tradable(long price, long turnoverKrw) {
        if (!props.enabled()) return true;
        if (turnoverKrw < props.minTurnoverKrw()) return false;
        return estimateRoundTripSlippagePct(price) <= props.maxRoundTripSlippagePct();
    }

    /** 가시화/미리보기용 비용 분해. */
    public record CostBreakdown(long price, long turnoverKrw, long tickSize,
                                double roundTripSpreadPct, double roundTripSlippagePct,
                                boolean tradable) {}

    public CostBreakdown breakdown(long price, long turnoverKrw) {
        return new CostBreakdown(price, turnoverKrw, tickSize(price),
                round4(roundTripSpreadPct(price)), round4(estimateRoundTripSlippagePct(price)),
                tradable(price, turnoverKrw));
    }

    /**
     * KRX 호가단위(2023~ 코스피·코스닥 통일). 가격대별 최소 가격변동폭(원).
     */
    public static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }

    /**
     * 지정가를 KRX 호가단위 격자에 스냅. 격자 밖 가격은 KIS가 "주식주문호가단위 오류"로 거부하므로
     * 주문 전송 전 반드시 정렬해야 한다. roundUp=true(매수)면 올림, false(매도)면 내림 → 스프레드를
     * ≤1틱 넘겨 체결성을 높인다(미체결은 취소·추격이 별도 처리). 이미 격자에 맞으면 그대로.
     */
    public static long snapToTick(long price, boolean roundUp) {
        if (price <= 0) return price;
        long tick = tickSize(price);
        long rem = price % tick;
        if (rem == 0) return price;
        long floor = price - rem;
        return roundUp ? floor + tick : floor;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
