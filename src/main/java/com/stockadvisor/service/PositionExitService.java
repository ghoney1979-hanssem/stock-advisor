package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 시간기반 청산 실행. 미청산 매수 포지션을 주기적으로 점검해
 * ① 보유시간(전략별 권장 보유시간, {@link StrategyHoldTimeProvider}) 경과 또는 ② 장마감(sessionEnd) 도달 시 매도 주문을 낸다.
 *
 * <p>매도는 {@link OrderService#submit}(REQUIRES_NEW)로 처리(DRY_RUN이면 기록만, LIVE면 KIS 전송).
 * 청산 성공 시 원 매수 주문을 closed 처리하고 확정손익(realizedPnl)을 기록 — 일일 손실 한도 집계의 입력이 된다.</p>
 *
 * <p>⚠️ 현재 청산가는 조회 현재가(지정가 근사)이며 실제 체결가는 아니다(체결조회 미구현). DRY_RUN 분석엔 충분.</p>
 */
@Service
public class PositionExitService {

    private static final Logger log = LoggerFactory.getLogger(PositionExitService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final KisApiClient kisApiClient;
    private final TradingPolicyProperties policy;
    private final StrategyHoldTimeProvider holdTimeProvider;
    private final MarketRiskGuard riskGuard;
    private final ExitMethodProvider exitMethodProvider;
    private final StrategyStopProvider stopProvider;   // 전략별 적응형 손절선(fail-closed → 고정 −7%)

    // 스윙 트레일링 — fail-closed: 검증(swing-trail-analysis)에서 트레일이 익일보유보다 나을 때만 그 %, 아니면 0(익일종가 보유).
    // 필드주입(생성자 무변경). 검증 전엔 항상 0이라 실청산이 안 바뀜.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SwingExitProvider swingExitProvider;
    private double swingTrailPctTest = 0;                            // 테스트 fallback(provider 미주입 시)
    void setSwingTrailPct(double p) { this.swingTrailPctTest = p; }   // 테스트용

    // 상한가 조기청산 — 당일 등락률이 이 %p 이상(상한가 +30% 근접)이면 전 종목 즉시 청산(상방 천장·갭리스크). 0=비활성.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.limit-up-lock-pct:29.0}")
    private double limitUpLockPct;

    // [조기청산 방지 ②] 진입 후 이 분(minutes) 안에는 신호기반(VWAP/트레일링/추세전환/흐름반전) 청산 금지 — 포지션이 숨 쉴 시간.
    // TIME(시간경과)·손절·서킷·상한가·장마감은 이 가드 밖이라 무관. 0=비활성(종전). D whipsaw(진입 1~7분 저가매도) 대응.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.adaptive-exit-method.min-hold-minutes:0}")
    private int methodMinHoldMinutes;
    // [조기청산 방지 ③] VWAP 이탈 히스테리시스 — 단일 터치가 아니라 VWAP×(1−이값/100) 하향돌파 시에만 청산(라이브 1분 과민 완화). 0=종전(터치).
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.adaptive-exit-method.vwap-buffer-pct:0.0}")
    private double vwapBufferPct;

    // 왕복 매매비용(수수료+거래세, 매수금액 기준 %) — DRY_RUN 즉시청산 손익도 LIVE(FillSync)와 동일하게 net 기록.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.cost.round-trip-pct:0.22}")
    private double roundTripCostPct = 0.22;   // 테스트(생성자 생성)는 초기값 사용

    // ── 인버스 전용 청산(2026-07-14) ── 진입(지수 약세)과 대칭: 지수가 계속 빠지는 동안 시간 무관 보유,
    // 약세 명제 소멸(레벨 회복 or 모멘텀 반등) 시 청산. 시간청산·트레일링·적응형 방식(개별종목 표본 학습값)은
    // 지수 미러 자산에 부적합해 미적용(실측 7/13: 시간청산이 수익 절반~80%를 놓침). 손절도 지수 스케일(-2%)로 교체.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.enabled:true}")
    private boolean inverseExitEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.recovery-pct:0.5}")
    private double inverseExitRecoveryPct = 0.5;    // 지수 당일 등락률 > -이값% → 약세 소멸(진입 -1%보다 완화 = 히스테리시스)
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.rebound-mom-pct:0.3}")
    private double inverseExitReboundMomPct = 0.3;  // 지수 최근 모멘텀(mom30) ≥ +이값% → 반등 시작(기저 임계)
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.rebound-day-scale:0.15}")
    private double inverseExitReboundDayScale = 0.15;  // 반등 임계 낙폭 비례 상향: thr=max(기저, |당일등락|×이값). 0=비활성(기저 고정)
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.stop-pct:2.0}")
    private double inverseExitStopPct = 2.0;        // 인버스 가격 손절(지수 +2% 역행=명제 오류) — 적응형 -5~7%는 지수 ETF에 도달 불가
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.max-hold-minutes:300}")
    private int inverseExitMaxHoldMinutes = 300;    // 백스톱(판정 불가·데이터 실패 대비)
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.inverse-index-map:114800:0001,251340:1001}")
    private String inverseIndexMapCsv = "114800:0001,251340:1001";
    // 매도 주문 컷오프 — KRX 정규장 접수 마감(15:30). 이후엔 청산 제출 보류(다음 거래일 처리).
    // ⚠️ 필드 초기값은 의도적으로 23:59(비활성) — 실제 시계를 쓰는 기존 단위테스트 26곳이 실행 시각에 따라
    // 깨지지 않게. 프로덕션은 Spring @Value가 15:30(또는 env)으로 덮어씀.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.exit-order-cutoff:15:30}")
    private String exitOrderCutoffRaw = "23:59";
    void setExitOrderCutoff(String v) { this.exitOrderCutoffRaw = v; }   // 테스트용
    private java.time.LocalTime exitOrderCutoff() { return java.time.LocalTime.parse(exitOrderCutoffRaw); }
    /** 컷오프 판정(순수) — now ≥ cutoff면 청산 제출 보류. */
    static boolean isAfterOrderCutoff(java.time.LocalTime now, java.time.LocalTime cutoff) {
        return !now.isBefore(cutoff);
    }

    // 휴장일 가드(2026-07-17 실측: 휴장일에 매분 매도 제출 → KIS "장운영일자 상이" 거부 390건+알림 스팸).
    // 별도 휴장 캘린더 없이 KIS 거부 메시지 자체를 휴장 신호로 사용 — 첫 거부에서 당일 제출 전면 중단(자정/재기동 리셋).
    private volatile String marketHolidayDate;
    /** 휴장 거부 판정(순수) — KIS "장운영일자가 주문일과 상이합니다"(휴장/장운영일 불일치). */
    static boolean isHolidayRejection(String message) {
        return message != null && message.contains("장운영일자");
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeService marketRegimeService;   // 모멘텀 반등 판정(미주입/미가용 시 레벨 판정만 — degrade)
    void setMarketRegimeService(MarketRegimeService s) { this.marketRegimeService = s; }   // 테스트용
    void setLimitUpLockPct(double p) { this.limitUpLockPct = p; }     // 테스트용
    void setMethodMinHoldMinutes(int m) { this.methodMinHoldMinutes = m; }   // 테스트용
    void setVwapBufferPct(double p) { this.vwapBufferPct = p; }              // 테스트용

    private final java.util.Set<String> swingStrategies;   // 오버나잇 스윙 — 장마감 강제청산 대신 익일 종가 청산
    // 서킷브레이커 전이 알림용(edge-trigger) — 시장별(KOSPI/KOSDAQ) 발동/해제 시 1회만 통지
    private final java.util.Map<String, Boolean> wasRiskOff = new java.util.concurrent.ConcurrentHashMap<>();
    // 시장폭(breadth) 리스크오프 전이 알림용 — 서킷과 별개 축(진입 차단 전용)
    private final java.util.Map<String, Boolean> wasBreadthOff = new java.util.concurrent.ConcurrentHashMap<>();

    // 인버스 ETF 코드 — 급락(서킷)이 기회라 리스크오프 강제청산에서 면제(승자 보유). 손절·시간청산은 그대로.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.inverse-codes:114800,251340}")
    private String inverseCsv = "114800,251340";   // 기본 초기값(Spring이 @Value로 override; 단위테스트는 이 값 사용)
    private volatile java.util.Set<String> inverseSet;
    private boolean isInverse(String code) {
        java.util.Set<String> s = inverseSet;
        if (s == null) {
            s = java.util.Arrays.stream(inverseCsv == null ? new String[0] : inverseCsv.split(","))
                    .map(String::trim).filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toSet());
            inverseSet = s;
        }
        return s.contains(code);
    }
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public PositionExitService(OrderRepository orderRepository, OrderService orderService,
                               KisApiClient kisApiClient, TradingPolicyProperties policy,
                               StrategyHoldTimeProvider holdTimeProvider, MarketRiskGuard riskGuard,
                               ExitMethodProvider exitMethodProvider,
                               @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.swing-strategies:MEAN_REVERSION_C}") String swingCsv,
                               StrategyStopProvider stopProvider) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.kisApiClient = kisApiClient;
        this.policy = policy;
        this.holdTimeProvider = holdTimeProvider;
        this.riskGuard = riskGuard;
        this.exitMethodProvider = exitMethodProvider;
        this.swingStrategies = PolicyGate.parseCsv(swingCsv);
        this.stopProvider = stopProvider;
    }

    /**
     * 청산 시점이 된 포지션을 매도한다.
     * @return 청산(매도 접수/기록) 건수
     */
    public int closeDuePositions() {
        // 레이어 3: 서킷브레이커 상태 점검 + 발동/해제 전이 알림(edge-trigger) — 시장별(코스피/코스닥 독립).
        // ⚠️ 반드시 open.isEmpty() 조기반환 前에 둔다 — 미청산 포지션이 0이어도 서킷은 신규진입을 막는
        //    시장 전체 이벤트이므로 알림은 나가야 한다(포지션 유무와 무관). (실측: 조기반환 뒤에 있어 미발송된 버그 수정)
        // 전이 "알림"은 컷오프(15:30) 이후 음소거(2026-07-24 실측: 장 종료 40분 뒤 breadth 스냅샷 신선도 만료가
        // "판정 중단"→off 전이를 만들며 ✅해제 알림으로 위장 — 폭락일마다 반복될 소음). 상태 갱신은 계속해
        // 다음 날 아침 스테일 상태로 인한 가짜 전이도 방지한다.
        boolean muteRiskAlerts = isAfterOrderCutoff(ZonedDateTime.now(SEOUL).toLocalTime(), exitOrderCutoff());
        for (String mkt : java.util.List.of("KOSPI", "KOSDAQ")) {
            boolean off = riskGuard.isRiskOff(mkt).off();
            boolean prev = wasRiskOff.getOrDefault(mkt, false);
            if (off && !prev && !muteRiskAlerts) {
                orderService.notifyEvent("⚠️ **서킷브레이커 발동(" + mkt + ")** — " + riskGuard.isRiskOff(mkt).reason()
                        + " → 해당 시장 신규진입 중단·포지션 청산 가속");
            } else if (!off && prev && !muteRiskAlerts) {
                orderService.notifyEvent("✅ **서킷 해제(" + mkt + ")** — 리스크오프 종료, 정상 운용 복귀");
            }
            wasRiskOff.put(mkt, off);

            // 시장폭(breadth) 리스크오프 전이 — 지수 서킷과 별개 축(진입 차단 전용, 청산은 안 건드림)
            MarketRiskGuard.RiskOff bro = riskGuard.breadthRiskOff(mkt);
            boolean bOff = bro != null && bro.off();
            boolean bPrev = wasBreadthOff.getOrDefault(mkt, false);
            if (bOff && !bPrev && !muteRiskAlerts) {
                orderService.notifyEvent("⚠️ **시장폭 리스크오프 발동(" + mkt + ")** — " + bro.reason()
                        + " → 해당 시장 신규진입 중단(보유 청산은 기존 규칙 유지)");
            } else if (!bOff && bPrev && !muteRiskAlerts) {
                orderService.notifyEvent("✅ **시장폭 리스크오프 해제(" + mkt + ")** — 시장 폭 회복, 신규진입 재개");
            }
            wasBreadthOff.put(mkt, bOff);
        }

        List<Order> open = orderRepository.findOpenBuyPositions();
        if (open.isEmpty()) {
            return 0;
        }
        ZonedDateTime now = ZonedDateTime.now(SEOUL);
        // 주문 컷오프(2026-07-16 안트로젠 실측): 15:30 장 종료 후엔 KIS가 매도 접수를 "장운영시간 아님"으로
        // 거부하는데 청산 cron은 16:59까지 돌아 — 매분 REJECTED+알림 스팸. 컷오프 밖이면 제출 없이 보류
        // (포지션 유지 → 다음 거래일 개장 후 첫 점검이 청산). 서킷/breadth 전이 알림은 위(이 가드 앞)라 영향 없음.
        if (isAfterOrderCutoff(now.toLocalTime(), exitOrderCutoff())) {
            log.debug("주문 컷오프({}) 이후 — 청산 제출 보류 {}건", exitOrderCutoffRaw, open.size());
            return 0;
        }
        String today = now.format(YYYYMMDD);
        if (today.equals(marketHolidayDate)) {
            log.debug("휴장일({}) 감지됨 — 청산 제출 보류 {}건(다음 거래일 처리)", today, open.size());
            return 0;
        }
        boolean sessionEnded = !now.toLocalTime().isBefore(policy.sessionEndLocalTime());
        int closed = 0;
        for (Order pos : open) {
            try {
                if (pos.getStatus() == OrderStatus.SUBMITTED) {
                    continue;   // LIVE 미체결(아직 매입 안 됨) — 청산 대상 아님
                }
                // 아직 부분체결 중인 매수는 청산 보류 — filledQty가 움직이는 값이라(예: 13→14) 그 시점 수량으로
                // 매도하면 뒤늦게 체결된 잔량이 고아가 됨(2026-08-03 골프존 215000: 14체결인데 13매도 → 1주 고아).
                // OrderCancelService가 3분 내 stale 부분매수를 FILLED(부분수량)로 정산하므로, 확정된 수량으로 매도.
                if (pos.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                    continue;
                }
                // 가격경로 추적(트레일링/추세전환)을 위해 매 점검마다 현재가 조회.
                long price = kisApiClient.fetchLatestClose(pos.getStockCode());
                if (price <= 0) {
                    log.debug("청산가 조회 0 — 보류 [{}] {}", pos.getStrategy(), pos.getStockCode());
                    continue;
                }
                long buyPrice = effectiveBuyPrice(pos);
                long heldMin = Duration.between(pos.getCreatedAt(), now.toInstant()).toMinutes();
                Long prevPrice = pos.getLastPrice();   // 직전 점검가(추세전환용) — 이번 갱신 전 값
                pos.trackPeak(price);                  // 고점 갱신(트레일링용)

                // ① 안전 오버라이드(항상 우선, 전 종목·스윙 포함): 상한가익절 > 손절 > 리스크오프(해당 종목 시장 서킷만)
                String reason = null;
                boolean swing = swingStrategies.contains(pos.getStrategy());
                MarketRiskGuard.RiskOff posRiskOff = riskGuard.isRiskOff(pos.getMarket());   // 시장별 — 타 시장 폭락엔 강제청산 안 함
                boolean inversePos = inverseExitEnabled && isInverse(pos.getStockCode());
                // 인버스는 지수 스케일 손절(-2%) — 전략별 적응형(-5~7%)은 지수 ETF엔 사실상 도달 불가(무방비).
                double stopPct = inversePos ? inverseExitStopPct : stopProvider.stopPct(pos.getStrategy());
                // 상한가 조기청산(전 종목 최우선 익절) — 오늘 더 못 오름(상방 천장) + 물량 풀리면 갭리스크 → 잠금. 이익 종목만 등락률 조회(losers는 상한가 아님 → KIS콜 절감). 인버스는 ±1x라 상한가 무관 — 조회 생략.
                double dayChgPct = (limitUpLockPct > 0 && price > buyPrice && !inversePos) ? kisApiClient.fetchDayChangeRate(pos.getStockCode()) : 0;
                if (limitUpLockPct > 0 && dayChgPct >= limitUpLockPct) {
                    reason = String.format("상한가익절 (+%.1f%%)", dayChgPct);
                } else if (stopPct > 0 && riskGuard.catastrophicStopHit(buyPrice, price, stopPct)) {
                    reason = String.format("손절 -%.1f%%", stopPct);
                } else if (posRiskOff.off() && !isInverse(pos.getStockCode())) {
                    // 인버스는 급락(리스크오프)이 기회 → 강제청산 면제(손절·시간청산은 아래에서 적용)
                    reason = "리스크오프(" + posRiskOff.reason() + ")";
                } else if (inversePos) {
                    // 인버스 전용: 약세 명제 소멸 시 청산, 지속 시 시간 무관 보유. 스윙보다 먼저(인버스는 다일 감쇠 → 무조건 당일 청산).
                    reason = inverseExitReason(pos, sessionEnded, heldMin);
                } else if (swing) {
                    // ② 스윙(오버나잇): 기본은 익일종가 청산. 트레일%는 fail-closed provider가 결정(검증 전엔 0=보유).
                    //    이익구간(peak>매수)에서만 arm — 초기 눌림을 조기 컷하지 않도록(C 엣지 보존). peak는 위 trackPeak로 당일+익일 갱신.
                    double swingTrailPct = swingExitProvider != null
                            ? swingExitProvider.trailPct(pos.getStrategy()) : swingTrailPctTest;
                    Long peak = pos.getPeakPrice();
                    if (swingTrailPct > 0 && peak != null && peak > buyPrice && price <= peak * (1 - swingTrailPct / 100.0)) {
                        reason = String.format("스윙트레일 -%.1f%%(고점되돌림,검증채택)", swingTrailPct);
                    } else if (sessionEnded && !today.equals(pos.getOrderDate())) {
                        reason = "스윙청산(익일종가)";
                    }
                } else if (sessionEnded) {
                    reason = "장마감";
                } else {
                    // ③ 적응형 청산방식(전략별 평균수익 최대). 표본부족/비활성이면 TIME.
                    reason = methodExitReason(pos, price, heldMin, prevPrice);
                }
                pos.setLastPrice(price);   // 다음 점검의 추세전환 기준
                if (reason == null) {
                    orderRepository.save(pos);   // 청산 안 함 → 추적값(peak/last)만 영속화
                    continue;
                }
                if (closePosition(pos, price, buyPrice, reason)) {
                    closed++;
                    if (reason.startsWith("손절")) {   // 재난 손절은 별도 강조 통지
                        double pct = buyPrice > 0 ? (price - buyPrice) * 100.0 / buyPrice : 0;
                        orderService.notifyEvent(String.format("🛑 **손절 청산** %s\n• 전략: %s · 매수 %,d → 현재 %,d (%.1f%%)",
                                orderService.stockDisplay(pos.getStockCode()),
                                orderService.label(pos.getStrategy()), buyPrice, price, pct));
                    }
                }
            } catch (Exception ex) {
                log.warn("청산 실패 [{}] {} (id={}): {}",
                        pos.getStrategy(), pos.getStockCode(), pos.getId(), ex.getMessage());
            }
        }
        if (closed > 0) {
            log.info("포지션 청산 {}건", closed);
        }
        return closed;
    }

    /**
     * 인버스 전용 청산 판정 — 진입(지수 약세 + 하락 중)의 대칭. 약세 명제가 살아있으면 null(보유), 소멸이면 사유 반환.
     * ① 장마감(다일 감쇠 — 오버나잇 금지) ② 최대보유 백스톱 ③ 지수 레벨 회복(당일 등락률 > -recovery)
     * ④ 지수 모멘텀 반등(mom10/30 ≥ +rebound). 지수 조회/흐름 미가용 시 해당 판정 생략(장마감 백스톱이 최후 보장).
     */
    private String inverseExitReason(Order pos, boolean sessionEnded, long heldMin) {
        if (sessionEnded) return "장마감";
        if (heldMin >= inverseExitMaxHoldMinutes) return "인버스 최대보유 " + inverseExitMaxHoldMinutes + "분(백스톱)";
        String idx = inverseIndexMap().get(pos.getStockCode());
        if (idx == null) return null;   // 지수 맵 없음 — 판정 불가, 백스톱만
        Double chg = safeIndexChange(idx);
        if (chg != null && chg > -inverseExitRecoveryPct) {
            return String.format("지수회복(%.2f%% > -%.1f%%) — 약세 소멸", chg, inverseExitRecoveryPct);
        }
        if (marketRegimeService != null) {
            String market = "0001".equals(idx) ? "KOSPI" : "KOSDAQ";
            MarketRegimeService.IntradayFlow flow = marketRegimeService.intradayFlow(market);
            // 반등 판정은 mom30만(2026-07-16 과민 청산 대응 — mom10은 폭락일 데드캣 미세반등에 그대로 발사돼
            // "+0.3%에 팔고 반등 끝난 더 높은 가격에 재매수"를 반복. mom30 미산출(개장 30분 내)이면 판정 생략,
            // 레벨회복·손절·백스톱이 방어). 임계는 당일 낙폭 비례 상향 — -6.7%일의 +0.3%는 낙폭의 4.5%짜리 노이즈.
            Double mom = (flow != null && flow.available()) ? flow.mom30Pct() : null;
            double thr = inverseExitReboundMomPct;
            if (chg != null && inverseExitReboundDayScale > 0) {
                thr = Math.max(thr, Math.abs(chg) * inverseExitReboundDayScale);
            }
            if (mom != null && mom >= thr) {
                return String.format("지수반등(mom30 %+.2f%% ≥ %.2f%%)", mom, thr);
            }
        }
        return null;   // 지수 약세 지속 — 시간 무관 보유
    }

    private volatile java.util.Map<String, String> inverseIndexMapCache;
    private java.util.Map<String, String> inverseIndexMap() {
        java.util.Map<String, String> m = inverseIndexMapCache;
        if (m == null) {
            m = new java.util.HashMap<>();
            if (inverseIndexMapCsv != null) {
                for (String pair : inverseIndexMapCsv.split(",")) {
                    String[] kv = pair.split(":");
                    if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) m.put(kv[0].trim(), kv[1].trim());
                }
            }
            inverseIndexMapCache = m;
        }
        return m;
    }

    private Double safeIndexChange(String indexCode) {
        try {
            return kisApiClient.fetchIndexChangeRate(indexCode);   // 60s 캐시 — 인버스 보유분만 호출
        } catch (Exception e) {
            log.debug("지수 조회 실패 [{}]: {}", indexCode, e.getMessage());
            return null;
        }
    }

    /**
     * 적응형 청산방식 판정 — 전략별 평균수익 최대 방식 적용. 청산이면 사유 반환, 아니면 null(보유).
     * TIME=권장 보유시간 경과 / TRAILING=고점 대비 되돌림 / VWAP=가격<VWAP / TREND_REVERSAL=직전 점검가 대비 하락.
     */
    private String methodExitReason(Order pos, long price, long heldMin, Long prevPrice) {
        ExitStrategyService.BestExit m = exitMethodProvider.methodFor(pos.getStrategy());
        // [조기청산 방지 ②] 진입 후 min-hold 분 안에는 신호기반 청산 보류(TIME 제외) — 역추세(D)가 진입 직후
        // VWAP 아래에서 즉시 컷되는 whipsaw 방지. 손절·서킷·상한가·장마감은 이 함수 밖(상위 안전 오버라이드)이라 무관.
        if (methodMinHoldMinutes > 0 && m.type() != com.stockadvisor.domain.ExitMethodType.TIME
                && heldMin < methodMinHoldMinutes) {
            return null;
        }
        switch (m.type()) {
            case TRAILING -> {
                long peak = pos.getPeakPrice() == null ? price : pos.getPeakPrice();
                if (peak > 0 && price <= peak * (1.0 - m.param() / 100.0)) {
                    return String.format("트레일링 -%.1f%%(고점 %d)", m.param(), peak);
                }
            }
            case VWAP -> {
                Double vwap = safeVwap(pos.getStockCode());
                // [조기청산 방지 ③] 단일 터치가 아니라 VWAP×(1−buffer%) 하향돌파 시에만(라이브 1분 과민 완화). buffer 0=종전(터치).
                if (vwap != null && vwap > 0 && price < vwap * (1.0 - vwapBufferPct / 100.0)) {
                    return vwapBufferPct > 0
                            ? String.format("VWAP이탈(%.0f, -%.1f%%)", vwap, vwapBufferPct)
                            : String.format("VWAP이탈(%.0f)", vwap);
                }
            }
            case FLOW_REVERSAL -> {
                // 지수 흐름 순풍이 꺼지면 이탈 — 진입 시장의 mom30 ≤ param(%) 음전 시 청산.
                // 흐름 미가용(개장 ~30분/조회실패/시장미상)이면 보유 — 장마감(sessionEnd) 분기가 최후 백스톱(시뮬의 EOD와 정합).
                if (marketRegimeService != null && pos.getMarket() != null && !"INVERSE".equals(pos.getMarket())) {
                    MarketRegimeService.IntradayFlow flow = marketRegimeService.intradayFlow(pos.getMarket());
                    Double mom = (flow != null && flow.available()) ? flow.mom30Pct() : null;
                    if (mom != null && mom <= m.param()) {
                        return String.format("지수흐름 반전(mom30 %+.2f%% ≤ %.1f%%)", mom, m.param());
                    }
                }
            }
            case TREND_REVERSAL -> {
                // N회 연속 하락 확인(단일 틱 휩쏘 방지). 반등/보합이면 카운터 리셋 → 지속 하락에만 청산.
                int confirm = Math.max(1, exitMethodProvider.trendConfirm());
                int down = pos.getTrendDownCount();
                if (prevPrice != null && price < prevPrice) {
                    down += 1;
                } else {
                    down = 0;
                }
                pos.setTrendDownCount(down);
                if (down >= confirm) {
                    return String.format("추세전환(%d회 연속 하락, 직전 %d↓)", down, prevPrice);
                }
            }
            default -> {   // TIME — 진입 시점에 락한 보유시간 경과(락값 없으면 live provider fallback)
                Integer locked = pos.getHoldMinutes();
                int hold = (locked != null && locked > 0) ? locked
                        : holdTimeProvider.holdMinutes(pos.getStrategy());
                if (heldMin >= hold) {
                    return "시간경과";
                }
            }
        }
        return null;
    }

    /** 현재 VWAP(실패 시 null → 청산 보류). */
    private Double safeVwap(String stockCode) {
        try {
            return kisApiClient.fetchVwapVolume(stockCode).vwap();
        } catch (Exception ex) {
            log.debug("VWAP 조회 실패 [{}]: {}", stockCode, ex.getMessage());
            return null;
        }
    }

    /** 실제 매입가 — 체결조회로 채워진 값 우선(없거나 0이면 주문값=DRY_RUN). */
    private long effectiveBuyPrice(Order pos) {
        return (pos.getAvgFillPrice() != null && pos.getAvgFillPrice() > 0) ? pos.getAvgFillPrice() : pos.getRequestedPrice();
    }

    /** 단일 포지션 매도 + closed/realizedPnl 기록 (가격/매입가는 호출측이 1회 조회해 전달). */
    private boolean closePosition(Order pos, long price, long buyPrice, String reason) {
        // 실제 보유 수량 — 체결조회로 채워진 값 우선(없거나 0이면 주문값=DRY_RUN)
        long qty = (pos.getFilledQty() != null && pos.getFilledQty() > 0) ? pos.getFilledQty() : pos.getRequestedQty();
        if (qty <= 0) {
            return false;
        }
        OrderService.OrderResult r = orderService.submit(new OrderService.OrderCommand(
                pos.getStrategy(), pos.getStockCode(), OrderSide.SELL,
                qty, price, 0, "SELL:" + pos.getId(), pos.getSector(), null, null,   // 매도는 보유시간·시장 무관(서킷 면제)
                reason));   // 청산 사유 — 매도 접수 알림에 표시
        if (!r.isAccepted()) {
            if (isHolidayRejection(r.message())) {
                marketHolidayDate = ZonedDateTime.now(SEOUL).format(YYYYMMDD);
                log.warn("휴장일 감지(KIS '장운영일자 상이') — 오늘 청산 제출 전면 중단, 다음 거래일 처리 [{}] {}",
                        pos.getStrategy(), pos.getStockCode());
            }
            log.debug("청산 매도 미실행 [{}] {}: {}", pos.getStrategy(), pos.getStockCode(), r.message());
            return false;
        }
        // LIVE: 매도 '접수'일 뿐 체결 아님 → 포지션 청산은 FillSync 가 매도 체결 확인 후 처리.
        // (미체결 매도는 OrderCancelService 가 취소 → 멱등성 풀려 다음 틱 재매도=추격)
        if (policy.mode() == TradingMode.LIVE) {
            log.info("[청산요청:{}] LIVE 매도 접수 [{}] {} ×{}주 (체결대기)",
                    reason, pos.getStrategy(), pos.getStockCode(), qty);
            return true;
        }
        // DRY_RUN: 체결 가정 — 즉시 청산 + 손익(현재가 기준, 왕복비용 차감 net — LIVE 기록과 정합)
        long cost = Math.round(buyPrice * qty * roundTripCostPct / 100.0);
        long pnl = (price - buyPrice) * qty - cost;
        pos.closePosition(pnl);
        orderRepository.save(pos);
        log.info("[청산:{}] [{}] {} 매수 {}원 → 매도 {}원 ×{}주 손익 {}원",
                reason, pos.getStrategy(), pos.getStockCode(), buyPrice, price, qty, pnl);
        return true;
    }
}
