package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisBalanceResponse;
import com.stockadvisor.market.dto.KisOrderResponse;
import com.stockadvisor.notification.DiscordNotifier;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 주문 실행 진입점(본인 계좌 자기매매). 모든 주문은 여기서 {@link PolicyGate} 검증을 거친다.
 *
 * <p>흐름: {@link OrderCommand} → PolicyGate.evaluate() → (통과 시) {@link Order} 생성 →
 * {@code mode=DRY_RUN}이면 전송 없이 기록만(흐름 검증), {@code mode=LIVE}면 KIS 주문 전송.</p>
 *
 * <p>LIVE 경로는 KIS 현금주문(지정가)을 전송한다. 단 기본 설정이 {@code enabled=false}+{@code DRY_RUN}이라
 * 명시적으로 켜기 전엔 실주문이 나가지 않는다(PolicyGate 마스터 스위치). 슬리피지 통제를 위해 지정가 기본.</p>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    // 알림 라벨/청산예정 표시용 — 필드주입(생성자 무churn). 테스트(생성자 생성)에선 null → 원시명·미표시로 degrade.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.util.List<com.stockadvisor.strategy.TradingStrategy> strategyBeans;
    // 알림 종목명 표시용 — 필드주입(생성자 무churn). 미주입(테스트)/미매핑이면 코드만 표시로 degrade.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.repository.CompanyRepository companyRepository;

    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.max-positions-per-group:1}")
    private long maxPositionsPerGroup = 1;   // 같은 계열(그룹 키) 최대 활성 포지션. 0=비활성

    // 전략별 동시 보유 상한(csv "STRATEGY:N", 2026-08-25) — 슬롯이 선착순이라 신호가 많은 전략이 리스크 예산을 독점한다.
    // 계기: L(눌림 반전)이 하루 84~89건을 내는데 거래량 급증을 요구하지 않아 개장 직후부터 신호가 쏟아진다.
    // 순자산 1,054만·부트스트랩 ×0.5(종목당 2.5%)·BEAR 노출상한 30% 기준으로 L 혼자 ~12종목 = 예산 전액을 채울 수 있어
    // D·G·J·K가 사실상 밀려난다(인버스는 노출가드 면제라 생존). 미지정 전략은 무제한(종전 동작).
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.max-positions-per-strategy:}")
    private String maxPositionsPerStrategyCsv = "";
    private volatile java.util.Map<String, Long> maxPositionsPerStrategy;

    /** "STRATEGY:N,STRATEGY2:M" 파싱(지연). 값이 숫자가 아니거나 ≤0이면 그 항목은 무시(=무제한). */
    static java.util.Map<String, Long> parsePositionCaps(String csv) {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        if (csv == null) return m;
        for (String part : csv.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim();
            if (k.isEmpty()) continue;
            try {
                long v = Long.parseLong(kv[1].trim());
                if (v > 0) m.put(k, v);
            } catch (NumberFormatException ignored) {
                // 잘못된 값은 조용히 무시 — 설정 오타로 진입이 전면 차단되는 것보다 낫다(degrade open)
            }
        }
        return m;
    }

    private long positionCapFor(String strategy) {
        java.util.Map<String, Long> m = maxPositionsPerStrategy;
        if (m == null) {
            m = parsePositionCaps(maxPositionsPerStrategyCsv);
            maxPositionsPerStrategy = m;
        }
        return m.getOrDefault(strategy, 0L);   // 0 = 상한 없음
    }

    private String companyName(String stockCode) {
        if (companyRepository == null || stockCode == null) return null;
        try {
            return companyRepository.findById(stockCode)
                    .map(com.stockadvisor.domain.Company::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 계열 그룹 키(순수) — 선행 영문 그룹명(HLB/SK/LG…) 또는 한글 앞 2자(삼성/현대…). 판정 불가면 null. */
    static String groupKeyOf(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[A-Za-z&]{2,}").matcher(n);
        if (m.find()) return m.group().toUpperCase();
        if (n.length() >= 2 && n.substring(0, 2).matches("[가-힣]{2}")) return n.substring(0, 2);
        return null;
    }
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}")
    private String swingStrategiesCsv;

    // 지정가 주문구분(슬리피지 통제) — 단타 진입 기본. 시장가("01") 지원은 추후.
    private static final String ORD_DVSN_LIMIT = "00";

    private final TradingPolicyProperties policy;
    private final PolicyGate policyGate;
    private final OrderRepository orderRepository;
    private final KisApiClient kisApiClient;
    private final DiscordNotifier discordNotifier;
    private final StrategyPerformanceGate performanceGate;
    private final MarketRiskGuard riskGuard;
    private final PositionSizer positionSizer;
    private final StrategyHoldTimeProvider holdTimeProvider;   // 진입 시점 권장 보유시간 락(lock)용

    public OrderService(TradingPolicyProperties policy, PolicyGate policyGate,
                        OrderRepository orderRepository, KisApiClient kisApiClient,
                        DiscordNotifier discordNotifier, StrategyPerformanceGate performanceGate,
                        MarketRiskGuard riskGuard, PositionSizer positionSizer,
                        StrategyHoldTimeProvider holdTimeProvider) {
        this.policy = policy;
        this.policyGate = policyGate;
        this.orderRepository = orderRepository;
        this.kisApiClient = kisApiClient;
        this.discordNotifier = discordNotifier;
        this.performanceGate = performanceGate;
        this.riskGuard = riskGuard;
        this.positionSizer = positionSizer;
        this.holdTimeProvider = holdTimeProvider;
    }

    /**
     * 주문 명령.
     *
     * @param price          지정가(원). 시장가면 정책 금액검증용 추정가를 넣는다(0 금지).
     * @param maxKrw         이 주문 허용 상한(원) = 순자산 × maxOrderPct. 호출측이 산정해 전달(PolicyGate 검증값).
     * @param idempotencyKey 중복 차단 키(진입은 strategy:stockCode:date)
     */
    public record OrderCommand(String strategy, String stockCode, OrderSide side,
                               long quantity, long price, long maxKrw, String idempotencyKey, String sector,
                               Integer holdMinutes, String market, String note) {
        /** 호환 생성자(note 없음) — 기존 호출부·테스트 무변경. */
        public OrderCommand(String strategy, String stockCode, OrderSide side,
                            long quantity, long price, long maxKrw, String idempotencyKey, String sector,
                            Integer holdMinutes, String market) {
            this(strategy, stockCode, side, quantity, price, maxKrw, idempotencyKey, sector, holdMinutes, market, null);
        }
    }

    public enum ResultStatus { PENDING_APPROVAL, DRY_RUN, SUBMITTED, REJECTED, FAILED }

    public record OrderResult(ResultStatus status, Long orderId, String message) {
        public boolean isAccepted() {
            return status == ResultStatus.DRY_RUN || status == ResultStatus.SUBMITTED;
        }
        static OrderResult rejected(String reason) { return new OrderResult(ResultStatus.REJECTED, null, reason); }
        static OrderResult dryRun(Long id) { return new OrderResult(ResultStatus.DRY_RUN, id, "DRY-RUN 기록"); }
        static OrderResult submitted(Long id) { return new OrderResult(ResultStatus.SUBMITTED, id, "전송"); }
        static OrderResult failed(Long id, String reason) { return new OrderResult(ResultStatus.FAILED, id, reason); }
        static OrderResult pending(Long id) { return new OrderResult(ResultStatus.PENDING_APPROVAL, id, "승인 대기"); }
    }

    /**
     * 신호 진입(BUY) 주문 — 1주문 한도(순자산×maxOrderPct)로 수량을 산정해 제출한다.
     * 사이징: qty = floor(cap / price). 1주도 못 사면 스킵.
     * LIVE 모드에선 ① 화이트리스트(live-strategies)에 없거나 ② 최근 성과(net 평균수익률)가 기준 미달인
     * 전략은 실주문 차단(DRY_RUN은 관찰용이라 전부 기록 — 가상매수·알림은 게이트와 무관).
     */
    public OrderResult submitEntry(String strategy, String stockCode, String sector, String market,
                                   long price, String idempotencyKey) {
        if (policy.mode() == TradingMode.LIVE && !policy.isLiveAllowed(strategy)) {
            log.info("[주문] LIVE 미승인 전략 — 주문 스킵 [{}] {}", strategy, stockCode);
            return OrderResult.rejected("LIVE 미승인 전략: " + strategy);
        }
        boolean fallbackEntry = false;   // ③ 국면무관 fallback 통과분 → 축소사이징 적용
        if (policy.mode() == TradingMode.LIVE) {
            StrategyPerformanceGate.GateDecision gate = performanceGate.evaluate(strategy, market);
            if (!gate.allowed()) {
                log.info("[주문] 성과 게이트 차단 — 주문 스킵 [{}] {}: {}", strategy, stockCode, gate.reason());
                return OrderResult.rejected("성과 게이트 차단: " + gate.reason());
            }
            fallbackEntry = gate.fallback();
        }
        // 같은-종목 크로스전략 가드: 다른(또는 같은) 전략이 이미 이 종목에 활성 포지션/승인대기를 잡았으면
        // 중복 진입 스킵 → 한 종목에 여러 전략이 스태킹돼 노출이 집중되는 것 방지(종목당 실포지션 1개).
        // 각 전략의 가상매수(TradeOutcome) 기록은 이 가드와 무관하게 이미 남으므로 섀도우 성과는 영향 없음.
        // DRY_RUN/LIVE 공통 적용(포지션 관리 흐름을 dry-run에서도 충실히 시뮬).
        if (orderRepository.countActivePositionsByStockCode(stockCode) > 0) {
            log.info("[주문] 같은 종목 기보유(전략 무관) — 크로스전략 중복 진입 스킵 [{}] {}", strategy, stockCode);
            return OrderResult.rejected("같은 종목 활성 포지션 존재 — 크로스전략 중복 진입 방지");
        }
        // 같은-계열주 가드(2026-07-24 HLB 3종 −75,214원 계기): HLB제약/이노베이션/생명과학처럼 그룹명이 같은
        // 종목들은 동일 뉴스로 동반 급등락 — 사실상 한 베팅인데 섹터 한도(3)로는 못 막았다. 회사명에서 그룹 키
        // (선행 영문 또는 한글 2자)를 뽑아, 같은 키의 활성 포지션이 이미 있으면 실주문 스킵(섀도우는 무관).
        // ⚠️ 휴리스틱 한계: "대한항공/대한해운"류 오탐 가능 — 비용은 두 번째 진입 스킵뿐이라 보수 수용. 0=비활성.
        if (maxPositionsPerGroup > 0 && companyRepository != null) {
            String group = groupKeyOf(companyName(stockCode));
            if (group != null) {
                long held = orderRepository.findOpenBuyPositions().stream()
                        .map(o -> groupKeyOf(companyName(o.getStockCode())))
                        .filter(g -> group.equals(g))
                        .count();
                if (held >= maxPositionsPerGroup) {
                    log.info("[주문] 같은 계열({}) 기보유 {}건 — 계열 중복 진입 스킵 [{}] {}", group, held, strategy, stockCode);
                    return OrderResult.rejected("같은 계열(" + group + ") 활성 포지션 존재 — 동반 급등락 집중 방지");
                }
            }
        }
        // 전략별 동시 보유 상한 — 신호가 많은 전략이 선착순으로 예산을 독점하는 것 방지(미지정이면 무제한).
        // ⚠️ 위치는 잔고조회 앞 — 어차피 막힐 진입에 KIS 호출을 쓰지 않는다(같은-종목/계열 가드와 동일).
        long posCap = positionCapFor(strategy);
        if (posCap > 0) {
            long held = orderRepository.countActivePositionsByStrategy(strategy);
            if (held >= posCap) {
                log.info("[주문] 전략별 보유 상한 도달({}/{}) — 진입 스킵 [{}] {}", held, posCap, strategy, stockCode);
                return OrderResult.rejected("전략별 동시 보유 상한 도달(" + held + "/" + posCap + ")");
            }
        }
        long netAssets = fetchNetAssets();
        if (netAssets <= 0) {
            log.warn("[주문] 순자산 산정 불가(계좌 평가액 0/조회실패) — 스킵 [{}] {}", strategy, stockCode);
            return OrderResult.rejected("순자산 산정 불가(계좌 평가액)");
        }
        long cap = Math.round(netAssets * policy.effectiveMaxOrderPct() / 100.0);
        // 레이어 3.1: ATR 변동성기반 사이징(1주문 상한 천장). ATR 미산출/비활성이면 고정 상한 사이징.
        PositionSizer.Sizing sizing = positionSizer.size(stockCode, price, netAssets);
        long qty = sizing.qty();
        if (qty <= 0) {
            log.info("[주문] 수량 0(1주문 한도 {}원 < 1주 {}원) — 스킵 [{}] {}", cap, price, strategy, stockCode);
            return OrderResult.rejected("수량 0(가격이 1주문 한도 초과)");
        }
        // ③ fallback(미검증 국면/인버스 부트스트랩) 진입은 사이징 축소 — 통과해도 실돈 노출을 줄임.
        //    INVERSE는 전용 부트스트랩 배수(기본 0.3 = 일반 비중의 30%), 그 외 국면 fallback은 fallbackSizeMult(0.5).
        if (fallbackEntry) {
            double mult = "INVERSE".equals(market)
                    ? performanceGate.inverseBootstrapSizeMult() : performanceGate.fallbackSizeMult();
            long reduced = Math.max(1, Math.round(qty * mult));
            if (reduced < qty) {
                log.info("[주문] fallback 축소사이징 [{}] {} {}주→{}주(×{})", strategy, stockCode, qty, reduced, mult);
                qty = reduced;
            }
        }
        log.debug("[주문] 사이징 [{}] {} {}주 — {}", strategy, stockCode, qty, sizing.basis());
        // 레이어 3: 국면연동 총노출 상한 + 서킷브레이커 (DRY_RUN/LIVE 공통 — 리스크관리 흐름을 dry-run으로도 검증)
        // ⚠️ 인버스 ETF(market="INVERSE")는 하락장/급락이 기회 → 서킷·노출 상한에서 면제(리스크가드 건너뜀).
        //    per-order 사이징·max-positions·일일손실 한도(PolicyGate)는 그대로 적용돼 여전히 bounded.
        if (!"INVERSE".equals(market)) {
            MarketRiskGuard.RiskDecision risk = riskGuard.allowEntry(qty * price, netAssets, sector, market);
            if (!risk.allowed()) {
                log.info("[주문] 리스크 가드 차단 — 주문 스킵 [{}] {}: {}", strategy, stockCode, risk.reason());
                return OrderResult.rejected("리스크 가드 차단: " + risk.reason());
            }
        } else {
            log.info("[주문] 인버스 — 서킷/노출 면제 진입 [{}] {}", strategy, stockCode);
        }
        // 진입 시점 권장 청산 보유시간을 락 — 이후 provider 갱신에 흔들리지 않게 Order에 저장.
        int holdMin = holdTimeProvider.holdMinutes(strategy);
        return submit(new OrderCommand(strategy, stockCode, OrderSide.BUY, qty, price, cap, idempotencyKey, sector, holdMin, market));
    }

    /** 수동 주문(관리 API) — 1주문 한도를 산정해 검증 후 제출. */
    public OrderResult submitManual(String strategy, String stockCode, OrderSide side,
                                    long qty, long price, String idempotencyKey) {
        return submit(new OrderCommand(strategy, stockCode, side, qty, price, perOrderCapKrw(), idempotencyKey, null, null, null));
    }

    /** 1주문 허용 상한(원) = 계좌 순자산 × maxOrderPct. 조회 실패/0 이면 0(사이징 불가). */
    private long perOrderCapKrw() {
        long netAssets = fetchNetAssets();
        return netAssets <= 0 ? 0 : Math.round(netAssets * policy.effectiveMaxOrderPct() / 100.0);
    }

    // 순자산 단기 캐시 — 진입마다 잔고를 조회하면 계좌 원장 초당한도(EGW00215)를 자주 초과하므로,
    // 스캔 내 거의 불변인 순자산을 TTL 동안 재사용한다. 조회 실패 시 직전 정상값으로 degrade(사이징 누락 방지).
    private static final long NET_ASSETS_TTL_MS = 60_000;
    private volatile long cachedNetAssets = 0;
    private volatile long netAssetsAt = 0;

    /** 계좌 순자산(원) — TTL 캐시. 캐시 유효하면 재사용, 조회 실패 시 직전 정상값. 한 번도 못 받았으면 0. */
    private long fetchNetAssets() {
        long now = System.currentTimeMillis();
        if (cachedNetAssets > 0 && (now - netAssetsAt) < NET_ASSETS_TTL_MS) {
            return cachedNetAssets;
        }
        try {
            KisBalanceResponse bal = kisApiClient.fetchBalance();
            if (bal.summary() == null || bal.summary().isEmpty()) {
                return cachedNetAssets;   // 응답 비면 직전값 유지
            }
            long na = parseLong(bal.summary().get(0).netAsset());
            if (na > 0) {
                cachedNetAssets = na;
                netAssetsAt = now;
            }
            return na > 0 ? na : cachedNetAssets;
        } catch (Exception ex) {
            log.warn("계좌 평가액 조회 실패 — 직전 순자산({})으로 degrade: {}", cachedNetAssets, ex.getMessage());
            return cachedNetAssets;   // 실패 시 직전 정상값(없으면 0 → 사이징 거부)
        }
    }

    private long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Long.parseLong(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 주문 시도. 정책 거부 시 기록 없이 REJECTED 반환, 통과 시 mode 에 따라 dry-run/실전 처리.
     * REQUIRES_NEW — 호출자(신호 평가) 트랜잭션과 분리해 주문 실패가 가상매수 기록을 롤백하지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResult submit(OrderCommand cmd) {
        ZonedDateTime now = ZonedDateTime.now(SEOUL);
        String today = now.format(YYYYMMDD);
        LocalTime nowTime = now.toLocalTime();

        // 지정가를 KRX 호가단위 격자에 스냅(매수 올림/매도 내림). 격자 밖 가격은 KIS 가 "호가단위 오류"로
        // 거부하므로 반드시 정렬. 정책 검증·기록·전송 모두 스냅된 실제 전송가로 통일한다.
        long orderPrice = ExecutionCostModel.snapToTick(cmd.price(), cmd.side() == OrderSide.BUY);

        PolicyGate.OrderRequest req = new PolicyGate.OrderRequest(
                cmd.strategy(), cmd.stockCode(), cmd.side(),
                cmd.quantity(), orderPrice, cmd.maxKrw(), cmd.idempotencyKey(), nowTime);

        PolicyGate.PolicyDecision decision = policyGate.evaluate(req, today);
        if (!decision.allowed()) {
            log.info("주문 거부 [{}] {} {}x{}: {}",
                    cmd.strategy(), cmd.stockCode(), cmd.quantity(), orderPrice, decision.reason());
            return OrderResult.rejected(decision.reason());
        }

        Order order = new Order(cmd.idempotencyKey(), cmd.strategy(), cmd.stockCode(), cmd.side(),
                cmd.quantity(), orderPrice, policy.mode(), today);
        order.setSector(cmd.sector());
        order.setMarket(cmd.market());             // 시장별 서킷 판정용(청산 시)
        order.setHoldMinutes(cmd.holdMinutes());   // 진입 시점 권장 보유시간 락
        order.setNote(cmd.note());                 // 알림용(매도 청산 사유 등, 비영속)

        // 수동 승인 게이트(LIVE 매수만): 큐잉 + Discord 통지, 승인 후 발사. (DRY_RUN·매도는 즉시 실행)
        if (policy.manualConfirm() && policy.mode() == TradingMode.LIVE && cmd.side() == OrderSide.BUY) {
            order.markPendingApproval();
            orderRepository.save(order);
            discordNotifier.send(buildApprovalMessage(order));
            log.info("[승인대기] [{}] {} {}주 @ {}원 id={}",
                    cmd.strategy(), cmd.stockCode(), cmd.quantity(), cmd.price(), order.getId());
            return OrderResult.pending(order.getId());
        }
        return executeOrder(order);
    }

    /** 실제 발송/기록 — DRY_RUN은 기록만, LIVE는 KIS 현금주문(지정가) 전송. */
    private OrderResult executeOrder(Order order) {
        if (policy.mode() == TradingMode.DRY_RUN) {
            order.markDryRun();
            orderRepository.save(order);
            log.info("[DRY-RUN] 주문 기록 [{}] {} {} {}주 @ {}원 (≈{}원) id={}",
                    order.getStrategy(), order.getSide(), order.getStockCode(), order.getRequestedQty(),
                    order.getRequestedPrice(), order.getRequestedKrw(), order.getId());
            return OrderResult.dryRun(order.getId());
        }
        orderRepository.save(order);   // 영속화(멱등성 키 확정)
        try {
            KisOrderResponse resp = kisApiClient.orderCash(
                    order.getStockCode(), order.getSide(), order.getRequestedQty(),
                    order.getRequestedPrice(), ORD_DVSN_LIMIT);
            if (resp.isSuccess()) {
                order.markSubmitted();
                if (resp.output() != null) {
                    order.setBrokerOrderNo(resp.output().orderNo());
                    order.setBrokerOrgNo(resp.output().exchangeOrgNo());
                }
                orderRepository.save(order);
                log.info("[LIVE] 주문 접수 [{}] {} {} {}주 @ {}원 ODNO={}",
                        order.getStrategy(), order.getSide(), order.getStockCode(), order.getRequestedQty(),
                        order.getRequestedPrice(), resp.output() == null ? "-" : resp.output().orderNo());
                notifyEvent(buildSubmittedMessage(order, resp.output() == null ? "-" : resp.output().orderNo()));
                return OrderResult.submitted(order.getId());
            }
            order.markRejected();
            orderRepository.save(order);
            log.warn("[LIVE] 주문 거부 [{}] {}: {}", order.getStrategy(), order.getStockCode(), resp.message());
            notifyEvent(String.format("🔴 **%s 거부** %s\n• 전략: %s · %d주 @ %,d원\n• 사유: %s",
                    sideKor(order.getSide()), stockDisplay(order.getStockCode()),
                    label(order.getStrategy()), order.getRequestedQty(), order.getRequestedPrice(), resp.message()));
            return OrderResult.failed(order.getId(), "KIS 거부: " + resp.message());
        } catch (Exception ex) {
            order.markFailed();
            orderRepository.save(order);
            log.error("[LIVE] 주문 오류 [{}] {}: {}", order.getStrategy(), order.getStockCode(), ex.getMessage());
            notifyEvent(String.format("🔴 **%s 오류** %s\n• 전략: %s\n• 사유: %s",
                    sideKor(order.getSide()), stockDisplay(order.getStockCode()),
                    label(order.getStrategy()), ex.getMessage()));
            return OrderResult.failed(order.getId(), "주문 오류: " + ex.getMessage());
        }
    }

    /**
     * LIVE 접수 알림 — 종목명 표시, 매수는 청산예정, 매도는 청산 사유 + 익절/손절예상(매수가 대비) 포함.
     */
    private String buildSubmittedMessage(Order o, String odno) {
        StringBuilder sb = new StringBuilder();
        if (o.getSide() == OrderSide.BUY) {
            sb.append("🟢 **매수 접수** ").append(stockDisplay(o.getStockCode())).append("\n");
            sb.append(String.format("• 전략: %s · %,d주 @ %,d원 (≈%,d원)\n",
                    label(o.getStrategy()), o.getRequestedQty(), o.getRequestedPrice(),
                    o.getRequestedQty() * o.getRequestedPrice()));
            String exit = expectedExitDesc(o);
            if (!exit.isBlank()) sb.append("• ").append(exit.replaceFirst("^ · ", "")).append("\n");
        } else {
            sb.append("🔵 **매도 접수** ").append(stockDisplay(o.getStockCode())).append(sellPnlTag(o)).append("\n");
            String note = o.getNote();
            sb.append(String.format("• 전략: %s%s · %,d주 @ %,d원\n",
                    label(o.getStrategy()),
                    (note == null || note.isBlank()) ? "" : " · 사유: " + note,
                    o.getRequestedQty(), o.getRequestedPrice()));
        }
        sb.append("• ODNO ").append(odno).append("\n_").append(Disclaimer.SHORT).append("_");
        return sb.toString();
    }

    /** 매도 접수의 익절/손절예상 태그 — 부모 매수(멱등키 SELL:{buyId}) 체결가 대비 매도 지정가. 산출 불가면 "". */
    private String sellPnlTag(Order sell) {
        String idem = sell.getIdempotencyKey();
        if (idem == null || !idem.startsWith("SELL:")) return "";
        try {
            Order buy = orderRepository.findById(Long.parseLong(idem.substring(5))).orElse(null);
            if (buy == null) return "";
            long buyPrice = (buy.getAvgFillPrice() != null && buy.getAvgFillPrice() > 0)
                    ? buy.getAvgFillPrice() : buy.getRequestedPrice();
            if (buyPrice <= 0) return "";
            double pct = (sell.getRequestedPrice() - buyPrice) * 100.0 / buyPrice;
            return String.format(" — %s %+.2f%% (매수 %,d → %,d)",
                    pct >= 0 ? "✅ 익절예상" : "🛑 손절예상", pct, buyPrice, sell.getRequestedPrice());
        } catch (Exception e) {
            return "";
        }
    }

    private static String sideKor(OrderSide side) {
        return side == OrderSide.BUY ? "매수" : "매도";
    }

    /** 전략 라벨(예: 수축돌파 (H)) — 빈 미주입(테스트)이면 원시명. 알림 재사용을 위해 public. */
    public String label(String strategy) {
        if (strategyBeans != null) {
            for (var s : strategyBeans) if (s.name().equals(strategy)) return s.label();
        }
        return strategy;
    }

    /** 종목 표시명 "종목명(코드)" — 미주입(테스트)/미매핑이면 코드만. 알림 재사용을 위해 public. */
    public String stockDisplay(String stockCode) {
        if (companyRepository != null && stockCode != null) {
            try {
                var c = companyRepository.findById(stockCode).orElse(null);
                if (c != null && c.getName() != null && !c.getName().isBlank()) {
                    return c.getName() + "(" + stockCode + ")";
                }
            } catch (Exception ignored) {
                // 이름 조회 실패는 알림을 막지 않는다 — 코드만 표시
            }
        }
        return stockCode;
    }

    /** LIVE 매수 청산예정 표시 — 스윙=익일종가, 그 외=진입+보유시간(락). 손절/서킷/상한가는 조기 청산. 데이터 없으면 "". */
    private String expectedExitDesc(Order o) {
        if (o.getSide() != OrderSide.BUY) return "";
        java.util.Set<String> swing = swingStrategiesCsv == null ? java.util.Set.of() : PolicyGate.parseCsv(swingStrategiesCsv);
        if (o.getStrategy() != null && swing.contains(o.getStrategy())) return " · 청산예정 익일 종가(스윙)";
        Integer hm = o.getHoldMinutes();
        if (hm == null || hm <= 0 || o.getCreatedAt() == null) return "";
        String t = o.getCreatedAt().atZone(SEOUL).toLocalTime().plusMinutes(hm).format(HHMM);
        return String.format(" · 청산예정 ~%s(%d분 보유, 손절/서킷/상한가 시 조기)", t, hm);
    }

    /** 해당 종목의 활성(미청산) 실포지션 존재 여부 — 인버스 재진입 자격 판정 등에 사용. */
    public boolean hasActivePosition(String stockCode) {
        return orderRepository.countActivePositionsByStockCode(stockCode) > 0;
    }

    /** 운영 이벤트 알림(실패해도 주문 흐름을 깨지 않게 격리). LIVE 주문 경로에서만 호출. */
    public void notifyEvent(String msg) {
        try {
            discordNotifier.send(msg);
        } catch (Exception e) {
            log.warn("이벤트 알림 발송 실패: {}", e.getMessage());
        }
    }

    /** 승인 대기 주문을 승인해 발사. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResult approve(long orderId) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null || o.getStatus() != OrderStatus.PENDING_APPROVAL) {
            return OrderResult.rejected("승인 대상 아님(id=" + orderId + ")");
        }
        if (!policy.enabled() || policyGate.isKillSwitch()) {
            return OrderResult.rejected("매매 비활성/킬스위치 — 승인 거부");
        }
        log.info("[승인] id={} [{}] {} 발사", orderId, o.getStrategy(), o.getStockCode());
        return executeOrder(o);
    }

    /** 승인 대기 주문 거부. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResult reject(long orderId) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null || o.getStatus() != OrderStatus.PENDING_APPROVAL) {
            return OrderResult.rejected("거부 대상 아님(id=" + orderId + ")");
        }
        o.markRejectedByApproval();
        orderRepository.save(o);
        log.info("[거부] id={} [{}] {}", orderId, o.getStrategy(), o.getStockCode());
        return OrderResult.rejected("수동 거부(id=" + orderId + ")");
    }

    /** 승인 대기 목록. */
    public List<Order> pendingApprovals() {
        return orderRepository.findByStatus(OrderStatus.PENDING_APPROVAL);
    }

    /** 승인 타임아웃 경과한 대기 주문 자동 거부 (늦은 진입 방지). @return 만료 건수 */
    @Transactional
    public int expireStaleApprovals() {
        List<Order> pending = orderRepository.findByStatus(OrderStatus.PENDING_APPROVAL);
        Instant now = Instant.now();
        int expired = 0;
        for (Order o : pending) {
            if (Duration.between(o.getCreatedAt(), now).toMinutes() >= policy.approvalTimeoutMinutes()) {
                o.markRejectedByApproval();
                orderRepository.save(o);
                expired++;
                log.info("[승인만료] id={} [{}] {} — {}분 미승인 자동거부",
                        o.getId(), o.getStrategy(), o.getStockCode(), policy.approvalTimeoutMinutes());
            }
        }
        return expired;
    }

    private String buildApprovalMessage(Order o) {
        return String.format("""
                🟡 **[승인 대기] 매수** %s
                • 전략: %s
                • 수량/가격: %,d주 @ %,d원 (≈%,d원)
                • 승인: `POST /api/v1/admin/orders/%d/approve`
                • 거부: `POST /api/v1/admin/orders/%d/reject`
                • %d분 내 미승인 시 자동 거부

                _%s_""",
                stockDisplay(o.getStockCode()), label(o.getStrategy()),
                o.getRequestedQty(), o.getRequestedPrice(), o.getRequestedKrw(),
                o.getId(), o.getId(), policy.approvalTimeoutMinutes(), Disclaimer.SHORT);
    }
}
