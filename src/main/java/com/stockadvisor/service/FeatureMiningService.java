package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Feature-space 마이닝 리포트 (2026-08-12, 생성적 분석).
 *
 * <p>전략 단위 채점(무엇을 코딩했나)이 아니라, 쌓인 {@link TradeOutcome}을 <b>feature 축으로 bin</b>해
 * "어떤 조건 구간의 진입이 수익이었나"를 스캔한다 — 아직 전략화 안 된 수익 pocket을 surface(사람이 코딩하기 전에
 * 데이터가 먼저 제안). 전 전략 진입을 pool하므로 전략 교차 패턴이 드러난다.</p>
 *
 * <p><b>허수 방지</b>: 각 bucket에 교차거래일 가드(점유율 ≤ maxDaySharePct <b>+ 거래일 ≥ 3 + LOO 부호 유지</b>)를 적용 — 단일일 클러스터
 * pocket(예: 반등일 하루가 만든 가짜 +net)을 highlights에서 제외한다({@link StrategyPerformanceGate}와 동일 사상).
 * net = (종가−매수)/매수×100 − 왕복비용 − 슬리피지(close horizon). ⚠️ 전략 교차 pool이라 같은 종목·일자가
 * 여러 전략 행으로 중복 가능(v1 수용, includeControl=true면 미진입 후보까지 포함해 반사실 확대).</p>
 *
 * <p><b>⚠️ 진입-대조군 비교의 horizon 유의</b>(2026-08-14 수정): 대조군은 경량 추적이라 <b>exit 마크가 거의 없다</b>.
 * 따라서 {@code horizon=exit}에서는 대조군 커버리지가 낮아 {@code controlNetPct}/{@code edgeVsControlPct}가
 * <b>null로 비워진다</b>({@link #MIN_CONTROL_COVERAGE_PCT}) — 편향 부분집합으로 잘못된 결론을 내는 것보다 낫다.
 * <b>진입-대조군 반사실 비교는 {@code horizon=close}에서 수행</b>하고, exit horizon은 진입군 pocket 랭킹에만 쓸 것.</p>
 */
@Service
public class FeatureMiningService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 대조군 net·edge를 신뢰하려면 필요한 최소 커버리지(%).
     *
     * <p>🐞 2026-08-14 실측 버그: horizon="exit"이면 진입군은 권장청산마크로 평가되는데 <b>대조군은 exit 마크를
     * 거의 수집하지 않아</b>(경량 추적 — 가격 horizon만) 커버리지가 ~15%로 떨어진다. 그런데도 그 15%의
     * <b>편향된 부분집합</b>으로 controlNet을 계산해 {@code edgeVsControlPct}가 부호까지 뒤집혔다
     * (ret5d%&lt;-5 pocket: exit −2.51%p ↔ close <b>+0.13%p</b> / 지수mom30≥0: −1.56 ↔ −0.30).
     * → 커버리지 미달이면 <b>controlNet·edge를 null 처리</b>하고 커버리지 자체를 응답에 실어 소비자가 판정하게 한다.
     * 대조군 exit 마크 전량 수집은 추적 부하상 비현실적이라 "수집 확대"가 아니라 "신뢰도 노출"이 해법.</p>
     */
    static final double MIN_CONTROL_COVERAGE_PCT = 70.0;

    /** 교차거래일 최소 요건 — ControlAnalysisService/MultidayExitAnalysisService와 동일 규칙. */
    private static final int MIN_DISTINCT_DAYS = 3;

    private final TradeOutcomeRepository tradeOutcomeRepository;
    private final ExecutionCostModel executionCostModel;
    private final ExitHorizonPriceResolver exitResolver;   // horizon="exit"(게이트 동일 청산시점) 지원
    private final double roundTripCostPct;

    public FeatureMiningService(TradeOutcomeRepository tradeOutcomeRepository,
                                ExecutionCostModel executionCostModel,
                                ExitHorizonPriceResolver exitResolver,
                                @Value("${stockadvisor.cost.round-trip-pct:0.18}") double roundTripCostPct) {
        this.tradeOutcomeRepository = tradeOutcomeRepository;
        this.executionCostModel = executionCostModel;
        this.exitResolver = exitResolver;
        this.roundTripCostPct = roundTripCostPct;
    }

    /**
     * @param n/netAvgPct/winRatePct/clustered/topStrategy  진입(entered) 기준 pocket 통계
     * @param topDay/netExTopDayPct                         net 합 기여가 가장 큰 진입일과, 그날을 뺀 나머지 net 평균(LOO).
     *                                                      🐞 2026-08-26 추가 — 종전 {@code clustered}는 <b>건수 점유율</b>만 봐서
     *                                                      점유율이 문턱 아래인데 net은 하루가 만든 pocket을 못 걸렀다. 실측: highlights
     *                                                      1위였던 {@code 전략=REVERSAL_L}이 점유율 45.9%(&lt;80)로 통과했지만
     *                                                      net +1.44% → <b>최대기여일 제외 −0.08%</b>로 부호가 뒤집힌다. 같은 데이터를
     *                                                      {@code control-analysis}는 이미 {@code clustered=true}로 판정하고 있어
     *                                                      <b>엔드포인트끼리 정반대 결론</b>을 내던 상태였다
     *                                                      ({@code control-diagnosis} 8/21 · {@code multiday-exit} 8/24와 같은 유형의 재발)
     * @param controlN/controlNetPct                        같은 pocket의 대조군(미진입 후보) net — "진입이 미진입보다 나았나" 비교.
     *                                                      controlN은 <b>해당 horizon 가격이 실제로 있는</b> 대조군 수
     * @param controlTotalN                                 pocket의 전체 대조군 수(가격 유무 무관) — 커버리지 분모
     * @param controlCoveragePct                            controlN/controlTotalN×100. 이 값이 낮으면 대조군이 <b>편향된 부분집합</b>
     * @param edgeVsControlPct                              진입net − 대조군net(양수면 진입이 이 조건에서 가치 추가).
     *                                                      ⚠️ <b>커버리지 미달이면 null</b>(아래 MIN_CONTROL_COVERAGE_PCT 참조)
     * @param edgeFrom/edgeTo/edgeEnteredN/edgeEnteredNetPct edge 계산에 실제로 쓰인 <b>겹치는 거래일 구간</b>과 그 구간의 진입 표본·net.
     *                                                      ⚠️ 축마다 태깅 시작일이 달라(뉴스 8/22·호가불균형 8/22·체결강도 8/25 등)
     *                                                      정렬 없이 빼면 edge가 "조건 차이"가 아니라 <b>기간 차이</b>를 잰다 — 아래 {@link #overlapWindow} 참조
     */
    public record Bucket(String feature, String range, int n, int distinctDays, double maxDaySharePct,
                         String topDay, Double netExTopDayPct,
                         double netAvgPct, double winRatePct, boolean clustered, String topStrategy,
                         int controlN, Double controlNetPct, Double edgeVsControlPct,
                         int controlTotalN, double controlCoveragePct,
                         String edgeFrom, String edgeTo, int edgeEnteredN, Double edgeEnteredNetPct) {}

    public record FeatureMining(String feature, List<Bucket> buckets) {}

    /** @param since/until 진입일 구간(yyyyMMdd, 포함). 시간분할 검증용 — 전·후반 부호가 갈리면 그 pocket은 채택 금지. */
    public record MiningReport(int rows, int lookbackDays, String horizon, String market, String regime, int minSamples,
                               String strategy, String since, String until,
                               double maxDaySharePct, List<Bucket> highlights, List<Bucket> avoid,
                               List<FeatureMining> features) {}

    /** 숫자 feature bin 정의: 이름·추출자·경계(오름차순). */
    private record NumDef(String name, Function<TradeOutcome, Double> f, double[] edges) {}

    private List<NumDef> numericDefs() {
        List<NumDef> d = new ArrayList<>();
        d.add(new NumDef("거래량배수", TradeOutcome::getEntryVolumeRatio, new double[]{2, 4, 8, 15}));
        d.add(new NumDef("당일등락률%", TradeOutcome::getEntryChangeRate, new double[]{0, 2, 5, 10}));
        d.add(new NumDef("추천점수", TradeOutcome::getEntryRecScore, new double[]{40, 55, 70}));
        d.add(new NumDef("PER", TradeOutcome::getEntryPer, new double[]{0, 10, 20, 40}));
        d.add(new NumDef("PBR", TradeOutcome::getEntryPbr, new double[]{1, 2, 4}));
        d.add(new NumDef("시총(억)", o -> o.getEntryMarketCap() == null ? null : o.getEntryMarketCap().doubleValue(),
                new double[]{1000, 5000, 20000}));
        d.add(new NumDef("ATR%", TradeOutcome::getEntryAtrPct, new double[]{2, 4, 7}));
        d.add(new NumDef("고가거리%", TradeOutcome::getEntryDistHighPct, new double[]{-5, -2, 0}));
        d.add(new NumDef("ret5d%", TradeOutcome::getEntryRet5dPct, new double[]{-5, 0, 5, 10}));
        d.add(new NumDef("체결강도%", TradeOutcome::getEntryExecStrength, new double[]{100, 150, 200}));
        // 호가 불균형(2026-08-22): (매수잔량−매도잔량)/(합)×100. 체결강도가 '이미 체결된' 압력이라면 이건 '아직 대기 중인' 압력이라
        // 서로 다른 축이고, 단기(30~90분) 예측력이 문헌에 문서화돼 있어 **가설이 선행하는** 몇 안 되는 후보다.
        // ⚠️ 뉴스 축과 결정적으로 다른 점: 수집 지점이 볼륨게이트 통과 후보 전원이라 **대조군도 태깅**된다
        //    → edgeVsControlPct가 실제로 나온다(뉴스는 커버리지 0%라 구조적 null이었다).
        // ⚠️ 판정 주지표는 obi5(깊이·안정적), obi1은 즉시 압력이라 노이즈가 크다 — 둘이 어긋나면 obi5를 믿을 것.
        //    두 축을 함께 두는 건 다중검정 비용이지만, forward-only라 지금 안 모으면 몇 주를 잃는다(수집≠검정).
        d.add(new NumDef("호가불균형1%", TradeOutcome::getEntryObi1, new double[]{-30, 0, 30}));
        d.add(new NumDef("호가불균형5%", TradeOutcome::getEntryObi5, new double[]{-30, 0, 30}));
        // 수급(2026-08-22): 직전 거래일 외국인·기관 순매수 비중%(순매수/거래량). 가설은
        // "수급이 뒷받침된 급등은 유지되고 개인 물량만의 급등은 되돌려진다".
        // ⚠️ 이 축만 **소급 태깅**돼 40일 표본에 즉시 붙는다(다른 축은 forward-only) — 대조군도 채워져 edge가 나온다.
        // ⚠️ 기준일이 진입일 '직전'이라 look-ahead가 없다. 당일 수급을 쓰면 그 자체가 미래 정보다.
        d.add(new NumDef("외국인순매수%", TradeOutcome::getEntryFrgnNtbyRatio, new double[]{-2, 0, 2}));
        d.add(new NumDef("기관순매수%", TradeOutcome::getEntryOrgnNtbyRatio, new double[]{-2, 0, 2}));
        // 합산 축 — 컬럼 추가 없이 저장값 두 개로 파생(외국인·기관이 같은 방향일 때가 진짜 수급 신호라는 가설).
        d.add(new NumDef("수급합%", o -> {
            Double f = o.getEntryFrgnNtbyRatio(), g = o.getEntryOrgnNtbyRatio();
            return (f == null || g == null) ? null : f + g;
        }, new double[]{-3, 0, 3}));
        d.add(new NumDef("지수mom30", TradeOutcome::getEntryIndexMom30, new double[]{0}));
        // 시장폭%(2026-08-24): 진입 시점 해당 시장 상승종목 비율. 지수(시총가중 수준)·mom30(흐름)이 못 보는
        // <b>참여 넓이</b> 축이고, 진입 시 이미 태깅돼 있어(entry_market_breadth_pct) 비용 0·소급 즉시 적용된다.
        // 계기(2026-08-24 실측): KOSPI −1.62%(삼성전자 −8.7%) / KOSDAQ +1.51%인데 <b>양 시장 다 BEAR 라벨</b>이었고
        // breadth는 58% 상승·중앙 +0.47%였다 — 즉 "BEAR 라벨 아래에서 종목 다수가 오른" 날이다. 그날 게이트는
        // BEAR 버킷으로 대부분 닫혔고 섀도우는 +0.68%였다(기회 상실). 국면 라벨이 <b>종목 단위 승률</b>과 얼마나
        // 상관하는지를 직접 잰 적이 없어 이 괴리를 물을 수가 없었다.
        // ⚠️ 이 축의 진가는 <b>국면과의 교차</b>다 — 별도 교차 축을 만들지 않고 기존 {@code ?regime=} 파라미터와
        //    조합해 본다(예 regime=BEAR & 시장폭 ≥50 구간). 축을 늘리지 않아 다중검정 비용이 안 붙는다.
        // ⚠️ 경계 30/50/70은 breadth 리스크오프 문턱(15%)보다 훨씬 위 — 리스크오프는 '붕괴' 판정이고
        //    이 축은 '평상시 참여 넓이'를 가르는 것이라 목적이 다르다.
        // ⚠️ 전체(overall) breadth는 <b>일부러 뺐다</b> — 시장별 값과 강상관이라 축만 늘고 정보가 안 는다.
        d.add(new NumDef("시장폭%", TradeOutcome::getEntryMarketBreadthPct, new double[]{30, 50, 70}));
        d.add(new NumDef("종목갭%", TradeOutcome::getEntryGapPct, new double[]{2, 4, 7}));        // K 갭 구간별 성과
        d.add(new NumDef("지수갭%", TradeOutcome::getEntryIndexGapPct, new double[]{0, 1, 2}));   // 지수 통째 갭업일 여부
        d.add(new NumDef("뉴스1h건수", o -> o.getEntryNewsCnt1h() == null ? null : o.getEntryNewsCnt1h().doubleValue(),
                new double[]{1, 3}));
        // 뉴스경과분(2026-08-21): 승자/패자 분석에서 <b>10개 전략 중 8개가 같은 방향</b>으로 갈렸다 —
        // 승자의 최신 뉴스가 더 최근이다(E 1,153 vs 2,204 / G 2,005 vs 3,697 / A 1,168 vs 3,027 / F 1,934 vs 2,439 / J 3,053 vs 4,823).
        // 전략별 조건이 아니라 <b>전 전략 공통</b>으로 나온 첫 신호라 bin별 net을 볼 가치가 있다.
        // 첫 경계 60분은 FRESH_BAD_NEWS 가드의 fresh-news-window-minutes와 같은 기준(해석 정합).
        // ⚠️ <b>2026-08-22 소급으로 해소됨</b> — 도입 당시엔 대조군 태깅이 0%라 edgeVsControlPct가 구조적 null이었으나,
        //    NewsBacktagService(날짜·시각 페이징)로 진입·대조군 양쪽을 같은 방법으로 채워 커버리지가 대칭이 됐다
        //    (2026-08-24 실측 진입 90.7% / 대조군 92.9% — 남은 결손은 KIS 이력 ~30거래일 창 밖의 구표본).
        // 🔴 그리고 그 반사실 비교가 <b>가설을 반증했다</b>: "신선한 뉴스가 이긴다"는 틀렸고 방향이 반대다
        //    (bin별 net이 신선할수록 나쁘다). 승자/패자 <b>평균</b> 비교가 왜도 큰 분포에서 정반대 결론을 낸 사례 —
        //    이 축은 <b>중앙값·bin별 net</b>으로만 읽을 것.
        d.add(new NumDef("뉴스경과분", o -> o.getEntryNewsAgeMin() == null ? null : o.getEntryNewsAgeMin().doubleValue(),
                new double[]{60, 240, 720, 2880}));
        // 개장후경과분(2026-08-21): "아침 진입만 지는" 패턴이 2회 연속 실측됐는데 시각 축이 없어 물을 수가 없었다 —
        // 8/14 LIVE 15건이 전부 09:01~09:24에 몰려 −188,665원, 8/20 D LIVE 5건이 09:37~09:51에 몰려 −29,140원(평균 −2.05%)인 반면
        // 같은 날 10시 이후 D 신호는 +6.86%/+3.10%/+2.92%였다. 구조적 열위인지 우연인지 판정하려면 bin별 net이 필요하다.
        // alertTime이 이미 저장돼 있어 **소급 계산**된다(신규 태깅 불필요 = forward-only 제약 없음, 추가 조회 0).
        // 경계 15/60/150/270분 → "개장 15분", "~10:00", "~11:30", "~13:30", "13:30~마감".
        // ⚠️ 연속매매(09:00~15:30) 밖 값은 null로 버린다 — 장전 공시 경로 등이 "<15" bin에 섞이면 정작 보려는
        //    '개장 직후' 구간이 오염된다(세션 가드상 드물지만 0은 아니다).
        d.add(new NumDef("개장후경과분", o -> {
            if (o.getAlertTime() == null) return null;
            int min = o.getAlertTime().atZone(SEOUL).toLocalTime().toSecondOfDay() / 60 - 9 * 60;
            return (min < 0 || min > 390) ? null : (double) min;
        }, new double[]{15, 60, 150, 270}));
        return d;
    }

    private record CatDef(String name, Function<TradeOutcome, String> f) {}

    private List<CatDef> categoricalDefs() {
        return List.of(
                new CatDef("국면", TradeOutcome::getEntryMarketTrend),
                new CatDef("시장", TradeOutcome::getEntryMarket),
                new CatDef("업종", TradeOutcome::getEntrySector),
                new CatDef("전략", TradeOutcome::getStrategy));
    }

    public MiningReport mine(int lookbackDays, String horizon, String market, String regime, int minSamples,
                             double maxDaySharePct, boolean includeControl) {
        return mine(lookbackDays, horizon, market, regime, minSamples, maxDaySharePct, includeControl, null, null);
    }

    /**
     * @param since 진입일 하한(yyyyMMdd, 포함). null이면 lookbackDays 기준 cutoff.
     * @param until 진입일 상한(yyyyMMdd, 포함). null이면 제한 없음.
     *
     * <p><b>왜 필요한가</b>(2026-08-21 발굴 세션): pocket 후보를 전·후반으로 갈라 부호가 유지되는지 보는 게
     * 다중검정 허수를 거르는 가장 실용적인 방법인데, 이 파라미터가 없어 매번 raw SQL로 내려가야 했다.
     * 발굴 필터에 "시간분할 부호 일치"를 넣으려면 이게 전제다.</p>
     */
    public MiningReport mine(int lookbackDays, String horizon, String market, String regime, int minSamples,
                             double maxDaySharePct, boolean includeControl, String since, String until) {
        return mine(lookbackDays, horizon, market, regime, minSamples, maxDaySharePct, includeControl,
                since, until, null);
    }

    /**
     * @param strategy 이 전략의 진입·대조군만으로 좁혀 마이닝(null=전 전략 풀링).
     *
     * <p><b>왜 필요한가</b>(2026-09-02): 풀링 edge만 보고 전역 필터를 도입하면 안 된다는 게 이 시스템이
     * 두 번 배운 교훈인데(8/21 F·H vs K의 Simpson 역설, 8/25 EXEC_OVERHEAT의 H 제외), 정작 <b>축을 전략별로
     * 분해할 수단이 없었다</b>. 예컨대 "체결강도 ≥200 전역 상한"은 풀링 edge −1.61이지만, 그 구간에 실제로
     * 진입하는 전략은 가드가 안 걸린 쪽(E·B·F)뿐이라 전략별로 봐야 판단이 선다.</p>
     *
     * <p>저장된 컬럼 필터라 <b>비용 0·소급 즉시</b>. market/regime과 AND로 겹쳐 쓸 수 있다.</p>
     */
    public MiningReport mine(int lookbackDays, String horizon, String market, String regime, int minSamples,
                             double maxDaySharePct, boolean includeControl, String since, String until,
                             String strategy) {
        String cutoff = (since != null && !since.isBlank())
                ? since
                : LocalDate.now(SEOUL).minusDays(lookbackDays).format(YYYYMMDD);
        List<TradeOutcome> entered = new ArrayList<>();
        List<TradeOutcome> control = new ArrayList<>();   // 미진입 후보(반사실 비교용) — includeControl일 때만
        for (TradeOutcome o : tradeOutcomeRepository.findByAlertDateGreaterThanEqual(cutoff)) {
            if (o.getBuyPrice() <= 0) continue;
            // 구간 필터는 저장소 쿼리(하한)와 별개로 여기서도 확인 — 저장소 구현에 의존하지 않게(상한은 여기서만).
            if (o.getAlertDate() != null) {
                if (o.getAlertDate().compareTo(cutoff) < 0) continue;
                if (until != null && !until.isBlank() && o.getAlertDate().compareTo(until) > 0) continue;
            }
            if (strategy != null && !strategy.isBlank() && !strategy.equals(o.getStrategy())) continue;
            if (market != null && !market.isBlank() && !market.equals(o.getEntryMarket())) continue;
            if (regime != null && !regime.isBlank() && !regime.equals(o.getEntryMarketTrend())) continue;
            if (o.isControl()) { if (includeControl) control.add(o); } else entered.add(o);
        }

        // horizon 통일: outcomeId → net%. horizon="exit"이면 게이트와 동일하게 전략별 청산시점 가격(스윙=nextClose,
        // 그 외=권장청산마크 OutcomeSample). 마크 미수집이면 제외(fail-closed). ⚠️ 대조군은 exit 마크 미수집이라
        // exit horizon에선 자동 제외 → 진입-대조군 비교는 close horizon에서만 채워짐(대조군은 종가만 보유).
        Map<Long, Double> netByOutcome = new java.util.HashMap<>();
        List<TradeOutcome> both = new ArrayList<>(entered); both.addAll(control);
        Map<String, List<TradeOutcome>> byStrategy = new LinkedHashMap<>();
        for (TradeOutcome o : both) byStrategy.computeIfAbsent(o.getStrategy(), k -> new ArrayList<>()).add(o);
        for (Map.Entry<String, List<TradeOutcome>> e : byStrategy.entrySet()) {
            String effHz = "exit".equals(horizon) ? exitResolver.horizonFor(e.getKey(), "exit") : horizon;
            Function<TradeOutcome, Long> px = exitResolver.priceFor(e.getKey(), effHz);
            for (TradeOutcome o : e.getValue()) {
                Long price = px.apply(o);
                if (price != null && price > 0) netByOutcome.put(o.getId(), netPct(o, price));
            }
        }
        List<TradeOutcome> re = entered.stream().filter(o -> netByOutcome.containsKey(o.getId())).toList();
        // ⚠️ 대조군은 <b>필터하지 않고 전량</b> 넘긴다 — bucket마다 (해당 horizon 가격 보유분 / 전체)로
        //    커버리지를 계산해야 편향 부분집합 여부를 판정할 수 있기 때문(MIN_CONTROL_COVERAGE_PCT).
        List<TradeOutcome> rc = control;

        List<FeatureMining> features = new ArrayList<>();
        for (NumDef def : numericDefs()) {
            features.add(mineFeature(def.name(), re, rc, o -> {
                Double v = def.f().apply(o);
                return v == null ? null : binLabel(v, def.edges());
            }, minSamples, maxDaySharePct, netByOutcome));
        }
        for (CatDef def : categoricalDefs()) {
            features.add(mineFeature(def.name(), re, rc, o -> {
                String v = def.f().apply(o);
                return v == null || v.isBlank() ? null : v;
            }, minSamples, maxDaySharePct, netByOutcome));
        }
        List<TradeOutcome> rows = re;

        // 하이라이트: 가드 통과(비클러스터·표본충분) bucket 중 net 상위/하위
        List<Bucket> all = new ArrayList<>();
        for (FeatureMining fm : features) for (Bucket b : fm.buckets()) if (!b.clustered()) all.add(b);
        List<Bucket> highlights = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct).reversed()).limit(15).toList();
        List<Bucket> avoid = all.stream()
                .sorted(Comparator.comparingDouble(Bucket::netAvgPct)).limit(10).toList();

        return new MiningReport(rows.size(), lookbackDays, horizon == null ? "close" : horizon, market, regime,
                minSamples, strategy, cutoff, until, maxDaySharePct, highlights, avoid, features);
    }

    private FeatureMining mineFeature(String feature, List<TradeOutcome> entered, List<TradeOutcome> control,
                                      Function<TradeOutcome, String> binner,
                                      int minSamples, double maxDaySharePct, Map<Long, Double> netByOutcome) {
        Map<String, List<TradeOutcome>> byBucket = binGroups(entered, binner);
        Map<String, List<TradeOutcome>> byBucketControl = binGroups(control, binner);
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<TradeOutcome>> e : byBucket.entrySet()) {
            List<TradeOutcome> g = e.getValue();
            if (g.size() < minSamples) continue;
            buckets.add(statOf(feature, e.getKey(), g,
                    byBucketControl.getOrDefault(e.getKey(), List.of()), maxDaySharePct, netByOutcome));
        }
        buckets.sort(Comparator.comparingDouble(Bucket::netAvgPct).reversed());
        return new FeatureMining(feature, buckets);
    }

    private Map<String, List<TradeOutcome>> binGroups(List<TradeOutcome> rows, Function<TradeOutcome, String> binner) {
        Map<String, List<TradeOutcome>> m = new LinkedHashMap<>();
        for (TradeOutcome o : rows) {
            String b = binner.apply(o);
            if (b != null) m.computeIfAbsent(b, k -> new ArrayList<>()).add(o);
        }
        return m;
    }

    private Bucket statOf(String feature, String range, List<TradeOutcome> g, List<TradeOutcome> controlG,
                          double maxDaySharePct, Map<Long, Double> netByOutcome) {
        double sum = 0; int wins = 0;
        Map<String, Integer> days = new LinkedHashMap<>();
        Map<String, Double> netByDay = new LinkedHashMap<>();   // LOO(최대기여일 제외) 판정용
        Map<String, Integer> strat = new LinkedHashMap<>();
        for (TradeOutcome o : g) {
            double net = netByOutcome.get(o.getId());
            sum += net;
            if (net > 0) wins++;
            String day = o.getAlertDate() == null ? "?" : o.getAlertDate();
            days.merge(day, 1, Integer::sum);
            netByDay.merge(day, net, Double::sum);
            strat.merge(o.getStrategy() == null ? "?" : o.getStrategy(), 1, Integer::sum);
        }
        int n = g.size();
        double enteredNet = round2(sum / n);
        double maxShare = maxSharePct(days, n);
        // 최대기여일(net 합 절대값 최대) 제외 net — 점유율만으로는 "net이 하루로 설명되는" pocket을 못 잡는다.
        String topDay = netByDay.entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue())))
                .map(Map.Entry::getKey).orElse(null);
        Double netExTop = null;
        if (topDay != null && days.size() > 1) {
            int restN = n - days.get(topDay);
            if (restN > 0) netExTop = round2((sum - netByDay.get(topDay)) / restN);
        }
        boolean clustered = (maxDaySharePct > 0 && maxShare > maxDaySharePct)
                || days.size() < MIN_DISTINCT_DAYS
                || (netExTop != null && Math.signum(enteredNet) != Math.signum(netExTop));
        String top = strat.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");
        // 같은 pocket의 대조군(미진입 후보) net — "진입이 미진입보다 나았나".
        // ⚠️ 기간 정렬이 먼저다(2026-08-25): 축마다 대조군 태깅 시작일이 달라(체결강도는 2026-08-25부터)
        // 그냥 빼면 27거래일 진입군 vs 1거래일 대조군을 비교해 edge가 "그날 시장이 좋았다"를 잰다(실측 −1.89~−6.37 전부 허수).
        // → 두 군이 겹치는 거래일 구간으로 양쪽을 자른 뒤에만 edge를 낸다.
        String[] win = overlapWindow(datesOf(g), datesOf(controlG));
        List<TradeOutcome> gw = inWindow(g, win);
        List<TradeOutcome> cw = inWindow(controlG, win);
        // 커버리지(=해당 horizon 가격 보유 대조군 / 전체 대조군)가 낮으면 편향 부분집합이라 net·edge를 내지 않는다.
        double cs = 0; int resolved = 0;
        for (TradeOutcome o : cw) {
            Double net = netByOutcome.get(o.getId());
            if (net == null) continue;
            cs += net; resolved++;
        }
        double coverage = cw.isEmpty() ? 0 : 100.0 * resolved / cw.size();
        boolean trustworthy = resolved > 0 && coverage >= MIN_CONTROL_COVERAGE_PCT;
        Double controlNet = trustworthy ? round2(cs / resolved) : null;
        // 구간 내 진입 net — edge의 왼쪽 항. 전체 netAvgPct(pocket 랭킹용)는 그대로 두고 여기만 정렬한다.
        Double alignedNet = null;
        if (!gw.isEmpty()) {
            double as = 0;
            for (TradeOutcome o : gw) as += netByOutcome.get(o.getId());
            alignedNet = round2(as / gw.size());
        }
        Double edge = (controlNet == null || alignedNet == null) ? null : round2(alignedNet - controlNet);
        return new Bucket(feature, range, n, days.size(), round2(maxShare), topDay, netExTop,
                enteredNet, round2(100.0 * wins / n), clustered, top,
                resolved, controlNet, edge, cw.size(), round2(coverage),
                win == null ? null : win[0], win == null ? null : win[1], gw.size(), alignedNet);
    }

    private static List<String> datesOf(List<TradeOutcome> rows) {
        List<String> out = new ArrayList<>();
        for (TradeOutcome o : rows) if (o.getAlertDate() != null) out.add(o.getAlertDate());
        return out;
    }

    private static List<TradeOutcome> inWindow(List<TradeOutcome> rows, String[] win) {
        if (win == null) return List.of();
        List<TradeOutcome> out = new ArrayList<>();
        for (TradeOutcome o : rows) {
            String d = o.getAlertDate();
            if (d != null && d.compareTo(win[0]) >= 0 && d.compareTo(win[1]) <= 0) out.add(o);
        }
        return out;
    }

    /**
     * 진입군·대조군이 <b>겹치는 거래일 구간</b> {@code [from,to]}(yyyyMMdd) — 없으면 null.
     *
     * <p><b>왜 필요한가</b>: feature 축마다 태깅 시작일이 다르다(수급·뉴스는 소급돼 전 구간, 호가불균형은 2026-08-22~,
     * 체결강도 대조군은 2026-08-25~). 정렬 없이 진입net−대조군net을 빼면 <b>조건 차이가 아니라 기간 차이</b>를 재게 된다 —
     * 실측 2026-08-25: 체결강도 축의 edge가 −1.89~−6.37로 나왔는데 진입군은 27거래일, 대조군은 <b>그날 하루</b>뿐이었고
     * 그날이 강세 반등일이라 대조군 net만 부풀었다(전부 허수).</p>
     *
     * <p>날짜가 yyyyMMdd 고정폭이라 <b>사전식 비교가 곧 시간순 비교</b>다.</p>
     */
    static String[] overlapWindow(List<String> entryDates, List<String> controlDates) {
        if (entryDates.isEmpty() || controlDates.isEmpty()) return null;
        String eFrom = minOf(entryDates), eTo = maxOf(entryDates);
        String cFrom = minOf(controlDates), cTo = maxOf(controlDates);
        String from = eFrom.compareTo(cFrom) >= 0 ? eFrom : cFrom;   // 늦게 시작한 쪽
        String to = eTo.compareTo(cTo) <= 0 ? eTo : cTo;             // 먼저 끝난 쪽
        return from.compareTo(to) <= 0 ? new String[]{from, to} : null;
    }

    private static String minOf(List<String> v) {
        String m = v.get(0);
        for (String x : v) if (x.compareTo(m) < 0) m = x;
        return m;
    }

    private static String maxOf(List<String> v) {
        String m = v.get(0);
        for (String x : v) if (x.compareTo(m) > 0) m = x;
        return m;
    }

    private double netPct(TradeOutcome o, long price) {
        double slip = o.getEntrySlippagePct() != null ? o.getEntrySlippagePct()
                : executionCostModel.estimateRoundTripSlippagePct(o.getBuyPrice());
        return (double) (price - o.getBuyPrice()) / o.getBuyPrice() * 100 - (roundTripCostPct + slip);
    }

    // ── 순수 코어(단위테스트 대상) ──────────────────────────────
    /** 값 v를 오름차순 경계 edges로 bin 라벨링. 예 edges[2,4,8]→ "<2","2~4","4~8","≥8". */
    static String binLabel(double v, double[] edges) {
        if (edges.length == 0) return "all";
        if (v < edges[0]) return "<" + fmt(edges[0]);
        for (int i = 0; i < edges.length - 1; i++) if (v < edges[i + 1]) return fmt(edges[i]) + "~" + fmt(edges[i + 1]);
        return "≥" + fmt(edges[edges.length - 1]);
    }

    /** 최대 단일 거래일 점유율(%). */
    static double maxSharePct(Map<String, Integer> days, int n) {
        if (n <= 0 || days.isEmpty()) return 0;
        int max = 0;
        for (int c : days.values()) if (c > max) max = c;
        return 100.0 * max / n;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
