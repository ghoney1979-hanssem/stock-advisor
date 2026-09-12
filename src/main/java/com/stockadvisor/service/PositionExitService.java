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
    // [마감청산 공격성 ②] 장마감 강제청산(스윙 익일종가 포함) 매도를 현재가 −이값% 지정가(marketable)로 접수해 즉시 체결
    // → 마감 미체결 취소 스팸·오버나잇 방지(2026-08-05 D 오버나잇 6건 계기). 실체결은 ≈매수호가라 손익 왜곡 미미(손익계산은 현재가 유지).
    // 마감청산 사유(장마감/스윙청산)에만 적용 — 손절·서킷·적응형 청산은 무관. 0=비활성(종전, 현재가 지정가).
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.exit-session-close-aggressive-pct:0.0}")
    private double sessionCloseAggressivePct;

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
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.inverse-exit.rebound-from-low-pct:1.0}")
    private double inverseExitReboundFromLowPct = 1.0;  // 반등 청산에 요구하는 '장중 저점 대비 지수 회복폭'(%p). 0=비활성(모멘텀만)
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
    void setSessionCloseAggressivePct(double p) { this.sessionCloseAggressivePct = p; }   // 테스트용

    private final java.util.Set<String> swingStrategies;   // 오버나잇 스윙 — 장마감 강제청산 대신 익일 종가 청산

    // ── 멀티데이 트레일 청산(2026-09-03, 사용자 지정 — 전략 P) ─────────────────────────────
    // 스윙(D+1 고정)과 다른 축이다: 여러 거래일을 보유하며 <b>수익률 +arm%에 한 번 도달한 뒤 고점 대비 −drop%</b>면
    // 매도하고, 미발동이면 max-hold-days(거래일) 백스톱으로 청산한다.
    //
    // ⚠️ <b>무장(arm) 요건이 스윙 트레일과의 결정적 차이</b> — 스윙은 `peak > 매수가`이기만 하면 arm돼서
    // +0.1%만 올라도 −2% 되돌림에 잘린다. 여기선 +5%를 한 번 찍어야 arm되므로 초기 눌림 구간을 통과시킨다
    // (C의 엣지가 '떨어진 걸 사서 되돌림을 먹는 것'이라 초기 조기컷이 치명적이다).
    //
    // ⚠️ <b>안전 오버라이드는 그대로 앞선다</b>(상한가익절 > 손절 > 리스크오프). 단 리스크오프 강제청산은
    // 2026-09-12부터 {@code trading.risk.riskoff-force-exit-enabled}로 끌 수 있고 <b>prod는 꺼져 있다</b> —
    // 서킷은 정의상 "장중저점 근처 + 반등 미달"일 때만 참이라 <b>항상 저점에서 발사</b>되는데, 멀티데이는
    // 그 되돌림을 먹으려고 며칠을 보유하는 전략이라 둘이 정면으로 충돌한다. 실측 근거는 아래 필드 주석 참조.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.multiday-exit.strategies:}")
    private String multidayExitCsv = "";
    private volatile java.util.Set<String> multidayExitSet;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.multiday-exit.arm-pct:5.0}")
    private double multidayArmPct = 5.0;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.multiday-exit.drop-pct:2.0}")
    private double multidayDropPct = 2.0;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.multiday-exit.max-hold-days:15}")
    private int multidayMaxHoldDays = 15;
    /** 거래일 카운트 소스 — {@code daily_price}(16:40 갱신). 미주입/조회실패면 영업일(월~금) 근사로 degrade. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 테스트용 — 멀티데이 청산 구성. */
    void configureMultiday(String csv, double armPct, double dropPct, int maxHoldDays) {
        this.multidayExitCsv = csv;
        this.multidayExitSet = null;
        this.multidayArmPct = armPct;
        this.multidayDropPct = dropPct;
        this.multidayMaxHoldDays = maxHoldDays;
    }

    private boolean isMultiday(String strategy) {
        java.util.Set<String> s = multidayExitSet;
        if (s == null) {
            s = PolicyGate.parseCsv(multidayExitCsv);
            multidayExitSet = s;
        }
        return s.contains(strategy);
    }

    // 서킷브레이커 전이 알림용(edge-trigger) — 시장별(KOSPI/KOSDAQ) 발동/해제 시 1회만 통지
    private final java.util.Map<String, Boolean> wasRiskOff = new java.util.concurrent.ConcurrentHashMap<>();
    // 시장폭(breadth) 리스크오프 전이 알림용 — 서킷과 별개 축(진입 차단 전용)
    private final java.util.Map<String, Boolean> wasBreadthOff = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 서킷(리스크오프) 발동 시 보유 포지션을 강제청산할지. {@code false}면 서킷은 <b>신규진입 차단만</b> 하고
     * 청산은 손절·트레일·만기에만 맡긴다(전이 Discord 알림은 그대로 나간다 — 진입 차단이 실제로 걸리므로).
     * <p>
     * ⚠️ <b>끈 이유(2026-09-12 사용자 결정, "손절에서만 정리")</b> — 서킷 재개 조건이 "장중 저점 대비 반등
     * {@code rebound-pct}%p 미만"이라, 발동이 참인 동안은 지수가 <b>정의상 그날 저점 부근</b>이다. 즉 이
     * 강제청산은 구조적으로 바닥에서 판다. 2026-09-11 09:01 실측: 코스피 −3.09%(장중저점)에서 멀티데이 10건이
     * 개장 1분 만에 전량 청산됐고 <b>9종목 전부 그날 장중 저가 ±0.5% 안에서 체결</b>(002350은 정확히 저가).
     * 확정 −62,340원 vs 같은 종목 종가 보유 −47,770원 = <b>+14,570원(23%) 더 잃었다</b>. 그날 코스피는
     * −3.09% → 종가 −1.73%로 회복했는데 그 회복을 통째로 놓쳤다. G의 9/7 이후 실현손실 −101,299원 중
     * <b>63%(−63,496원)가 이 한 번</b>이다.
     * <p>
     * ⚠️ <b>꼬리 방어를 포기한 것이 아니다</b> — 손절({@code catastrophic-stop-pct}, prod −12%)이 그대로 매 분
     * 작동한다. 바뀐 건 "지수가 빠졌다는 이유로 개별 종목을 저점에 던지는 것"을 그만둔 것뿐이다. 반대로
     * <b>대가는 명확하다</b>: 진짜 시스템 리스크(연쇄 폭락)에서 포지션이 손절선까지 그대로 내려간다 —
     * 서킷이 −3%에서 끊어주던 것을 이제 −12%까지 안고 간다. 폭락이 며칠 이어지면 그 차이가 실현된다.
     * <p>
     * ⚠️ 신규진입 차단은 <b>그대로 유지</b>된다({@code OrderService}가 {@code riskGuard.allowEntry}로 판정) —
     * 폭락일에 새로 사는 것과 이미 산 것을 저점에 파는 것은 다른 문제이고, 전자는 막는 게 맞다.
     * <p>
     * ⚠️ 되돌리려면 prod {@code .env}의 {@code TRADING_RISK_RISKOFF_FORCE_EXIT_ENABLED}를 true로 두고 컨테이너만
     * 재생성하면 된다(이미지 불변). 코드 기본값은 종전 동작(true) 그대로라 테스트·로컬은 안 바뀐다.
     */
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.risk.riskoff-force-exit-enabled:true}")
    private boolean riskOffForceExitEnabled = true;

    /** 테스트용 — 리스크오프 강제청산 on/off. */
    void configureRiskOffForceExit(boolean enabled) {
        this.riskOffForceExitEnabled = enabled;
    }

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
                } else if (riskOffForceExitEnabled && posRiskOff.off() && !isInverse(pos.getStockCode())) {
                    // 인버스는 급락(리스크오프)이 기회 → 강제청산 면제(손절·시간청산은 아래에서 적용).
                    // riskOffForceExitEnabled=false(prod)면 전 종목이 여기를 건너뛰고 아래 정상 청산 분기로 간다
                    // — 서킷은 신규진입 차단만 하고, 정리는 손절(-12%)·트레일·만기가 맡는다. 필드 주석 참조.
                    reason = "리스크오프(" + posRiskOff.reason() + ")";
                } else if (inversePos) {
                    // 인버스 전용: 약세 명제 소멸 시 청산, 지속 시 시간 무관 보유. 스윙보다 먼저(인버스는 다일 감쇠 → 무조건 당일 청산).
                    reason = inverseExitReason(pos, sessionEnded, heldMin);
                } else if (isMultiday(pos.getStrategy())) {
                    // ②-a 멀티데이 트레일(전략 P) — 스윙보다 먼저 판정(스윙 집합과 겹쳐도 멀티데이가 이긴다).
                    reason = multidayExitReason(buyPrice, price, pos.getPeakPrice(),
                            tradingDaysHeld(pos.getOrderDate(), today), sessionEnded,
                            multidayArmPct, multidayDropPct, multidayMaxHoldDays);
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
     * 멀티데이 트레일 청산 판정(순수, 2026-09-03) — 보유하면 null, 청산이면 사유.
     *
     * <p>규칙: <b>수익률이 한 번이라도 +{@code armPct}에 도달</b>(= {@code peak ≥ 매수가×(1+arm/100)})한 뒤
     * <b>고점 대비 −{@code dropPct}</b>면 매도. 미발동이면 {@code maxHoldDays} <b>거래일</b> 백스톱(그날 장 마감에 청산).</p>
     *
     * <p>⚠️ 무장 판정에 <b>현재가가 아니라 고점(peak)</b>을 쓰는 게 요점이다 — 장중에 +6%를 찍고 +3%로 밀린
     * 포지션은 이미 무장돼야 한다. 현재가로 보면 그 되돌림을 영영 못 잡는다.</p>
     *
     * <p>⚠️ 백스톱은 <b>그날 장 마감에만</b> 발사한다({@code sessionEnded}). 장중에 발사하면 15거래일째 아침에
     * 팔아버려 그날 종가까지의 경로를 통째로 버리는데, 근거 시뮬(일봉 종가)은 <b>종가 청산</b>을 가정했다 —
     * 시뮬과 실행이 어긋나면 "검증한 적 없는 청산"이 된다(2026-08-18 비-TIME horizon 버그와 같은 유형).</p>
     *
     * @param heldDays 진입일 이후 경과 <b>거래일</b> 수(진입 당일=0)
     */
    static String multidayExitReason(long buyPrice, long price, Long peak, int heldDays, boolean sessionEnded,
                                     double armPct, double dropPct, int maxHoldDays) {
        if (buyPrice <= 0) return null;
        long p = peak == null ? price : Math.max(peak, price);
        boolean armed = armPct <= 0 || p >= buyPrice * (1 + armPct / 100.0);
        if (armed && dropPct > 0 && price <= p * (1 - dropPct / 100.0)) {
            double gain = (price - buyPrice) * 100.0 / buyPrice;
            return String.format("멀티데이트레일 -%.1f%%(고점대비, 무장 +%.1f%%, 수익 %+.1f%%)", dropPct, armPct, gain);
        }
        if (maxHoldDays > 0 && heldDays >= maxHoldDays && sessionEnded) {
            return String.format("멀티데이 만기청산(D+%d 종가)", heldDays);
        }
        return null;
    }

    /**
     * 멀티데이 청산가 시뮬(순수, 2026-09-03) — <b>일봉 종가 경로</b>에 위 {@link #multidayExitReason}을 그대로 적용해
     * "이 규칙으로 팔았다면 얼마였나"를 돌려준다. 게이트 채점 horizon({@code multiday})이 이걸 쓴다.
     *
     * <p>⚠️ <b>판정 함수를 재사용하는 게 요점</b> — 시뮬을 따로 구현하면 라이브 청산과 조용히 갈라지고,
     * 그러면 게이트가 <b>실제로 하지 않는 청산</b>으로 실주문을 열어준다(2026-08-18 비-TIME horizon 버그가 정확히 그것).
     * 여기선 같은 함수를 호출하므로 규칙이 갈라질 수 없다.</p>
     *
     * <p>⚠️ <b>근사</b>: 라이브는 1분마다 현재가를 보지만 이 시뮬은 <b>일봉 종가만</b> 본다 — 장중에 무장·발동했을
     * 케이스를 놓친다(그래서 시뮬이 라이브보다 늦게 팔고, 대체로 낙관도 비관도 아닌 방향으로 어긋난다).
     * {@code sessionEnded=true}로 고정하는 것도 같은 이유다(종가 시점 판정).</p>
     *
     * @param closesByDay D+1부터 순서대로의 종가(비어 있으면 null)
     * @return 청산가. 트레일 미발동이면 마지막 관측일 종가(= 만기 종가)
     * @deprecated 손절·상한가익절이 빠진 <b>구판</b>이다(순수 시뮬 테스트·호환 전용).
     *             프로덕션 채점 경로는 반드시 {@link #simulateMultidayExitPrice(long, java.util.List, long, double, double, int, double, double)}
     *             를 쓸 것 — 손절을 빼고 채점하면 <b>라이브가 실제로 내는 손실을 못 보는 net</b>이 된다.
     */
    @Deprecated
    static Long simulateMultidayExitPrice(long buyPrice, java.util.List<Long> closesByDay,
                                          double armPct, double dropPct, int maxHoldDays) {
        if (closesByDay == null) return null;
        java.util.List<DayBar> bars = new java.util.ArrayList<>(closesByDay.size());
        for (int i = 0; i < closesByDay.size(); i++) {
            Long c = closesByDay.get(i);
            bars.add(c == null ? null : DayBar.ofClose(i + 1, c));
        }
        return simulateMultidayExitPrice(buyPrice, bars, 0, armPct, dropPct, maxHoldDays, 0, 0);
    }

    /**
     * 하루치 일봉(거래일 + 시·고·저·종). 시·고·저는 <b>없을 수 있다</b>(구표본) — 그 경우 종가 판정으로 degrade한다.
     *
     * @param day   진입일 이후 경과 <b>거래일</b>(D+N의 N). 마크가 중간에 빠진 경로에서도 만기(D+15) 판정이
     *              어긋나지 않도록 순번이 아니라 실제 거래일을 싣는다.
     * @param close 종가(필수, ≤0이면 그 날은 건너뜀)
     */
    public record DayBar(int day, long close, Long open, Long high, Long low) {
        public static DayBar ofClose(int day, long close) { return new DayBar(day, close, null, null, null); }

        long effHigh() { return high != null && high > 0 ? high : close; }
        long effOpen() { return open != null && open > 0 ? open : 0; }
    }

    /** 시뮬 청산 결과 — 유니버스 벤치마크를 같은 보유기간으로 맞추려면 <b>청산 거래일</b>도 필요하다. */
    public record MultidayExit(long price, int heldDays, boolean triggered) { }

    /**
     * 멀티데이 청산가 시뮬 — <b>라이브 청산 규칙 전체</b>(상한가익절 &gt; 손절 &gt; 멀티데이 트레일/만기)를
     * 일봉 경로에 적용해 "이 규칙으로 팔았다면 얼마였나"를 돌려준다. 게이트 채점 horizon({@code multiday})이 이걸 쓴다.
     *
     * <p>🔴 <b>손절이 왜 여기 들어와야 하나</b>(2026-09-10, 사용자 지시 "net 측정을 청산/손절 기준에 맞게"):
     * 종전 시뮬은 트레일·만기만 봤는데 라이브는 <b>매 분</b> 손절선(전략별 적응형, 미채택 시 −7%)을 함께 본다.
     * 즉 게이트가 채점하던 net은 <b>라이브가 실제로 확정하는 손실을 포함하지 않은</b> 값이었다 — 깊게 물렸다가
     * 종가에 회복한 경로가 시뮬에선 살아남지만 실매매에선 이미 잘려 있다. 이건 2026-08-18 비-TIME horizon 버그와
     * 정확히 같은 유형("검증한 적 없는 청산으로 실주문을 연다")이다.</p>
     *
     * <p>⚠️ <b>규칙을 복제하지 않는다</b> — 트레일·만기는 {@link #multidayExitReason}, 손절은
     * {@link MarketRiskGuard#stopHit}를 그대로 호출한다. 임계값도 라이브와 <b>같은 프로퍼티</b>에서 온다
     * (손절은 {@code StrategyStopProvider}, 상한가는 {@code trading.limit-up-lock-pct}).</p>
     *
     * <p><b>하루 안의 순서 규약</b>(일봉은 고가·저가의 시간 순서를 주지 않는다): TrailingExitSimulator(2026-08-28)와
     * 같은 <b>"저가 먼저"</b> 규약을 쓴다 — ① 저가로 트레일·손절을 먼저 판정하고 ② 그 다음 고가로 상한가·peak를
     * 갱신한다. 반대로 하면 "고가가 먼저 왔다"고 가정하는 셈이라 낙관 편향이 된다.</p>
     *
     * <p>⚠️ 저가 구간에서 <b>트레일을 손절보다 먼저</b> 보는 것은 우선순위 역전이 아니라 <b>시간 순서</b>다 —
     * 무장 상태의 트레일선(≥ 매수×1.029)은 손절선(매수×0.93)보다 항상 위라, 하락 중이면 트레일선을 먼저 통과한다.
     * 둘 다 갭으로 건너뛴 날은 어차피 시가 체결이라 같은 값이 나온다.</p>
     *
     * <p>⚠️ <b>갭은 트리거 가격이 아니라 시가 체결</b>이다(하락 갭에서 손절가에 팔린 것처럼 계산하면 하락장 손실이
     * 조직적으로 과소평가된다 — 같은 이유로 {@code TrailingExitSimulator}도 이 규칙을 쓴다).</p>
     *
     * <p>⚠️ <b>남은 근사</b> ① 고·저가 미수집 구표본은 종가로 degrade(=손절 히트 과소) ② 같은 날 고가로 무장한 뒤
     * 그날 저가에서 발사되는 경로는 놓친다(저가-먼저 규약의 대가) ③ <b>서킷(리스크오프) 강제청산은 미반영</b> —
     * 그날의 지수 경로가 필요해 일봉 마크만으론 재구성이 안 된다(실제로는 폭락일에 강제청산되므로 시뮬이 그만큼 낙관).</p>
     *
     * @param bars       D+1부터 순서대로의 일봉(비어 있으면 null 반환)
     * @param entryClose 진입일(D0) 종가 — 상한가익절의 전일종가 기준. ≤0이면 매수가로 degrade
     * @param stopPct    손절선(%). 0이면 손절 미적용(구판 동작)
     * @param limitUpPct 상한가익절 문턱(당일 등락률 %). 0이면 미적용
     * @return 청산가. 어떤 트리거도 안 걸리면 마지막 관측일 종가(= 만기 종가)
     */
    static Long simulateMultidayExitPrice(long buyPrice, java.util.List<DayBar> bars, long entryClose,
                                          double armPct, double dropPct, int maxHoldDays,
                                          double stopPct, double limitUpPct) {
        MultidayExit e = simulateMultidayExit(buyPrice, bars, entryClose, armPct, dropPct, maxHoldDays,
                stopPct, limitUpPct);
        return e == null ? null : e.price();
    }

    /** 위와 같은 시뮬이되 <b>청산 거래일</b>까지 돌려준다(멀티데이 분석의 유니버스 반사실이 보유기간을 맞추는 데 필요). */
    static MultidayExit simulateMultidayExit(long buyPrice, java.util.List<DayBar> bars, long entryClose,
                                             double armPct, double dropPct, int maxHoldDays,
                                             double stopPct, double limitUpPct) {
        if (buyPrice <= 0 || bars == null || bars.isEmpty()) return null;
        long peak = buyPrice;
        long prevClose = entryClose > 0 ? entryClose : buyPrice;
        MultidayExit last = null;
        for (int i = 0; i < bars.size(); i++) {
            DayBar b = bars.get(i);
            if (b == null || b.close() <= 0) continue;
            int heldDays = b.day() > 0 ? b.day() : i + 1;
            if (maxHoldDays > 0 && heldDays > maxHoldDays) break;
            long close = b.close();
            long open = b.effOpen();
            // ⚠️ 장중 트리거는 <b>실제 고·저가가 있을 때만</b> 판정한다. 종가로 대체해 판정하면 트리거 레벨(=종가보다
            //    위)로 체결가를 잡게 돼 오히려 낙관이 된다 — 구표본(고·저가 없음)은 종전대로 종가 판정만 받는다.
            boolean hasLow = b.low() != null && b.low() > 0;
            boolean hasHigh = b.high() != null && b.high() > 0;
            last = new MultidayExit(close, heldDays, false);

            // ① 저가 구간 — 트레일(무장분) → 손절. 가격 레벨 순서가 곧 시간 순서다(위 주석 참조).
            boolean armed = armPct <= 0 || peak >= buyPrice * (1 + armPct / 100.0);
            if (hasLow) {
                long low = b.low();
                if (armed && dropPct > 0) {
                    long level = Math.round(peak * (1 - dropPct / 100.0));
                    if (low <= level) return new MultidayExit(fillOnFall(open, level), heldDays, true);
                }
                if (MarketRiskGuard.stopHit(buyPrice, low, stopPct)) {
                    return new MultidayExit(
                            fillOnFall(open, Math.round(buyPrice * (1 - stopPct / 100.0))), heldDays, true);
                }
            }
            // ② 고가 구간 — 상한가익절(전일 종가 대비 당일 등락률 ≥ 문턱). 라이브와 같이 이익 구간에서만 발사.
            if (hasHigh && limitUpPct > 0 && prevClose > 0) {
                long level = Math.round(prevClose * (1 + limitUpPct / 100.0));
                if (b.high() >= level && level > buyPrice) {
                    return new MultidayExit(open > 0 ? Math.max(open, level) : level, heldDays, true);
                }
            }
            // ③ 종가 판정 — 손절이 먼저(라이브 우선순위), 그 다음 라이브와 같은 트레일/만기 판정 함수.
            //    peak 는 그날 고가로 갱신한 뒤 판정한다(라이브는 장중 현재가로 peak 를 갱신하므로).
            peak = Math.max(peak, b.effHigh());
            if (MarketRiskGuard.stopHit(buyPrice, close, stopPct)
                    || multidayExitReason(buyPrice, close, peak, heldDays, true, armPct, dropPct, maxHoldDays) != null) {
                return new MultidayExit(close, heldDays, true);
            }
            prevClose = close;
        }
        return last;
    }

    /**
     * 하락 트리거 체결가 — 갭으로 레벨 아래에서 출발했으면 시가 체결(트리거가에 팔린 척하지 않는다).
     *
     * <p>⚠️ 트리거 레벨은 { Math.round}로 원 단위를 맞춘다(floor는 부동소수 오차 때문에 금액대에 따라
     * 1원씩 들쭉날쭉해진다 — 실측: 10,000×0.93=9,300.0인데 1,000×0.93=929.9999…). 체결가 근사이지
     * 트리거 조건이 아니므로 반올림이 맞다.</p>
     */
    private static long fillOnFall(long open, long level) {
        return open > 0 ? Math.min(open, level) : level;
    }

    /**
     * 진입일 이후 경과 <b>거래일</b> 수. {@code daily_price}(하루 1회 갱신)의 실제 거래일을 세고,
     * 미주입·조회실패면 <b>영업일(월~금) 근사</b>로 degrade한다(공휴일을 과대 계산 → 백스톱이 조금 빨라지는 방향).
     */
    private int tradingDaysHeld(String orderDate, String today) {
        if (orderDate == null || today == null) return 0;
        if (jdbcTemplate != null) {
            try {
                Integer n = jdbcTemplate.queryForObject(
                        "select count(distinct business_date) from daily_price where business_date > ? and business_date <= ?",
                        Integer.class, orderDate, today);
                if (n != null) return n;
            } catch (Exception ignored) {
                // degrade — 아래 영업일 근사
            }
        }
        try {
            java.time.LocalDate a = java.time.LocalDate.parse(orderDate, YYYYMMDD);
            java.time.LocalDate b = java.time.LocalDate.parse(today, YYYYMMDD);
            int n = 0;
            for (java.time.LocalDate d = a.plusDays(1); !d.isAfter(b); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() <= 5) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
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
            // 저점 대비 레벨 회복 요건(2026-08-18 실측 대응): mom30 블립만으로 팔면 하락이 계속되는 날에 왕복만 반복한다.
            // 8/18 KOSDAQ −3.52%(장중저점 −4.23%)일에 청산 6건의 사유가 전부 mom30 +0.31~+0.48%였고, 251340이
            // 2,335→2,400으로 오르는 내내 7왕복 −4,521원(비용만 지불). 임계의 낙폭 비례 상향(rebound-day-scale)은
            // '지금 등락률'(청산 시점 −2%대)을 기준으로 삼아 그 시점엔 기저 0.30%에 머물러 작동하지 않았다.
            // → 모멘텀 반전과 함께 '지수가 장중 저점에서 실제로 올라왔는지'를 요구한다. 서킷 재개 판정(저점 대비
            //    rebound-pct%p 반등)과 같은 사상이며, 하락 지속 구간에서 승자 보유를 보존한다.
            Double dayLow = marketRegimeService.dayLowChangeOf(market);
            if (inverseReboundExit(mom, thr, chg, dayLow, inverseExitReboundFromLowPct)) {
                return String.format("지수반등(mom30 %+.2f%% ≥ %.2f%%, 저점대비 %s)", mom, thr,
                        (chg != null && dayLow != null) ? String.format("%+.2f%%p", chg - dayLow) : "미상");
            }
        }
        return null;   // 지수 약세 지속 — 시간 무관 보유
    }

    /**
     * 인버스 반등 청산 판정(순수) — <b>모멘텀 반전 AND 저점 대비 레벨 회복</b>을 함께 요구.
     *
     * @param minRecoveryPct 요구 회복폭(%p). 0 이하면 종전대로 모멘텀만으로 판정.
     *                       지수 등락률/장중저점이 미상이거나 {@code dayLow > chg}(저점이 현재보다 높은 데이터 부정합,
     *                       실데이터에선 불가능 — 저점 추적이 현재값을 먼저 반영한다)면 판정 불가 → 모멘텀만(degrade open).
     */
    static boolean inverseReboundExit(Double mom, double thr, Double chg, Double dayLow, double minRecoveryPct) {
        if (mom == null || mom < thr) return false;
        if (minRecoveryPct <= 0 || chg == null || dayLow == null || dayLow > chg) return true;
        return (chg - dayLow) >= minRecoveryPct;
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

    /** 마감 강제청산 사유인지(장마감/스윙 익일종가) — marketable 지정가 대상. 손절·서킷·적응형은 제외. */
    private static boolean isSessionCloseReason(String reason) {
        return reason != null && (reason.startsWith("장마감") || reason.startsWith("스윙청산"));
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
        // [마감청산 공격성 ②] 마감 강제청산(장마감/스윙 익일종가)은 현재가 −pct% marketable 지정가로 접수해 즉시 체결
        // (마감 미체결→취소→오버나잇 방지). 그 외(손절·서킷·적응형)는 현재가 지정가 유지. OrderService가 tick 스냅(매도 내림).
        long limitPrice = price;
        if (sessionCloseAggressivePct > 0 && isSessionCloseReason(reason)) {
            limitPrice = Math.max(1, (long) Math.floor(price * (1 - sessionCloseAggressivePct / 100.0)));
        }
        OrderService.OrderResult r = orderService.submit(new OrderService.OrderCommand(
                pos.getStrategy(), pos.getStockCode(), OrderSide.SELL,
                qty, limitPrice, 0, "SELL:" + pos.getId(), pos.getSector(), null, null,   // 매도는 보유시간·시장 무관(서킷 면제)
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
