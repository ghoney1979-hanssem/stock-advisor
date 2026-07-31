package com.stockadvisor.service;

import com.stockadvisor.config.properties.SizingProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ATR 사이징: 위험예산÷(ATR×배수)로 수량 산정, 1주문 상한 천장, ATR 미산출 시 고정 fallback.
 */
class PositionSizerTest {

    // maxOrderPct=10
    private TradingPolicyProperties policy() {
        return new TradingPolicyProperties(true, TradingMode.DRY_RUN, 10.0, 0, 50_000, 10,
                "15:20", 60, true, List.of(), 3, 5, 0);
    }

    private SizingProperties sizing(boolean atr, int period, double mult, double riskPct) {
        return new SizingProperties(atr, period, mult, riskPct);
    }

    /** 고/저/종가 일봉 행(최신일 우선). high/low null이면 종가만. */
    private KisDailyPriceResponse.DailyPrice row(String c, String h, String l) {
        return new KisDailyPriceResponse.DailyPrice("20260629", c, h, l, "0", "0", "0");
    }

    // 시장충격 비활성(maxImpactPct=0) — 기존 사이징 동작 유지
    private PositionSizer sizer(KisApiClient kis, SizingProperties sp) {
        return new PositionSizer(kis, policy(), sp, costModel(0));
    }

    private ExecutionCostModel costModel(double maxImpactPct) {
        return new ExecutionCostModel(new com.stockadvisor.config.properties.ExecutionCostProperties(
                true, 1, 0, 0, 999, maxImpactPct));
    }

    private KisApiClient kisWith(List<KisDailyPriceResponse.DailyPrice> rows) {
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchDailyPrices(any())).thenReturn(new KisDailyPriceResponse("0", "ok", rows));
        return kis;
    }

    @Test
    void ATR_비활성이면_고정상한_사이징() {
        KisApiClient kis = mock(KisApiClient.class);
        // 순자산 1000만 × 10% = 100만, 가격 5만 → 20주
        PositionSizer.Sizing s = sizer(kis, sizing(false, 14, 2.0, 0.5)).size("005930", 50_000, 10_000_000);

        assertThat(s.qty()).isEqualTo(20);
        assertThat(s.basis()).contains("고정");
    }

    @Test
    void ATR기반_변동성클수록_수량적음_상한이내() {
        // 매일 고-저 폭 1000원 일정 → ATR=1000. 위험예산 = 1000만×0.5% = 5만. stop=2×1000=2000. atrQty=50000/2000=25주.
        // 상한 = 1000만×10%/5만 = 20주. min(25,20)=20 → 이 경우 상한이 천장.
        List<KisDailyPriceResponse.DailyPrice> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("50000", "50500", "49500"));
        PositionSizer.Sizing s = sizer(kisWith(rows), sizing(true, 14, 2.0, 0.5)).size("005930", 50_000, 10_000_000);

        assertThat(s.atrKrw()).isEqualTo(1000.0);
        assertThat(s.qty()).isEqualTo(20);            // 상한 천장 적용
        assertThat(s.capQty()).isEqualTo(20);
        assertThat(s.basis()).contains("ATR");
    }

    @Test
    void ATR기반_고변동종목은_상한보다_적게() {
        // 고-저 폭 5000원 → ATR=5000. 위험예산 5만, stop=2×5000=10000, atrQty=5주 < 상한 20주 → 5주.
        List<KisDailyPriceResponse.DailyPrice> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("50000", "52500", "47500"));
        PositionSizer.Sizing s = sizer(kisWith(rows), sizing(true, 14, 2.0, 0.5)).size("005930", 50_000, 10_000_000);

        assertThat(s.atrKrw()).isEqualTo(5000.0);
        assertThat(s.qty()).isEqualTo(5);             // 변동성 커서 상한보다 적게
    }

    @Test
    void 고저없으면_종가간변화로_ATR_degrade() {
        // 종가가 100씩 상승(고/저 null) → TR=|Δclose|=100. ATR=100. 위험예산 5만, stop=200, atrQty=250 > 상한 → 상한 20.
        List<KisDailyPriceResponse.DailyPrice> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row(String.valueOf(50000 + i * 100), null, null));
        // 최신일 우선이라 역순으로 — 값 순서 무관(절대변화 100 일정)
        PositionSizer.Sizing s = sizer(kisWith(rows), sizing(true, 14, 2.0, 0.5)).size("005930", 50_000, 10_000_000);

        assertThat(s.atrKrw()).isEqualTo(100.0);
        assertThat(s.qty()).isEqualTo(20);
    }

    @Test
    void 일봉없으면_고정_fallback() {
        PositionSizer.Sizing s = sizer(kisWith(List.of()), sizing(true, 14, 2.0, 0.5)).size("005930", 50_000, 10_000_000);

        assertThat(s.atrKrw()).isNull();
        assertThat(s.qty()).isEqualTo(20);            // 고정 상한
        assertThat(s.basis()).contains("미산출");
    }

    @Test
    void 시장충격_캡으로_수량축소() {
        // 고정 사이징 cap 20주(순자산 1000만×10%/5만). 호가 잔량으로 5주만 충격 0.5% 내 흡수 → 5주로 캡.
        KisApiClient kis = mock(KisApiClient.class);
        when(kis.fetchOrderBook(any())).thenReturn(new KisApiClient.OrderBook(49_950,
                List.of(new KisApiClient.Level(50_000, 5),    // best ask, 5주
                        new KisApiClient.Level(50_300, 100))));  // 50300 > 50250(밴드) → 제외
        PositionSizer s = new PositionSizer(kis, policy(), sizing(false, 14, 2.0, 0.5), costModel(0.5));

        PositionSizer.Sizing r = s.size("005930", 50_000, 10_000_000);

        assertThat(r.qty()).isEqualTo(5);          // 20 → 5 캡
        assertThat(r.capQty()).isEqualTo(20);
        assertThat(r.basis()).contains("시장충격캡");
    }

    @Test
    void computeAtr_정식TR_최댓값선택() {
        // prevClose=100, 당일 H=130 L=90 → TR=max(40, |130-100|, |90-100|)=40
        List<KisDailyPriceResponse.DailyPrice> chrono = List.of(
                row("100", "105", "95"),
                row("120", "130", "90"));
        Double atr = PositionSizer.computeAtr(chrono, 14);

        assertThat(atr).isEqualTo(40.0);   // TR 1건(첫날은 prevClose 없어 제외)
    }
}
