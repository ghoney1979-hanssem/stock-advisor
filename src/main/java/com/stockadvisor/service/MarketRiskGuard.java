package com.stockadvisor.service;

import com.stockadvisor.config.properties.RiskProperties;
import com.stockadvisor.domain.MarketTrend;
import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.VolatilityLevel;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국면연동 포트폴리오 리스크 가드 (레이어 3 — 하락장 방어).
 *
 * <p>두 축: <b>① 총노출 상한</b> — 약세·고변동 국면일수록 순자산 대비 가용 비중을 낮춰 신규진입을 제한.
 * <b>② 서킷브레이커</b> — 지수 장중 급락 시 신규진입을 즉시 중단(+ {@link PositionExitService}가 청산 가속).</p>
 *
 * <p>전략 성과게이트(개별 전략의 "이 전략 실전 자격")와 직교한다 — 이건 시장 전체 리스크 기준 포트폴리오 통제.
 * 국면/지수 산출이 실패하면(데이터 부족) <b>추가 제약 없이 통과</b>(degrade open) — 데이터 실패로 매매를 막지 않는다.</p>
 */
@Service
public class MarketRiskGuard {

    private static final Logger log = LoggerFactory.getLogger(MarketRiskGuard.class);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MarketRegimeService marketRegimeService;
    private final KisApiClient kisApiClient;
    private final OrderRepository orderRepository;
    private final RiskProperties props;

    /** 약세장 전 전략 신규진입 차단(코드 기본 off — 종전 동작). prod에서 켬. */
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.trading.risk.bear-block-enabled:false}")
    private boolean bearBlockEnabled = false;

    /** 테스트용. */
    void setBearBlockEnabled(boolean v) { this.bearBlockEnabled = v; }

    // 시장폭(breadth) 리스크오프 — 지수 서킷의 사각지대(대형주가 버틴 광범위 투매) 보완. 필드주입(생성자 무churn),
    // 미주입(테스트)이면 breadth 판정 자체를 건너뜀(degrade open).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketBreadthService breadthService;
    void setBreadthService(MarketBreadthService s) { this.breadthService = s; }   // 테스트용

    /** breadth 스냅샷 최대 허용 나이(분) — 12분 주기 전수 스캔 기준 3사이클. 이보다 오래되면(마감 후·전일분) 판정 안 함. */
    private static final long BREADTH_MAX_AGE_MIN = 40;

    // 지수별 서킷 상태기계(장중 저점 대비 반등으로 재개). 일별 리셋. 메모리 상태(재기동 시 초기화 — 1분 내 재평가).
    private static final class Circuit {
        boolean halt = false;
        double extreme = 0.0;   // !halt: 고점(0부터 추적) / halt: 저점 추적
    }
    private final Map<String, Circuit> circuits = new HashMap<>();
    private String lastResetDay = null;

    public MarketRiskGuard(MarketRegimeService marketRegimeService, KisApiClient kisApiClient,
                           OrderRepository orderRepository, RiskProperties props) {
        this.marketRegimeService = marketRegimeService;
        this.kisApiClient = kisApiClient;
        this.orderRepository = orderRepository;
        this.props = props;
    }

    public record RiskDecision(boolean allowed, String reason) {
        static RiskDecision allow() { return new RiskDecision(true, "OK"); }
        static RiskDecision deny(String r) { return new RiskDecision(false, r); }
    }

    /** 서킷브레이커 신호. off=true면 신규진입 중단·청산 가속. */
    public record RiskOff(boolean off, String reason) {}

    /** 진입 허용 판정(overall 서킷) — 시장 미지정. */
    public RiskDecision allowEntry(long orderKrw, long netAssetsKrw, String sector) {
        return allowEntry(orderKrw, netAssetsKrw, sector, null);
    }

    /** 진입 허용 판정 — ① 서킷브레이커(<b>해당 시장</b>) ② 섹터 집중 한도 ③ 총노출 상한. */
    public RiskDecision allowEntry(long orderKrw, long netAssetsKrw, String sector, String market) {
        return allowEntry(orderKrw, netAssetsKrw, sector, market, false);
    }

    /**
     * @param gateStrictPass 성과게이트가 <b>엄격 버킷</b>(국면·흐름·시장폭 표본 충족)으로 통과시킨 진입이면 true —
     *                       bear-block을 면제한다. "약세장이지만 이 상태에서 검증된 전략"은 여는 게 상태조건부 선택의 취지이고,
     *                       bear-block이 막으려는 건 <b>검증 없이</b>(fallback·부트스트랩) 약세장에 들어가는 진입이다.
     *                       실측(6/25~8/28): BEAR×흐름↑ 도 −0.69%(n=755)라 지금은 면제 대상이 거의 없지만,
     *                       BEAR×흐름↑×폭 버킷이 표본 20으로 양수가 되면 그 순간 자동으로 열려야 한다.
     */
    public RiskDecision allowEntry(long orderKrw, long netAssetsKrw, String sector, String market, boolean gateStrictPass) {
        if (!props.enabled()) return RiskDecision.allow();

        // ⓪ 약세장 전 전략 신규진입 차단(2026-08-29, 사용자 결정). 섀도우 6/25~8/28 (전략,시장,국면) 셀 n≥30에서
        //    BEAR 셀은 L(8/25 클러스터) 하나 빼고 전부 −0.9~−2.8% — "약세장엔 여는 전략이 없다"가 가장 강한 단일 구조.
        //    노출상한(BEAR 30~60%)을 0으로 두는 것과 같되, 시장별 라벨(KOSDAQ 종목은 KOSDAQ 국면)로 판정한다.
        //    인버스는 OrderService가 이 가드를 건너뛰므로 무관(하락장이 곧 기회). 국면 미상은 통과(degrade open).
        //    엄격 버킷 통과분(gateStrictPass)은 면제 — 상태조건부 검증이 있는 진입은 막지 않는다(사용자 지적 반영).
        if (bearBlockEnabled && !gateStrictPass) {
            MarketTrend t = null;
            try {
                t = (market != null && !market.isBlank()) ? marketRegimeService.trendOf(market) : marketRegimeService.overallTrend();
            } catch (Exception ignored) { /* 국면 조회 실패 → 판정 생략 */ }
            if (t == MarketTrend.BEAR) {
                return RiskDecision.deny("약세장 신규진입 차단(bear-block): " + (market == null ? "전체" : market) + " 국면 BEAR");
            }
        }

        RiskOff ro = isRiskOff(market);   // 시장별 — 코스닥 폭락이 코스피 진입을 막지 않음
        if (ro.off()) {
            return RiskDecision.deny("서킷브레이커: " + ro.reason());
        }
        // 시장폭 리스크오프 — 지수는 멀쩡한데 시장 전반이 무너진 날(좁은 지수 방어) 신규진입 차단. 청산은 건드리지 않음.
        RiskOff bro = breadthRiskOff(market);
        if (bro != null && bro.off()) {
            return RiskDecision.deny("시장폭 리스크오프: " + bro.reason());
        }
        // 섹터 집중 한도 — 같은 업종 과다 보유 차단(동반급락 분산)
        if (props.maxPositionsPerSector() > 0 && sector != null && !sector.isBlank()) {
            long inSector = orderRepository.countOpenPositionsBySector(sector);
            if (inSector >= props.maxPositionsPerSector()) {
                return RiskDecision.deny(String.format(
                        "섹터 집중 한도: '%s' 보유 %d ≥ %d", sector, inSector, props.maxPositionsPerSector()));
            }
        }
        if (netAssetsKrw <= 0) return RiskDecision.allow();   // 순자산 미상 → 노출 한도 판정 불가, 통과(다른 게이트가 잡음)

        double capPct = exposureCapPct();
        long capKrw = Math.round(netAssetsKrw * capPct / 100.0);
        long openKrw = openExposureKrw();
        if (openKrw + orderKrw > capKrw) {
            return RiskDecision.deny(String.format(
                    "총노출 한도 초과: (보유 %,d + 주문 %,d)원 > 한도 %,d원 (순자산×%.0f%%, %s)",
                    openKrw, orderKrw, capKrw, capPct, regimeLabel()));
        }
        return RiskDecision.allow();
    }

    /** 개별 포지션 손절 하한(%). 0이면 비활성. (재난 방지용 바닥 — 데이터 기반 청산과 별개) */
    public double catastrophicStopPct() {
        return props.enabled() ? props.catastrophicStopPct() : 0.0;
    }

    /** 매수가 대비 현재가가 고정 손절 하한 이하인가 — true면 즉시 청산. */
    public boolean catastrophicStopHit(long buyPrice, long currentPrice) {
        return catastrophicStopHit(buyPrice, currentPrice, catastrophicStopPct());
    }

    /** 매수가 대비 현재가가 주어진 손절 pct 이하인가 (전략별 적응형 손절용). */
    public boolean catastrophicStopHit(long buyPrice, long currentPrice, double pct) {
        if (pct <= 0 || buyPrice <= 0 || currentPrice <= 0) return false;
        return currentPrice <= buyPrice * (1.0 - pct / 100.0);
    }

    /** 현재 국면 기준 총노출 상한(순자산 대비 %). 국면 미산출이면 제약 없음(100). 개장 창이면 개장 상한과 min 결합. */
    public double exposureCapPct() {
        return exposureCapPct(ZonedDateTime.now(SEOUL).toLocalTime());
    }

    /**
     * 총노출 상한(%) — 국면 상한과 <b>개장 창 상한</b>의 min.
     *
     * <p>개장 창 상한은 <b>"갭 구간 상관 노출 축소"</b>용이다(2026-08-14 실측: LIVE 15건이 전부 09:01~09:24에 진입,
     * 09:07에 노출 5,012,064원 = 순자산 47%로 예산 도달, 갭업 고점이라 계좌 −1.78%). {@code openingExposureCapPct}=0이면
     * <b>비활성</b>이라 기존 동작과 완전히 동일하다.</p>
     *
     * <p>⚠️ 초기 서술 정정: "예산 소진이 오후까지 이어졌다"는 틀렸다 — 노출은 현재 보유 기준이라 청산 시 회복되며
     * 8/14도 10:42엔 0원이었다. 근거는 시간 분산이지 "오후 예산 확보"가 아니다.</p>
     */
    public double exposureCapPct(java.time.LocalTime now) {
        if (!props.enabled()) return 100;
        MarketTrend trend = marketRegimeService.overallTrend();
        if (trend == null) return 100;   // 데이터 부족 → 제약 없음
        double base = switch (trend) {
            case BULL -> props.bullExposurePct();
            case NEUTRAL -> props.neutralExposurePct();
            case BEAR -> props.bearExposurePct();
        };
        if (anyHighVolatility()) base *= props.highVolExposureMult();
        base = openingCapped(base, now, props.openingExposureCapPct(), props.openingWindowMinutes());
        return Math.min(100, Math.max(0, base));
    }

    /** KRX 정규장 개시(09:00) — 개장 창 기준점. */
    static final java.time.LocalTime MARKET_OPEN = java.time.LocalTime.of(9, 0);

    /**
     * 개장 창 노출 상한 결합(순수) — [09:00, 09:00+windowMinutes) 구간이면 {@code min(base, openingCapPct)}.
     * {@code openingCapPct<=0}(비활성)이거나 창 밖이면 base 그대로.
     */
    static double openingCapped(double base, java.time.LocalTime now, double openingCapPct, int windowMinutes) {
        if (openingCapPct <= 0 || now == null) return base;
        java.time.LocalTime end = MARKET_OPEN.plusMinutes(windowMinutes);
        if (now.isBefore(MARKET_OPEN) || !now.isBefore(end)) return base;
        return Math.min(base, openingCapPct);
    }

    /** 미청산 매수 포지션 총액(원) — 주문 요청금액 합산. */
    public long openExposureKrw() {
        long sum = 0;
        for (Order o : orderRepository.findOpenBuyPositions()) {
            sum += o.getRequestedKrw();
        }
        return sum;
    }

    /**
     * 서킷브레이커 — 저점 대비 반등 기반 상태기계.
     * <p>발동: 지수가 장중 고점 대비 {@code crashHaltPct}%p 이상 급락 + 절대 -crashHaltPct% 이하.
     * 재개(둘 중 하나): ① <b>장중 저점 대비 {@code reboundPct}%p 반등</b>(아직 -crashHaltPct% 이하여도 조기 재개, 예 -6%→-4%)
     * OR ② <b>지수가 -crashHaltPct%(크래시 레벨) 위로 회복</b>(레벨 기반). 재개 후 새 고점 기준 재무장 — 거기서 다시
     * crashHaltPct%p 급락해야 재발동(경계 깜빡임·데드캣 방지).</p>
     * 코스피/코스닥 중 하나라도 halt면 off=true(overall).
     */
    public synchronized RiskOff isRiskOff() {
        return isRiskOff(null);
    }

    /**
     * 시장별 서킷 판정 — {@code market}="KOSPI"/"KOSDAQ"면 <b>해당 시장 지수 서킷만</b> 반환(코스닥 폭락이 코스피 진입/청산을
     * 막지 않도록). null(overall)이면 하나라도 halt면 off. ⚠️ 상태기계는 매 호출마다 두 지수 모두 갱신(멱등)해 어느 시장을
     * 조회하든 둘 다 최신 상태를 유지한다. 인버스 코드는 호출측에서 별도 면제.
     */
    public synchronized RiskOff isRiskOff(String market) {
        if (!props.enabled() || props.crashHaltPct() <= 0) {
            return new RiskOff(false, null);
        }
        String today = ZonedDateTime.now(SEOUL).format(YYYYMMDD);
        if (!today.equals(lastResetDay)) {   // 일별 리셋(전일 저점/상태 초기화)
            circuits.clear();
            lastResetDay = today;
        }
        RiskOff kospi = stepCircuit("0001", "코스피");    // 상태기계는 매 틱 둘 다 갱신(멱등)
        RiskOff kosdaq = stepCircuit("1001", "코스닥");
        if ("KOSPI".equals(market)) return kospi != null ? kospi : new RiskOff(false, null);
        if ("KOSDAQ".equals(market)) return kosdaq != null ? kosdaq : new RiskOff(false, null);
        // overall(시장 미상/null): 하나라도 halt면 off
        if (kospi != null && kospi.off()) return kospi;
        if (kosdaq != null && kosdaq.off()) return kosdaq;
        return new RiskOff(false, null);
    }

    /**
     * 시장폭(breadth) 리스크오프 — 해당 시장 상승비율 &lt; {@code breadthRiskoffAdvPct} <b>AND</b>
     * 중앙 등락률 ≤ -{@code breadthRiskoffMedianPct}면 신규진입 차단(<b>강제청산 없음</b> — 그건 지수 서킷의 몫).
     * 지수(시총가중 프록시)가 대형주 방어로 멀쩡해 보여도 시장 전반이 무너진 날을 잡는다(2026-07-13 코스닥 실측).
     * degrade open: breadth 미주입/미집계/스냅샷 40분 초과(마감 후·전일분)면 판정 안 함.
     */
    public RiskOff breadthRiskOff(String market) {
        if (!props.enabled() || props.breadthRiskoffAdvPct() <= 0 || breadthService == null) {
            return new RiskOff(false, null);
        }
        String key = (market == null || market.isBlank()) ? "OVERALL" : market;
        if ("INVERSE".equals(key)) return new RiskOff(false, null);   // 인버스는 하락이 기회 — 호출측 면제와 정합
        if (!breadthService.isFresh(BREADTH_MAX_AGE_MIN)) return new RiskOff(false, null);
        Double adv = breadthService.breadthPct(key);
        Double median = breadthService.medianChangePct(key);
        if (adv == null || median == null) return new RiskOff(false, null);
        if (adv < props.breadthRiskoffAdvPct() && median <= -props.breadthRiskoffMedianPct()) {
            return new RiskOff(true, String.format("%s 상승비율 %.1f%% < %.0f%% & 중앙 %.2f%% ≤ -%.1f%% (광범위 투매)",
                    key, adv, props.breadthRiskoffAdvPct(), median, props.breadthRiskoffMedianPct()));
        }
        return new RiskOff(false, null);
    }

    /** 지수 1개의 서킷 상태 갱신 + 판정. 조회 실패 시 null(해당 지수 판단 보류). */
    private RiskOff stepCircuit(String indexCode, String name) {
        Double curD = safeIndexChange(indexCode);
        if (curD == null) return null;
        double cur = curD;
        double halt = props.crashHaltPct();
        double rebound = props.reboundPct();
        Circuit c = circuits.computeIfAbsent(indexCode, k -> new Circuit());
        if (!c.halt) {
            c.extreme = Math.max(c.extreme, cur);                     // 고점 추적(0부터)
            if (cur <= -halt && (c.extreme - cur) >= halt) {          // 고점서 halt%p↑ 급락 + 절대 -halt 이하
                c.halt = true;
                c.extreme = cur;                                      // 저점 추적 시작
            }
        } else {
            c.extreme = Math.min(c.extreme, cur);                     // 저점 추적
            // 재개: ① 저점 대비 rebound%p 반등(아직 -halt 이하여도 조기 재개) OR ② 지수가 -halt(크래시 레벨) 위로 회복(레벨 기반).
            boolean reboundResume = (cur - c.extreme) >= rebound;
            boolean levelResume = cur > -halt;
            if (reboundResume || levelResume) {
                c.halt = false;
                c.extreme = cur;                                      // 새 고점 기준 리셋(재무장 — 재발동은 peak대비 halt%p 급락)
            }
        }
        if (c.halt) {
            return new RiskOff(true, String.format("%s %.2f%% (장중저점 %.2f%%, 저점대비 반등 %.1f%%p 미만 & -%.1f%% 이하)",
                    name, cur, c.extreme, rebound, halt));
        }
        return new RiskOff(false, null);
    }

    /** 가시화용 현재 리스크 상태 — 서킷·시장폭 리스크오프를 시장별로 노출(코스피/코스닥 독립). */
    public record RiskStatus(boolean enabled, String overallTrend, double exposureCapPct,
                             long openExposureKrw, boolean riskOff, String riskOffReason,
                             boolean kospiRiskOff, String kospiReason,
                             boolean kosdaqRiskOff, String kosdaqReason,
                             boolean kospiBreadthOff, String kospiBreadthReason,
                             boolean kosdaqBreadthOff, String kosdaqBreadthReason) {}

    public RiskStatus status() {
        MarketTrend t = marketRegimeService.overallTrend();
        RiskOff kospi = isRiskOff("KOSPI");    // 두 지수 상태기계 갱신 + 코스피 판정
        RiskOff kosdaq = isRiskOff("KOSDAQ");  // 코스닥 판정
        RiskOff kospiB = breadthRiskOff("KOSPI");
        RiskOff kosdaqB = breadthRiskOff("KOSDAQ");
        boolean any = kospi.off() || kosdaq.off();
        String anyReason = kospi.off() ? kospi.reason() : (kosdaq.off() ? kosdaq.reason() : null);
        return new RiskStatus(props.enabled(), t == null ? null : t.name(),
                exposureCapPct(), openExposureKrw(), any, anyReason,
                kospi.off(), kospi.reason(), kosdaq.off(), kosdaq.reason(),
                kospiB.off(), kospiB.reason(), kosdaqB.off(), kosdaqB.reason());
    }

    private boolean anyHighVolatility() {
        return marketRegimeService.all().stream()
                .anyMatch(r -> r.available() && r.volatility() == VolatilityLevel.HIGH);
    }

    private String regimeLabel() {
        MarketTrend t = marketRegimeService.overallTrend();
        String base = t == null ? "국면미상" : t.korean() + (anyHighVolatility() ? "·고변동" : "");
        // 개장 창 상한이 실제로 물린 경우에만 표기 — "왜 아침에만 막혔나"가 로그로 바로 보이게
        java.time.LocalTime now = ZonedDateTime.now(SEOUL).toLocalTime();
        if (props.openingExposureCapPct() > 0
                && openingCapped(100, now, props.openingExposureCapPct(), props.openingWindowMinutes()) < 100) {
            base += "·개장창" + props.openingWindowMinutes() + "분";
        }
        return base;
    }

    private Double safeIndexChange(String indexCode) {
        try {
            return kisApiClient.fetchIndexChangeRate(indexCode);
        } catch (Exception ex) {
            log.debug("지수 등락률 조회 실패 [{}]: {}", indexCode, ex.getMessage());
            return null;
        }
    }
}
