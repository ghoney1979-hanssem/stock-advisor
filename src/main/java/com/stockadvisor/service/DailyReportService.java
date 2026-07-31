package com.stockadvisor.service;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.notification.DiscordNotifier;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.OrderRepository;
import com.stockadvisor.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 매일 장 마감 후 <b>실매매(LIVE 주문) 기준</b> 성과 요약을 Discord 로 발송.
 * - 오늘 실매매: LIVE 매수 건별(종목명·전략·체결가) + 청산 결과(익절/손절/미청산)
 * - 전략별 누적 실현손익(원·승률)
 * - 일별 실현손익 그래프(최근 N거래일)
 * - 전략 진단 감시 플래그(섀도우 대조군 기반 — 감시용)
 *
 * <p>섀도우(가상매수) 성과·권장 청산시점은 리포트에서 제외(사용자 요청) — 상세 분석은
 * {@code /admin/strategy-report}·{@code /admin/exit-timing} 등 조회 API로.</p>
 */
@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter YMD_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DAILY_CHART_DAYS = 10;     // 일별 실현손익 그래프 표시 거래일 수
    private static final int BAR_WIDTH = 10;            // 컬러 블록 막대 최대 길이
    /** 체결된 적 없는 죽은 주문 — 실매매 집계 제외. */
    private static final Set<OrderStatus> DEAD = Set.of(OrderStatus.REJECTED, OrderStatus.FAILED, OrderStatus.CANCELLED);

    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final KisApiClient kisApiClient;
    private final DiscordNotifier discordNotifier;
    private final ControlAnalysisService controlAnalysisService;
    private final Map<String, String> labelByName;

    public DailyReportService(OrderRepository orderRepository,
                              CompanyRepository companyRepository,
                              KisApiClient kisApiClient,
                              DiscordNotifier discordNotifier,
                              ControlAnalysisService controlAnalysisService,
                              List<TradingStrategy> strategies) {
        this.orderRepository = orderRepository;
        this.companyRepository = companyRepository;
        this.kisApiClient = kisApiClient;
        this.discordNotifier = discordNotifier;
        this.controlAnalysisService = controlAnalysisService;
        this.labelByName = strategies.stream()
                .collect(Collectors.toMap(TradingStrategy::name, TradingStrategy::label));
    }

    /** 일별 실매매 리포트 생성·발송. */
    public String sendDailyReport() {
        ZonedDateTime now = ZonedDateTime.now(SEOUL);
        String today = now.format(YMD_KEY);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 **일일 실매매 리포트 (").append(now.format(YMD)).append(")**\n\n");

        // 0) 시장 국면 (코스피/코스닥 등락률) — 상승장/하락장 대비 성과 판단용
        Double kospi = safeIndexChange("0001");
        Double kosdaq = safeIndexChange("1001");
        sb.append("**[시장]** 코스피 ").append(fmtPct(kospi))
                .append(" · 코스닥 ").append(fmtPct(kosdaq)).append("\n\n");

        // 1) 오늘 실매매 — LIVE 매수 건별 + 청산 결과
        List<Order> todayBuys = orderRepository
                .findByModeAndSideAndOrderDateGreaterThanEqual(TradingMode.LIVE, OrderSide.BUY, today).stream()
                .filter(o -> !DEAD.contains(o.getStatus()))
                .toList();
        appendTodayTrades(sb, todayBuys);

        // 2) 전략별 누적 실현손익 + 3) 일별 실현손익 그래프
        List<Order> closedAll = orderRepository
                .findByModeAndSideAndClosed(TradingMode.LIVE, OrderSide.BUY, true).stream()
                .filter(o -> o.getRealizedPnl() != null)   // 잔고보정(reconcile) 청산은 손익 미상 → 제외
                .toList();
        appendCumulative(sb, closedAll);
        appendDailyChart(sb, closedAll);

        // 4) 전략 진단 감시 플래그 (손실 전략만 자동 감지 — 상세 원인·조치는 수동 판단, 자동 조치 아님)
        appendDiagnosisFlags(sb);

        sb.append("\n_").append(Disclaimer.SHORT).append("_");

        String msg = sb.toString();
        discordNotifier.send(msg);
        log.info("일일 실매매 리포트 발송 (오늘 매수 {}건, 누적 청산 {}건)", todayBuys.size(), closedAll.size());
        return msg;
    }

    /** 오늘 실매매 건별 — 종목명·전략·체결가·청산 결과(익절/손절/미청산). */
    private void appendTodayTrades(StringBuilder sb, List<Order> buys) {
        sb.append("**[오늘 실매매]**\n");
        if (buys.isEmpty()) {
            sb.append("• 없음\n");
            return;
        }
        long realized = 0;
        int open = 0;
        for (Order o : buys) {
            long qty = effectiveQty(o);
            long price = effectiveBuyPrice(o);
            sb.append("• ").append(stockDisplay(o.getStockCode()))
              .append(" [").append(label(o.getStrategy())).append("] ")
              .append(String.format("%,d주 @ %,d원", qty, price));
            if (o.isClosed() && o.getRealizedPnl() != null) {
                long pnl = o.getRealizedPnl();
                realized += pnl;
                double pct = pctOf(pnl, qty * price);
                sb.append(String.format(" → %s **%+,d원 (%+.2f%%)**", pnl >= 0 ? "✅" : "🛑", pnl, pct));
            } else if (o.isClosed()) {
                sb.append(" → 청산(손익 미상·잔고보정)");
            } else {
                open++;
                sb.append(" → ⏳ 미청산");
            }
            sb.append("\n");
        }
        sb.append(String.format("• **합계**: 매수 %d건 · 실현 **%+,d원**", buys.size(), realized));
        if (open > 0) sb.append(" · ⚠️ 미청산 ").append(open).append("건");
        sb.append("\n");
    }

    /** 전략별 누적 실현손익(원) — 막대(🟩/🟥) + 건수·승률. */
    private void appendCumulative(StringBuilder sb, List<Order> closed) {
        sb.append("\n**[전략별 누적 실현손익 📊]**\n");
        if (closed.isEmpty()) {
            sb.append("• 청산 완료 실매매 없음\n");
            return;
        }
        Map<String, List<Order>> byStrat = closed.stream()
                .collect(Collectors.groupingBy(Order::getStrategy, TreeMap::new, Collectors.toList()));
        double maxAbs = byStrat.values().stream()
                .mapToDouble(list -> Math.abs(sumPnl(list))).max().orElse(1.0);
        long total = 0;
        int totalWins = 0, totalN = 0;
        for (Map.Entry<String, List<Order>> e : byStrat.entrySet()) {
            List<Order> list = e.getValue();
            long pnl = sumPnl(list);
            long wins = list.stream().filter(o -> o.getRealizedPnl() > 0).count();
            total += pnl;
            totalWins += (int) wins;
            totalN += list.size();
            sb.append("• ").append(label(e.getKey())).append(" ").append(bar(pnl, maxAbs))
              .append(String.format(" %+,d원 (n%d · 승 %.0f%%)\n", pnl, list.size(), 100.0 * wins / list.size()));
        }
        sb.append(String.format("• **누적 합계**: %+,d원 (n%d · 승 %.0f%%)\n",
                total, totalN, totalN == 0 ? 0 : 100.0 * totalWins / totalN));
    }

    /** 최근 N거래일 일별 실현손익(원) 막대 그래프 — 매수 주문일 기준 집계. */
    private void appendDailyChart(StringBuilder sb, List<Order> closed) {
        sb.append("\n**[일별 실현손익 📈 · 최근 ").append(DAILY_CHART_DAYS).append("거래일]**\n");
        Map<String, List<Order>> byDay = closed.stream()
                .collect(Collectors.groupingBy(Order::getOrderDate, TreeMap::new, Collectors.toList()));
        if (byDay.isEmpty()) {
            sb.append("• 데이터 없음\n");
            return;
        }
        List<String> days = new ArrayList<>(byDay.keySet());
        List<String> last = days.subList(Math.max(0, days.size() - DAILY_CHART_DAYS), days.size());
        Map<String, Long> daily = new LinkedHashMap<>();
        for (String d : last) daily.put(d, sumPnl(byDay.get(d)));
        double maxAbs = daily.values().stream().mapToDouble(Math::abs).max().orElse(1.0);
        daily.forEach((d, pnl) -> sb.append(fmtDay(d)).append(" ").append(bar(pnl, maxAbs))
                .append(String.format(" %+,d원 (%d건)\n", pnl, byDay.get(d).size())));
    }

    /** 전략 진단 감시 플래그 — 손실 전략만 자동 감지해 알림(감시용). 상세 원인·조치는 수동 판단(자동 조치 아님). */
    private void appendDiagnosisFlags(StringBuilder sb) {
        sb.append("\n**[전략 진단 감시 🔍]**\n");
        try {
            List<ControlAnalysisService.Diagnosis> ds = controlAnalysisService.diagnose();
            List<String> losers = new ArrayList<>();
            List<String> unsampled = new ArrayList<>();
            for (ControlAnalysisService.Diagnosis d : ds) {
                if (d.verdict().startsWith("LOSER")) {
                    losers.add(String.format("%s(%+.2f%%)", label(d.strategy()), d.enteredNet()));
                } else if ("UNDERSAMPLED".equals(d.verdict())) {
                    unsampled.add(label(d.strategy()));
                }
            }
            if (losers.isEmpty()) {
                sb.append("• 🟢 손실 전략 없음\n");
            } else {
                sb.append("• 🔴 손실 감지: ").append(String.join(", ", losers)).append("\n");
                sb.append("• ⚠️ 상세 원인·조치는 수동 판단 필요 (자동 조치 아님) — `/admin/control-diagnosis`\n");
            }
            if (!unsampled.isEmpty()) {
                sb.append("• ⚪ 미검증: ").append(String.join(", ", unsampled)).append("\n");
            }
        } catch (Exception e) {
            sb.append("• 진단 계산 실패\n");
            log.warn("전략 진단 감시 계산 실패: {}", e.getMessage());
        }
    }

    private long sumPnl(List<Order> list) {
        return list.stream().mapToLong(Order::getRealizedPnl).sum();
    }

    /** 실제 체결 수량 — 체결조회 값 우선(없으면 주문값). */
    private long effectiveQty(Order o) {
        return (o.getFilledQty() != null && o.getFilledQty() > 0) ? o.getFilledQty() : o.getRequestedQty();
    }

    /** 실제 매입가 — 체결조회 값 우선(없으면 주문값). */
    private long effectiveBuyPrice(Order o) {
        return (o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0) ? o.getAvgFillPrice() : o.getRequestedPrice();
    }

    private double pctOf(long pnl, long cost) {
        return cost <= 0 ? 0 : pnl * 100.0 / cost;
    }

    /** 종목 표시명 "종목명(코드)" — 미매핑이면 코드만. */
    private String stockDisplay(String stockCode) {
        try {
            var c = companyRepository.findById(stockCode).orElse(null);
            if (c != null && c.getName() != null && !c.getName().isBlank()) {
                return c.getName() + "(" + stockCode + ")";
            }
        } catch (Exception ignored) {
            // 이름 조회 실패는 리포트를 막지 않는다
        }
        return stockCode;
    }

    /** 값의 부호·크기를 컬러 블록 막대로(🟩 양/🟥 음). Discord 텍스트에서 안정적으로 렌더. */
    private String bar(double value, double maxAbs) {
        if (maxAbs <= 0) maxAbs = 1;
        int len = (int) Math.round(Math.min(1.0, Math.abs(value) / maxAbs) * BAR_WIDTH);
        if (len < 1 && value != 0) len = 1;
        return (value >= 0 ? "🟩" : "🟥").repeat(len);
    }

    /** "20260707" → "07-07". */
    private String fmtDay(String yyyymmdd) {
        return yyyymmdd != null && yyyymmdd.length() == 8
                ? yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8) : yyyymmdd;
    }

    private String label(String strategy) {
        return labelByName.getOrDefault(strategy, strategy);
    }

    /** 지수 등락률 조회 — 실패해도 리포트는 발송되도록 null 처리. */
    private Double safeIndexChange(String indexCode) {
        try {
            return kisApiClient.fetchIndexChangeRate(indexCode);
        } catch (Exception ex) {
            log.warn("지수 조회 실패 code={}: {}", indexCode, ex.getMessage());
            return null;
        }
    }

    private String fmtPct(Double v) {
        return v == null ? "N/A" : String.format("%+.2f%%", v);
    }
}
