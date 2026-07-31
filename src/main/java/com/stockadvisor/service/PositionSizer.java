package com.stockadvisor.service;

import com.stockadvisor.config.properties.SizingProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 변동성기반(ATR) 포지션 사이징 (레이어 3.1).
 *
 * <p>거래당 위험예산(순자산 대비 %)을 종목 ATR(평균진폭)로 나눠 수량을 정한다 → 변동성 균등화.
 * 1주문 상한(순자산×maxOrderPct)을 천장으로 두어 절대 초과 금지. ATR 미산출/비활성이면 고정 상한 사이징.</p>
 *
 * <p>ATR은 일별시세의 True Range 평균(고/저 있으면 정식 TR, 없으면 종가간 절대변화로 degrade). {@link #computeAtr}는
 * 순수 함수라 KIS 없이 검증 가능.</p>
 */
@Service
public class PositionSizer {

    private static final Logger log = LoggerFactory.getLogger(PositionSizer.class);

    private final KisApiClient kisApiClient;
    private final TradingPolicyProperties policy;
    private final SizingProperties props;
    private final ExecutionCostModel executionCostModel;

    public PositionSizer(KisApiClient kisApiClient, TradingPolicyProperties policy, SizingProperties props,
                         ExecutionCostModel executionCostModel) {
        this.kisApiClient = kisApiClient;
        this.policy = policy;
        this.props = props;
        this.executionCostModel = executionCostModel;
    }

    /**
     * @param qty     최종 매수 수량, capQty 1주문 상한 기준 수량(천장), atrKrw 산출 ATR(원, 미산출 null)
     * @param basis   산정 근거(로그/가시화용)
     */
    public record Sizing(long qty, long capQty, Double atrKrw, String basis) {}

    /** 종목·가격·순자산으로 매수 수량 산정. */
    public Sizing size(String stockCode, long price, long netAssetsKrw) {
        long capKrw = Math.round(netAssetsKrw * policy.effectiveMaxOrderPct() / 100.0);
        long capQty = price > 0 ? capKrw / price : 0;

        long qty;
        Double atr = null;
        String basis;
        if (!props.atrEnabled() || capQty <= 0) {
            qty = capQty;
            basis = "고정(순자산×" + (long) policy.effectiveMaxOrderPct() + "%)";
        } else {
            atr = atrOf(stockCode);
            if (atr == null || atr <= 0) {
                qty = capQty;
                basis = "고정(ATR 미산출)";
            } else {
                double riskBudget = netAssetsKrw * props.riskPerTradePct() / 100.0;
                double stopDist = props.atrMult() * atr;
                long atrQty = stopDist > 0 ? (long) (riskBudget / stopDist) : 0;
                qty = Math.max(0, Math.min(atrQty, capQty));   // 1주문 상한 천장
                basis = String.format("ATR %.0f원×%.1f, 위험예산 %.2f%%→%d주(상한 %d주)",
                        atr, props.atrMult(), props.riskPerTradePct(), atrQty, capQty);
            }
        }

        // 호가 잔량 기반 시장충격 캡 — 주문이 매도호가를 maxImpactPct 넘게 밀면 흡수 가능 수량으로 축소
        if (executionCostModel.impactEnabled() && qty > 0) {
            KisApiClient.OrderBook ob = safeOrderBook(stockCode);
            if (ob != null) {
                long depthCap = executionCostModel.maxSharesWithinImpact(ob.asks());
                if (depthCap < qty) {
                    basis += String.format(" | 시장충격캡 %d→%d주", qty, Math.max(0, depthCap));
                    qty = Math.max(0, depthCap);
                }
            }
        }
        return new Sizing(qty, capQty, (atr != null && atr > 0) ? round2(atr) : null, basis);
    }

    private KisApiClient.OrderBook safeOrderBook(String stockCode) {
        try {
            return kisApiClient.fetchOrderBook(stockCode);
        } catch (Exception ex) {
            log.debug("호가 조회 실패(시장충격 캡 생략) [{}]: {}", stockCode, ex.getMessage());
            return null;
        }
    }

    private Double atrOf(String stockCode) {
        try {
            List<KisDailyPriceResponse.DailyPrice> rows = kisApiClient.fetchDailyPrices(stockCode).output();
            if (rows == null || rows.isEmpty()) return null;
            // 최신일 우선 → 오래된→최신 순으로 뒤집어 시계열 구성
            List<KisDailyPriceResponse.DailyPrice> chrono = new ArrayList<>(rows);
            java.util.Collections.reverse(chrono);
            return computeAtr(chrono, props.atrPeriod());
        } catch (Exception ex) {
            log.debug("ATR 계산 실패 [{}]: {}", stockCode, ex.getMessage());
            return null;
        }
    }

    /**
     * True Range 평균(ATR). rows는 오래된→최신. 고/저 있으면 정식 TR=max(H-L, |H-prevC|, |L-prevC|),
     * 없으면 |C-prevC| 로 degrade. 최근 period 개 평균. 데이터 부족/파싱 실패 시 null.
     */
    public static Double computeAtr(List<KisDailyPriceResponse.DailyPrice> chrono, int period) {
        List<Double> trs = new ArrayList<>();
        Double prevClose = null;
        for (KisDailyPriceResponse.DailyPrice d : chrono) {
            Double close = parse(d.closePrice());
            if (close == null) { prevClose = null; continue; }
            Double high = parse(d.highPrice());
            Double low = parse(d.lowPrice());
            if (prevClose != null) {
                double tr;
                if (high != null && low != null) {
                    tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                } else {
                    tr = Math.abs(close - prevClose);
                }
                trs.add(tr);
            }
            prevClose = close;
        }
        if (trs.isEmpty()) return null;
        int from = Math.max(0, trs.size() - period);
        double sum = 0;
        int n = 0;
        for (int i = from; i < trs.size(); i++) { sum += trs.get(i); n++; }
        return n == 0 ? null : sum / n;
    }

    private static Double parse(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.parseDouble(v.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
