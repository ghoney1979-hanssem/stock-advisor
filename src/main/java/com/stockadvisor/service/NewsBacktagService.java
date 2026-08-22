package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisNewsResponse;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 뉴스 feature(진입 1시간 내 건수·최신 경과분) <b>소급 태깅</b>.
 *
 * <p>왜 필요한가 — 뉴스 축은 <b>진입 시에만 종목당 1콜 lazy</b>로 태깅돼 <b>대조군 커버리지가 0%</b>였다.
 * 그래서 8/21의 "신선할수록 나쁘다"(&lt;60분 −0.79% vs 720~2880분 −0.31%) 실측이 <b>반사실 없이 반쪽</b>으로 남았다 —
 * "뉴스가 나쁜 것"인지 "뉴스 나는 종목이 나쁜 것"인지 구분하지 못한다. 그 구분은 대조군으로만 가능하다.</p>
 *
 * <p>소급이 가능한 근거(2026-08-22 실측): {@code FID_INPUT_DATE_1}로 과거 날짜가 조회되고,
 * {@code FID_INPUT_HOUR_1}로 <b>그 시각 이전</b>만 받을 수 있다 → 진입 시각 뷰를 그대로 재구성한다.</p>
 *
 * <p><b>40건 상한 대응(설계의 핵심)</b> — 1콜이 40건뿐이라 활발한 종목은 하루치도 안 된다.
 * (종목,일자) 쌍의 행들을 <b>진입시각 내림차순</b>으로 처리하며 받아둔 항목 풀을 재사용하고,
 * 풀이 그 행의 관심 구간(진입시각−60분)을 못 덮을 때만 추가로 호출한다 →
 * 뉴스가 드문 종목은 쌍당 1콜, 삼성전자급만 여러 콜.</p>
 *
 * <p>⚠️ <b>기존 라이브 태깅분도 다시 덮어쓴다</b>(의도). 진입군 77.4% / 대조군 0%인 비대칭 커버리지는
 * 비교 자체를 편향시키므로, 양쪽을 <b>같은 방법으로</b> 100%에 맞추는 것이 이 작업의 절반이다.</p>
 *
 * <p>⚠️ 트랜잭션을 길게 잡지 않는다 — 33,689행·수십 분 작업이라 배치 단위로 저장한다.</p>
 */
@Service
public class NewsBacktagService {

    private static final Logger log = LoggerFactory.getLogger(NewsBacktagService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int WINDOW_MIN = 60;          // 라이브 태깅과 동일(fresh-news-window-minutes)
    private static final int SAVE_BATCH = 500;
    private static final int DEFAULT_LOOKBACK_DAYS = 45;
    private static final int MAX_PAGES_PER_PAIR = 6;   // 폭주 방지(삼성전자급도 6콜이면 충분)

    private final TradeOutcomeRepository repository;
    private final KisApiClient kisApiClient;

    public NewsBacktagService(TradeOutcomeRepository repository, KisApiClient kisApiClient) {
        this.repository = repository;
        this.kisApiClient = kisApiClient;
    }

    public record BacktagReport(int pairs, int apiCalls, int rowsTagged, int rowsFailed,
                                int rowsWithNews, long elapsedSec) {}

    /**
     * @param lookbackDays 소급 대상 기간(기본 45일)
     * @param force        true면 이미 태깅된 행도 다시 채운다(방법 통일용). false면 미태깅 행만.
     */
    public BacktagReport backfill(Integer lookbackDays, boolean force) {
        long startedAt = System.currentTimeMillis();
        int days = (lookbackDays == null || lookbackDays <= 0) ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
        String cutoff = LocalDate.now(SEOUL).minusDays(days).format(YYYYMMDD);

        Map<String, List<TradeOutcome>> byPair = new LinkedHashMap<>();
        for (TradeOutcome o : repository.findByAlertDateGreaterThanEqual(cutoff)) {
            if (!force && o.getEntryNewsBasisDate() != null) continue;
            if (o.getAlertTime() == null) continue;
            byPair.computeIfAbsent(o.getStockCode() + "|" + o.getAlertDate(), k -> new ArrayList<>()).add(o);
        }

        int calls = 0, tagged = 0, failed = 0, withNews = 0;
        List<TradeOutcome> pending = new ArrayList<>();
        for (Map.Entry<String, List<TradeOutcome>> e : byPair.entrySet()) {
            int sep = e.getKey().indexOf('|');
            String stockCode = e.getKey().substring(0, sep);
            String date = e.getKey().substring(sep + 1);
            List<TradeOutcome> rows = new ArrayList<>(e.getValue());
            // 늦은 진입부터 — 한 번 받아둔 항목 풀을 이른 진입에도 재사용할 수 있다(항목이 시간 역순이므로).
            rows.sort(Comparator.comparing(TradeOutcome::getAlertTime).reversed());

            List<KisNewsResponse.NewsItem> pool = new ArrayList<>();
            LocalDateTime poolOldest = null;
            int pages = 0;

            for (TradeOutcome o : rows) {
                LocalDateTime at = LocalDateTime.ofInstant(o.getAlertTime(), SEOUL);
                LocalDateTime needFrom = at.minusMinutes(WINDOW_MIN);
                // 풀이 관심 구간을 못 덮으면 이 행의 진입시각 기준으로 추가 페이지를 받는다.
                if ((poolOldest == null || poolOldest.isAfter(needFrom)) && pages < MAX_PAGES_PER_PAIR) {
                    try {
                        KisNewsResponse r = kisApiClient.fetchNewsTitles(stockCode, date, at.format(HHMMSS));
                        calls++;
                        pages++;
                        if (r != null && r.output() != null) {
                            pool.addAll(r.output());
                            LocalDateTime o2 = oldest(r.output());
                            if (o2 != null && (poolOldest == null || o2.isBefore(poolOldest))) poolOldest = o2;
                        }
                    } catch (Exception ex) {
                        failed++;
                        log.debug("뉴스 소급 조회 실패 [{} {}]: {}", stockCode, date, ex.getMessage());
                        continue;
                    }
                }
                // ⚠️ 진입시각 '이후' 항목은 반드시 버린다 — features()는 미래 항목을 age 0(방금 나온 뉴스)으로
                //    치므로, 걸러내지 않으면 소급이 조용히 look-ahead가 된다.
                List<KisNewsResponse.NewsItem> visible = new ArrayList<>();
                for (KisNewsResponse.NewsItem n : pool) {
                    LocalDateTime t = at(n);
                    if (t != null && !t.isAfter(at)) visible.add(n);
                }
                KisNewsResponse.NewsFeature f =
                        KisNewsResponse.features(visible, ZonedDateTime.of(at, SEOUL), WINDOW_MIN);
                o.setEntryNews(f.recentCount(), f.latestAgeMin());
                o.setEntryNewsBasisDate(date);
                tagged++;
                if (f.latestAgeMin() != null) withNews++;
                pending.add(o);
            }

            if (pending.size() >= SAVE_BATCH) {
                repository.saveAll(pending);          // 긴 트랜잭션 회피 — 배치마다 커밋
                pending.clear();
            }
        }
        if (!pending.isEmpty()) repository.saveAll(pending);

        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        log.info("뉴스 소급 태깅 완료 — 쌍 {}, 호출 {}, 태깅 {}행(뉴스있음 {}), 실패 {}행, {}초",
                byPair.size(), calls, tagged, withNews, failed, sec);
        return new BacktagReport(byPair.size(), calls, tagged, failed, withNews, sec);
    }

    private static LocalDateTime oldest(List<KisNewsResponse.NewsItem> items) {
        LocalDateTime min = null;
        for (KisNewsResponse.NewsItem n : items) {
            LocalDateTime t = at(n);
            if (t != null && (min == null || t.isBefore(min))) min = t;
        }
        return min;
    }

    private static LocalDateTime at(KisNewsResponse.NewsItem n) {
        if (n == null || n.date() == null || n.time() == null) return null;
        try {
            String t = n.time().length() >= 6 ? n.time().substring(0, 6)
                    : (n.time() + "000000").substring(0, 6);
            return LocalDateTime.parse(n.date() + t, DT);
        } catch (Exception e) {
            return null;
        }
    }
}
