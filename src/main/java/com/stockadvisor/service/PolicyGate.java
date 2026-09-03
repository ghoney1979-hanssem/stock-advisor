package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실전 매매 진입/청산 직전 모든 주문을 검증하는 안전 게이트(본인 계좌 자기매매).
 *
 * <p>신호 → 주문 사이에 위치해, 비협상 안전장치를 한 곳에서 강제한다:
 * 마스터 스위치/킬스위치 · 멱등성(중복주문) · 1주문 절대 금액 상한 ·
 * 강제청산 시각 이후 신규진입 금지 · 1일 주문 한도 · 최대 동시 보유 · 일일 손실 한도.</p>
 *
 * <p>매도(청산)는 리스크 축소 방향이라 멱등성/킬스위치 외 한도 제약 없이 허용한다.
 * 아직 OrderService(KIS 주문)에는 연결되지 않은 독립 컴포넌트다.</p>
 */
@Component
public class PolicyGate {

    private static final Logger log = LoggerFactory.getLogger(PolicyGate.class);

    private final TradingPolicyProperties policy;
    private final OrderRepository orderRepository;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final java.util.Set<String> swingStrategies;   // 오버나잇 스윙 전략 — 장마감 초과 청산 진입규칙 면제
    private final java.util.Set<String> inverseCodes;      // 인버스 ETF — 시간청산 미적용(지수조건+sessionEnd 백스톱)이라 시간규칙 면제
    /** 멀티데이 트레일 전략(P 등) — 스윙과 같은 이유로 "장마감 초과 청산" 진입규칙 면제. 필드주입(생성자 무churn). */
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.multiday-exit.strategies:}")
    private String multidayCsv = "";
    private java.util.Set<String> multidayStrategies = java.util.Set.of();
    @jakarta.annotation.PostConstruct
    void initMultiday() { this.multidayStrategies = parseCsv(multidayCsv); }
    /** 테스트용 — 멀티데이 전략 지정. */
    void setMultidayStrategies(String csv) { this.multidayStrategies = parseCsv(csv); }

    /** 런타임 긴급 정지 스위치(설정 enabled 와 별개). 관리 API 로 토글. */
    private final AtomicBoolean killSwitch = new AtomicBoolean(false);

    public PolicyGate(TradingPolicyProperties policy, OrderRepository orderRepository,
                      StrategyHoldTimeProvider holdTimeProvider,
                      @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv,
                      @org.springframework.beans.factory.annotation.Value("${stockadvisor.inverse-codes:114800,251340}") String inverseCsv) {
        this.policy = policy;
        this.orderRepository = orderRepository;
        this.holdTimeProvider = holdTimeProvider;
        this.swingStrategies = parseCsv(swingCsv);
        this.inverseCodes = parseCsv(inverseCsv);
    }

    /**
     * 주문 요청.
     *
     * @param quantity       수량(주)
     * @param price          가격(원). 시장가면 검증용 추정가를 넣는다(0이면 금액 한도 검증 불가하므로 추정가 권장).
     * @param maxKrw         이 주문의 허용 상한(원) = 계좌 순자산 × maxOrderPct. 호출측(OrderService)이 산정해 전달.
     * @param idempotencyKey 중복 차단 키(진입은 strategy:stockCode:date)
     * @param now            판정 기준 시각(KST 로컬타임)
     */
    public record OrderRequest(String strategy, String stockCode, OrderSide side,
                               long quantity, long price, long maxKrw, String idempotencyKey, LocalTime now) {
        public long krwAmount() {
            return quantity * price;
        }
    }

    public record PolicyDecision(boolean allowed, String reason) {
        public static PolicyDecision allow() {
            return new PolicyDecision(true, "OK");
        }
        public static PolicyDecision deny(String reason) {
            return new PolicyDecision(false, reason);
        }
    }

    /** 주문 허용 여부 판정. 거부 사유를 함께 반환한다. */
    public PolicyDecision evaluate(OrderRequest req, String today) {
        // 0) 마스터 스위치 / 긴급 정지
        if (!policy.enabled()) {
            return PolicyDecision.deny("매매 비활성화(enabled=false)");
        }
        if (killSwitch.get()) {
            return PolicyDecision.deny("킬스위치 ON");
        }
        // 1) 멱등성 — 중복 주문 차단 (매수/매도 공통). 단 취소/거부/실패분은 재주문(추격) 허용.
        if (req.idempotencyKey() != null
                && orderRepository.existsByIdempotencyKeyAndStatusNotIn(req.idempotencyKey(),
                        java.util.List.of(OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.FAILED))) {
            return PolicyDecision.deny("중복 주문(idempotency): " + req.idempotencyKey());
        }
        // 매도(청산)는 리스크 축소 방향 → 한도 제약 없이 허용
        if (req.side() == OrderSide.SELL) {
            return PolicyDecision.allow();
        }

        // ===== 이하 매수(신규 진입) 전용 하드캡 =====
        // 2) 1주문 금액 상한 (순자산×maxOrderPct, 수량/가격 계산 버그의 최후 방어막)
        long krw = req.krwAmount();
        if (krw <= 0) {
            return PolicyDecision.deny("주문 금액 산정 불가(수량/가격 확인): qty=" + req.quantity() + " price=" + req.price());
        }
        if (req.maxKrw() <= 0) {
            return PolicyDecision.deny("주문 한도 산정 불가(계좌 평가액 0/조회실패)");
        }
        if (krw > req.maxKrw()) {
            return PolicyDecision.deny(String.format("1주문 한도 초과: %,d원 > %,d원 (순자산×%.0f%%)",
                    krw, req.maxKrw(), policy.effectiveMaxOrderPct()));
        }
        // 3) 시간기반 청산이 연속매매 종료(session-end) 넘기면 신규진입 금지(오버나잇 방지).
        //    ⚠️ 스윙 전략(C 등)은 의도적으로 오버나잇 보유라 이 규칙 면제 — 장중 아무 때나 진입 허용.
        //    ⚠️ 인버스 ETF도 면제(2026-07-20 실측: 적응형 90분이 이 규칙에 적용돼 15:12 재진입이 오차단) —
        //    인버스 청산은 시간이 아니라 지수조건이고 sessionEnd(15:20) 백스톱이 당일청산을 보장하므로
        //    "청산이 장마감을 넘긴다"는 전제 자체가 성립 안 함. 마감 전 하락 가속(인버스 최적 구간) 진입 보존.
        //    ⚠️ 멀티데이 전략(P 등)도 같은 이유로 면제 — 청산이 트레일/거래일 백스톱이라 "장마감 초과"가 전제부터 성립 안 함.
        if (!swingStrategies.contains(req.strategy()) && !multidayStrategies.contains(req.strategy())
                && !inverseCodes.contains(req.stockCode())) {
            int holdMinutes = holdTimeProvider.holdMinutes(req.strategy());
            LocalTime exitBy = req.now().plusMinutes(holdMinutes);
            if (exitBy.isAfter(policy.sessionEndLocalTime())) {
                return PolicyDecision.deny(String.format("시간기반 청산(보유 %d분 → %s)이 장마감 %s 초과 → 신규진입 금지",
                        holdMinutes, exitBy, policy.sessionEnd()));
            }
        }
        // 4) 1일 신규 주문 한도 (0 이하면 무제한)
        if (policy.maxOrdersPerDay() > 0) {
            long todayBuys = orderRepository.countByOrderDateAndSide(today, OrderSide.BUY);
            if (todayBuys >= policy.maxOrdersPerDay()) {
                return PolicyDecision.deny("1일 주문 한도 도달: " + todayBuys + "/" + policy.maxOrdersPerDay());
            }
        }
        // 5) 최대 동시 보유 종목
        long open = orderRepository.countOpenPositions();
        if (open >= policy.maxPositions()) {
            return PolicyDecision.deny("최대 동시 보유 도달: " + open + "/" + policy.maxPositions());
        }
        // 6) 일일 손실 한도 — 당일 확정손실이 한도 이상이면 신규진입 중단.
        //    ⚠️ 인버스는 면제(2026-07-24 실측: 아침 H −81,078이 한도를 소진해 코스피 −5.9% 폭락일의
        //    인버스 재진입 4회가 전부 거부 — 헤지 다리는 하락일에 "버는" 방향이라 무관 전략의 손실로
        //    커버리지를 끄는 건 취지 역행. 노출은 부트스트랩 ×0.3 × 인버스 2코드 ≈ 순자산 3%로 자체 제한).
        if (!inverseCodes.contains(req.stockCode())) {
            long loss = Math.max(0, -orderRepository.sumRealizedPnlByDate(today));
            // 한도: dailyLossLimitPct > 0 이면 순자산 대비 %(2026-07-24) — 순자산은 maxKrw(=순자산×종목비중)에서 역산.
            // maxKrw 미가용(0)이면 절대액 fallback. (maxKrw 의미는 OrderRequest javadoc에 고정 계약)
            long limit = policy.dailyLossLimit();
            if (policy.dailyLossLimitPct() > 0 && req.maxKrw() > 0 && policy.effectiveMaxOrderPct() > 0) {
                double netAssets = req.maxKrw() * 100.0 / policy.effectiveMaxOrderPct();
                limit = Math.round(netAssets * policy.dailyLossLimitPct() / 100.0);
            }
            if (loss >= limit) {
                return PolicyDecision.deny(String.format("일일 손실 한도 도달: %,d원 ≥ %,d원", loss, limit));
            }
        }
        return PolicyDecision.allow();
    }

    static java.util.Set<String> parseCsv(String csv) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                if (!s.isBlank()) set.add(s.trim());
            }
        }
        return set;
    }

    /** 긴급 정지 토글(true=정지). */
    public void setKillSwitch(boolean on) {
        killSwitch.set(on);
        log.warn("킬스위치 {}", on ? "ON — 신규 매매 정지" : "OFF — 매매 재개");
    }

    public boolean isKillSwitch() {
        return killSwitch.get();
    }
}
