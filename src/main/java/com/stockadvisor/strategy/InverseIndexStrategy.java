package com.stockadvisor.strategy;

import com.stockadvisor.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전략 I — 인버스 = 지수 약세 트리거 (섀도우, <b>거래량 무관</b>).
 *
 * <p>기존 인버스 포착(B/E가 인버스 ETF <b>자체의 거래량 급증</b>을 봄)은 인버스 볼륨이 잘 안 튀어 계속 실패했다.
 * 이 전략은 인버스 ETF의 볼륨이 아니라 <b>대응 지수(코스피/코스닥)의 당일 약세</b>를 트리거로, 시장이 빠지면
 * 인버스가 오른다는 직접 관계를 이용해 롱 진입한다({@code requiresVolumeSpike()=false}로 볼륨 게이트 우회).</p>
 *
 * <p>대상은 인버스 코드 한정({@code ctx.inverse()}) — 전 종목 풀평가 폭증 없음. 지수 등락률은 종목당 1회
 * KIS 조회(60s 캐시)라 인버스 2종목만 호출. 지수가 {@code minDropPct}%p 이상 하락일 때만 진입.</p>
 */
@Component
public class InverseIndexStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(InverseIndexStrategy.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final KisApiClient kisApiClient;
    private final boolean enabled;
    private final double minDropPct;              // 지수가 이 %p 이상 하락(당일)이면 약세로 판정
    private final double maxDropPct;              // 낙폭 상한(2026-07-20) — 지수 당일 -이값% 초과 진행 후엔 진입 보류. 0=비활성
    private final Map<String, String> codeToIndex;  // 인버스코드 → 지수코드(0001 코스피/1001 코스닥)

    // 지수 흐름 확인 필터(2026-07-14): 당일 -1%여도 "갭다운 후 회복 중"이면 인버스는 고점매수(반등하는 칼날의 거울상).
    // 2026-07-16 강화: mom10 단독 판정은 "30분 추세 회복(+) 중 순간 10분 음전"을 통과시켜 휩쏘 재진입(고점매수→반등에 손절)
    // 반복 — mom10·mom30 둘 다 0 이하(순간도, 지속도 하락/보합)일 때만 진입. 청산(mom30 기준)과 대칭.
    @Value("${stockadvisor.signal.inverse-index-require-falling:true}")
    private boolean requireFalling = true;
    // 반등일 fade 확대(2026-07-22, 사용자 결정): REBOUND_DAY 성립일(당일 고점 ≥ surge% AND 기저 비강세)엔
    // 진입 상한을 -minDrop(-1%) → +fadeMax(+2%)로 확대 — "갭업이 무너지는 날"의 fade 구간(+2%~-1%) 포착.
    // 평상일 상한은 불변(강세장 눌림 휩쏘 회피). 0=비활성. mom10/30 동시하락·하한(-4%)·쿨다운은 동일 적용.
    @Value("${stockadvisor.signal.inverse-rebound-fade-max-pct:2.0}")
    private double reboundFadeMaxPct = 2.0;
    @Value("${stockadvisor.signal.rebound-day-min-surge-pct:2.0}")
    private double reboundSurgePct = 2.0;   // 반등일 판정 임계 — 순추세 가드와 동일 knob 공유
    // fade 확인 요건(2026-08-20, 11차 재진입 −3,711원 계기): 반등일 확대창은 "갭업이 무너지는 날"을 노린 것인데,
    // 지수가 고점 근처에 머무는 동안에도 창이 열려 있어 진입 허용구간(−0.5%~+2%)이 청산 트리거(지수 > −0.5%)와
    // 통째로 겹쳤다 — 진입 즉시 청산되는 왕복비용 루프. 창을 열되 "고점 대비 fadeConfirmPct%p 이상 실제로 밀렸을 것"을
    // 함께 요구한다(8/20 실측: 고점 +2.4% vs 진입 시 +1.84% = 0.56%p차 → 차단됐을 후보). 0=비활성(종전 동작).
    @Value("${stockadvisor.signal.inverse-fade-confirm-pct:1.0}")
    private double fadeConfirmPct = 1.0;
    void setReboundFadeMaxPct(double v) { this.reboundFadeMaxPct = v; }   // 테스트용
    void setFadeConfirmPct(double v) { this.fadeConfirmPct = v; }         // 테스트용
    // 필드주입(생성자 무churn) — 미주입/흐름 미가용(장초 분봉 부족)이면 판정 생략(degrade open, 기존 동작).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stockadvisor.service.MarketRegimeService regimeService;
    void setRegimeService(com.stockadvisor.service.MarketRegimeService s) { this.regimeService = s; }   // 테스트용
    void setRequireFalling(boolean b) { this.requireFalling = b; }                                       // 테스트용

    public InverseIndexStrategy(KisApiClient kisApiClient,
                                @Value("${stockadvisor.signal.inverse-index-enabled:true}") boolean enabled,
                                @Value("${stockadvisor.signal.inverse-index-min-drop:1.0}") double minDropPct,
                                @Value("${stockadvisor.signal.inverse-index-max-drop:4.0}") double maxDropPct,
                                @Value("${stockadvisor.signal.inverse-index-map:114800:0001,251340:1001}") String mapCsv) {
        this.kisApiClient = kisApiClient;
        this.enabled = enabled;
        this.minDropPct = minDropPct;
        this.maxDropPct = maxDropPct;
        this.codeToIndex = parseMap(mapCsv);
    }

    private static Map<String, String> parseMap(String csv) {
        Map<String, String> m = new HashMap<>();
        if (csv == null) return m;
        for (String pair : csv.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) m.put(kv[0].trim(), kv[1].trim());
        }
        return m;
    }

    @Override
    public String name() {
        return "INVERSE_INDEX_I";
    }

    @Override
    public String label() {
        return "인버스 지수약세형 (I)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!enabled) return "DISABLED";
        if (!ctx.inverse()) return "NOT_INVERSE";                 // 인버스 코드에만 적용(전 종목 폭증 방지)
        String idx = codeToIndex.get(ctx.stockCode());
        if (idx == null) return "NO_INDEX_MAP";
        Double chg = safeIndexChange(idx);
        if (chg == null) return "NO_INDEX";                       // 지수 조회 실패
        double dayHigh = trackDayHigh(idx, chg);   // 진입 판정과 같은 소스의 당일 고점(아래 주석 참조)
        double upperPct = -minDropPct;   // 기본 진입 상한(약세 확인선)
        if (reboundFadeMaxPct > 0 && regimeService != null) {
            String mkt = "0001".equals(idx) ? "KOSPI" : ("1001".equals(idx) ? "KOSDAQ" : null);
            if (mkt != null && regimeService.isReboundDay(mkt, reboundSurgePct)) {
                if (fadeConfirmPct <= 0 || dayHigh - chg >= fadeConfirmPct) {
                    upperPct = reboundFadeMaxPct;   // 반등일 + fade 진행 확인 → 확대창(+2%까지)
                } else if (chg > -minDropPct) {
                    // 확대창에 기대야만 통과할 후보(평상일 상한 위)인데 fade가 아직 진행 안 됨 → 보류.
                    // ⚠️ 진짜 약세(chg ≤ -minDrop)는 확대창과 무관하므로 여기서 막지 않는다.
                    return "NOT_FADING";
                }
            }
        }
        if (chg > upperPct) return "INDEX_NOT_WEAK";              // 상한 위(아직 안 꺾임/평상일 비약세)
        // 낙폭 상한(2026-07-20 실측: 지수 -4% 진행 후 진입 4건 전패 -1.0~-1.3% — 인버스가 이미 당일 +5% 오른
        // 고점매수라 되돌림 한 번에 청산됨). 폭락 "초입" 진입만 허용 — C의 max-drop(과폭락 제외)과 같은 사상.
        if (maxDropPct > 0 && chg <= -maxDropPct) return "INDEX_TOO_DEEP";
        // 흐름 확인: 당일 약세여도 최근 모멘텀이 상승(+)이면 회복 중 — 인버스 고점매수 회피(INDEX_RECOVERING).
        if (requireFalling && regimeService != null) {
            String market = "0001".equals(idx) ? "KOSPI" : ("1001".equals(idx) ? "KOSDAQ" : null);
            if (market != null) {
                var flow = regimeService.intradayFlow(market);
                if (flow != null && flow.available()) {
                    Double m10 = flow.mom10Pct();
                    Double m30 = flow.mom30Pct();
                    // 한쪽이라도 상승(+)이면 회복 중 — 순간(10분)·지속(30분) 모두 하락/보합이어야 진입
                    if ((m10 != null && m10 > 0) || (m30 != null && m30 > 0)) {
                        return "INDEX_RECOVERING";   // 지수 반등 중 → 진입 보류(다음 틱 재평가)
                    }
                }
            }
            // 흐름 미가용(장초 분봉 부족/조회 실패) → 기존 동작(당일 약세만으로 진입)
        }
        return null;                                              // 지수 약세 + 아직 하락/보합 중 → 인버스 롱
    }

    // 당일 지수 등락률 고점 추적 — 지수코드별, 날짜 바뀌면 리셋. 관측 시점 기반(스캔 주기 호출로 충분).
    // 판정에 쓰는 chg(= fetchIndexChangeRate, 60s 캐시)를 그대로 누적하므로 추가 KIS 호출 0.
    // MarketRegimeService.dayHighChangeOf도 같은 소스지만 private + 시장(KOSPI/KOSDAQ) 키라, 지수코드 키로
    // 이미 손에 든 값을 누적하는 편이 자족적이다(가시성 확대 불필요). 두 추적기는 같은 값을 보므로 상충 없음.
    private final Map<String, double[]> dayHighChg = new ConcurrentHashMap<>();   // indexCode -> {epochDay, highPct}

    private double trackDayHigh(String indexCode, double chg) {
        long today = LocalDate.now(SEOUL).toEpochDay();
        double[] cur = dayHighChg.compute(indexCode, (k, prev) ->
                (prev == null || (long) prev[0] != today || chg > prev[1]) ? new double[]{today, chg} : prev);
        return cur[1];
    }

    private Double safeIndexChange(String indexCode) {
        try {
            return kisApiClient.fetchIndexChangeRate(indexCode);   // 60s 캐시 — 인버스 2종목만 호출
        } catch (Exception e) {
            log.debug("지수 조회 실패 code={}: {}", indexCode, e.getMessage());
            return null;
        }
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;   // 워치리스트 스캔에서 인버스 코드에만 발동
    }

    @Override
    public boolean requiresVolumeSpike() {
        return false;   // 인버스 ETF 자체 볼륨이 아니라 지수 약세가 트리거 — 볼륨 게이트 우회
    }

    @Override
    public boolean preScreen(String stockCode, com.stockadvisor.service.SignalResult signal) {
        return codeToIndex.containsKey(stockCode);   // 인버스 코드에만 우회(전 종목 폭증 방지)
    }

    @Override
    public boolean alerts() {
        return false;   // v1 섀도우(화이트리스트 미포함 → 실주문 0, perf-gate 검증)
    }
}
