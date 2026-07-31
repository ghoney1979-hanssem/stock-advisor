package com.stockadvisor.service;

import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.notification.DiscordNotifier;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.OrderRepository;
import com.stockadvisor.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 일일 실매매 리포트 — LIVE 주문 기준 성과(오늘 건별 익절/손절·미청산, 전략별 누적), 섀도우/권장청산 미표시.
 */
class DailyReportServiceTest {

    private static final String TODAY = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    private final OrderRepository orderRepo = mock(OrderRepository.class);
    private final CompanyRepository companyRepo = mock(CompanyRepository.class);
    private final KisApiClient kis = mock(KisApiClient.class);
    private final DiscordNotifier discord = mock(DiscordNotifier.class);
    private final ControlAnalysisService control = mock(ControlAnalysisService.class);

    private DailyReportService svc() {
        TradingStrategy b = mock(TradingStrategy.class);
        when(b.name()).thenReturn("VOLUME_LEADING_B");
        when(b.label()).thenReturn("거래량주도 (B)");
        when(kis.fetchIndexChangeRate(anyString())).thenReturn(1.23);
        when(control.diagnose()).thenReturn(List.of());
        when(companyRepo.findById("005930")).thenReturn(Optional.of(
                new Company("005930", "삼성전자", null, "KOSPI")));
        return new DailyReportService(orderRepo, companyRepo, kis, discord, control, List.of(b));
    }

    private Order liveBuy(String code, long qty, long price) {
        Order o = new Order("VOLUME_LEADING_B:" + code + ":" + TODAY, "VOLUME_LEADING_B", code,
                OrderSide.BUY, qty, price, TradingMode.LIVE, TODAY);
        o.markFilled(qty, price);
        return o;
    }

    @Test
    void 실매매_기준_오늘거래_익절손절과_누적손익을_포함한다() {
        Order win = liveBuy("005930", 4, 67_200);
        win.closePosition(1_200);                    // ✅ 익절
        Order loss = liveBuy("004090", 26, 11_310);
        loss.closePosition(-2_000);                  // 🛑 손절
        when(orderRepo.findByModeAndSideAndOrderDateGreaterThanEqual(eq(TradingMode.LIVE), eq(OrderSide.BUY), any()))
                .thenReturn(List.of(win, loss));
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true))
                .thenReturn(List.of(win, loss));

        String msg = svc().sendDailyReport();

        assertThat(msg).contains("일일 실매매 리포트");
        assertThat(msg).contains("삼성전자(005930)");           // 종목명 표시
        assertThat(msg).contains("✅").contains("🛑");          // 익절/손절 구분
        assertThat(msg).contains("거래량주도 (B)");              // 전략 라벨
        assertThat(msg).contains("누적 실현손익");
        assertThat(msg).doesNotContain("가상매수");              // 섀도우 성과 미표시
        assertThat(msg).doesNotContain("권장 청산시점");          // 권장청산 미표시(사용자 요청)
    }

    @Test
    void 미청산_포지션은_경고와_함께_표시된다() {
        Order open = liveBuy("005930", 2, 115_100);   // 청산 안 됨
        when(orderRepo.findByModeAndSideAndOrderDateGreaterThanEqual(eq(TradingMode.LIVE), eq(OrderSide.BUY), any()))
                .thenReturn(List.of(open));
        when(orderRepo.findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true))
                .thenReturn(List.of());

        String msg = svc().sendDailyReport();

        assertThat(msg).contains("⏳ 미청산");
        assertThat(msg).contains("미청산 1건");
    }
}
