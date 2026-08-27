package com.stockadvisor.service;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 추격(취소→재주문) 비용 분석 — 2026-08-27, <b>측정 먼저</b>.
 *
 * <p><b>왜</b>: 미체결 주문은 {@code trading.unfilled-timeout-minutes}(3분) 경과 시 취소되고, 멱등 판정이
 * 상태인식이라 다음 틱에 <b>현재가로 재주문</b>된다(= 가격 추격). 이 동작 자체는 의도된 것이지만 —
 * 체결을 포기하는 것보다 낫다 — <b>비용이 한 번도 측정된 적이 없었다</b>.</p>
 *
 * <p>실측 계기(2026-08-27 112040): 매도가 15,170 → 15,130 → 15,120 → 15,120 → <b>15,110</b>으로
 * 5회 시도·4회 취소 끝에 체결됐다. 하락 중인 종목이라 재주문마다 호가를 따라 내려가는
 * <b>계단식 하향 추격</b>이 됐고, 첫 호가 대비 60원(−0.39%p) 불리하게 체결됐으며 보유시간도
 * 90분 → 106분으로 늘었다. 매수 쪽은 거울상(상승 중 추격 매수)이 된다.</p>
 *
 * <p><b>소급 가능</b> — 새 태깅이 필요 없다. 같은 {@code idempotencyKey}를 공유하는 주문들이 곧 한 체인이고
 * (취소분은 부분 유니크 인덱스에서 빠지므로 여러 행이 남는다), 첫 시도가와 최종 체결가가 모두 저장돼 있다.</p>
 *
 * <p>부호 규약: {@code adverseDriftPct}는 <b>음수가 불리</b>다 — 매도는 더 싸게 팔린 것,
 * 매수는 더 비싸게 산 것. 방향을 정규화해 두 side를 같은 축에서 읽는다.</p>
 */
@Service
public class OrderChaseAnalysisService {

    private static final char SEP = 0;   // (side, strategy) 합성키 구분자 — 전략명에 안 나오는 문자

    private final OrderRepository orderRepository;

    public OrderChaseAnalysisService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * @param attempts        체인의 총 주문 수(취소 + 최종 체결)
     * @param firstPrice      첫 시도 지정가
     * @param filledPrice     최종 체결가
     * @param adverseDriftPct 첫 시도가 대비 체결가의 <b>불리한</b> 이동(%) — 음수가 불리
     * @param minutesLost     첫 시도 ~ 최종 체결까지 걸린 분(청산 지연)
     */
    public record Chase(String stockCode, String strategy, String side, String orderDate,
                        int attempts, long firstPrice, long filledPrice,
                        double adverseDriftPct, long minutesLost) {}

    /**
     * @param chains          추격이 실제로 일어난 체인 수(시도 ≥ 2)
     * @param avgDriftPct     평균 불리 이동(%) — 음수면 추격이 비용을 내고 있다
     * @param worstDriftPct   최악 체인
     * @param avgAttempts     평균 시도 수
     * @param avgMinutesLost  평균 지연(분)
     */
    public record Summary(String side, String strategy, int chains, double avgDriftPct,
                          double worstDriftPct, double avgAttempts, double avgMinutesLost,
                          List<Chase> samples) {}

    /** @param sinceOrderDate yyyyMMdd(포함). null이면 전체. */
    public List<Summary> analyze(String sinceOrderDate) {
        // 멱등키가 곧 체인 식별자 — 취소분은 부분 유니크에서 빠져 같은 키로 여러 행이 남는다.
        Map<String, List<Order>> chains = new LinkedHashMap<>();
        for (Order o : orderRepository.findAll()) {
            if (o.getMode() != TradingMode.LIVE) continue;   // DRY_RUN은 실체결이 없어 추격 개념이 없다
            if (o.getIdempotencyKey() == null) continue;
            if (sinceOrderDate != null && (o.getOrderDate() == null || o.getOrderDate().compareTo(sinceOrderDate) < 0)) continue;
            chains.computeIfAbsent(o.getIdempotencyKey(), k -> new ArrayList<>()).add(o);
        }

        List<Chase> all = new ArrayList<>();
        for (List<Order> rows : chains.values()) {
            Chase c = toChase(rows);
            if (c != null) all.add(c);
        }

        // (side, strategy)별 집계 — 매수/매도는 추격 방향이 반대라 반드시 분리해서 읽는다.
        Map<String, List<Chase>> byKey = new LinkedHashMap<>();
        for (Chase c : all) byKey.computeIfAbsent(c.side() + SEP + c.strategy(), k -> new ArrayList<>()).add(c);

        List<Summary> out = new ArrayList<>();
        byKey.forEach((k, list) -> {
            int bar = k.indexOf(SEP);   // 전략명에 구분자가 없다는 가정 대신 첫 구분자 기준으로 자른다
            double drift = list.stream().mapToDouble(Chase::adverseDriftPct).average().orElse(0);
            double worst = list.stream().mapToDouble(Chase::adverseDriftPct).min().orElse(0);
            double att = list.stream().mapToInt(Chase::attempts).average().orElse(0);
            double lost = list.stream().mapToLong(Chase::minutesLost).average().orElse(0);
            List<Chase> worstFew = list.stream()
                    .sorted(Comparator.comparingDouble(Chase::adverseDriftPct))
                    .limit(5).toList();
            out.add(new Summary(k.substring(0, bar), k.substring(bar + 1), list.size(), round2(drift), round2(worst),
                    round2(att), round2(lost), worstFew));
        });
        out.sort(Comparator.comparingDouble(Summary::avgDriftPct));
        return out;
    }

    /**
     * 한 체인(같은 멱등키)을 추격 1건으로 환산(순수) — 추격이 없었으면(시도 1회) null.
     *
     * <p>최종 체결가는 {@code avgFillPrice}(실체결)를 쓴다. 체결된 행이 없으면 아직 진행 중이거나
     * 전부 실패한 체인이라 비용을 확정할 수 없어 제외한다.</p>
     */
    static Chase toChase(List<Order> rows) {
        if (rows == null || rows.size() < 2) return null;
        List<Order> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        Order first = sorted.get(0);
        Order filled = null;
        for (Order o : sorted) {
            if (o.getStatus() == OrderStatus.FILLED && o.getAvgFillPrice() != null && o.getAvgFillPrice() > 0) filled = o;
        }
        if (filled == null || first.getRequestedPrice() <= 0) return null;

        // 취소·거부만 앞에 쌓인 경우가 추격이다. 첫 행이 곧 체결이면 재주문이 없었던 것.
        if (first == filled) return null;

        long fp = first.getRequestedPrice();
        long xp = filled.getAvgFillPrice();
        // 부호 정규화: 매도는 싸게 팔릴수록, 매수는 비싸게 살수록 불리 → 둘 다 음수가 불리가 되게 한다.
        double drift = first.getSide() == OrderSide.SELL
                ? (double) (xp - fp) / fp * 100.0
                : (double) (fp - xp) / fp * 100.0;
        long minutes = (first.getCreatedAt() != null && filled.getCreatedAt() != null)
                ? java.time.Duration.between(first.getCreatedAt(), filled.getCreatedAt()).toMinutes()
                : 0;

        return new Chase(first.getStockCode(), first.getStrategy() == null ? "?" : first.getStrategy(),
                first.getSide().name(), first.getOrderDate(), sorted.size(), fp, xp, round2(drift), minutes);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
