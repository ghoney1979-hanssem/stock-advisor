package com.stockadvisor.service;

import com.stockadvisor.market.NaverDailyPriceClient;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.DailyPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일봉 히스토리 <b>대량 적재</b> — 멀티데이 전략 백테스트의 전제 데이터.
 *
 * <p>지금까지 이 시스템이 백테스트를 못 한 이유는 <b>과거 분봉이 없어서</b>였다(인트라데이 전략은 포워드 섀도우가
 * 유일한 검증 수단). 그런데 멀티데이 전략은 <b>일봉만으로 완전히 재현</b>되므로, 장기 일봉만 있으면 파라미터 탐색과
 * 꼬리 손실 측정이 가능해진다. 이 서비스가 그 데이터를 한 번에 확보한다.</p>
 *
 * <p>설계 요점:</p>
 * <ul>
 *   <li><b>종목당 1콜</b> — 소스가 1콜에 10년치를 준다(실측 2,613행). 페이징이 없어 1,500콜로 끝난다.</li>
 *   <li><b>재실행 안전</b> — upsert(on conflict do nothing) + 이미 구간이 덮인 종목은 <b>조회조차 생략</b>.</li>
 *   <li><b>종목별 실패 격리</b> — 한 종목이 실패해도 나머지는 계속한다(연구 데이터라 부분 성공이 유효하다).</li>
 *   <li><b>JdbcTemplate 배치</b> — 370만 행 규모라 영속성 컨텍스트를 태우면 느리다.</li>
 * </ul>
 *
 * <p>⚠️ <b>생존편향</b>: 대상이 <b>오늘 기준</b> 워치리스트(시총 상위 1,500)라, 이 데이터로 돌린 백테스트는
 * "10년을 살아남아 지금 상위권인 회사"만 본다. <b>우량주 물타기 전략은 이 편향에 가장 취약하다</b> —
 * 물타기가 죽는 경로가 곧 "계속 내려가 밀려나거나 폐지되는" 시나리오인데 그게 표본에서 빠진다.
 * → 결과는 <b>낙관 상한</b>으로만 읽고, 절대 수익률보다 <b>파라미터 간 상대 비교</b>에 쓸 것.
 * (소스는 폐지 종목 가격도 주므로, 과거 시점 유니버스를 따로 구성하면 교정은 가능하다 — 미실행.)</p>
 */
@Service
public class DailyHistoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DailyHistoryBackfillService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 이 시각 이후면 오늘 일봉이 확정됐다고 보고 포함한다(KRX 마감 15:30 + 여유). */
    private static final LocalTime BAR_FINAL_AFTER = LocalTime.of(16, 0);
    private static final int DEFAULT_YEARS = 10;
    private static final int BATCH_SIZE = 1000;

    private static final String UPSERT =
            "insert into daily_price "
            + "(stock_code, business_date, open_price, high_price, low_price, close_price, volume, frgn_hold_pct) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (stock_code, business_date) do nothing";

    private final CompanyRepository companyRepository;
    private final DailyPriceRepository dailyPriceRepository;
    private final NaverDailyPriceClient client;
    private final JdbcTemplate jdbcTemplate;
    /** 소스 예의상 호출 간격(ms). 실측은 무지연 15콜/2초에도 차단이 없었지만 1,500콜은 얌전히 돈다. */
    private final long throttleMs;

    public DailyHistoryBackfillService(CompanyRepository companyRepository,
                                       DailyPriceRepository dailyPriceRepository,
                                       NaverDailyPriceClient client,
                                       JdbcTemplate jdbcTemplate,
                                       @Value("${stockadvisor.daily-history.throttle-ms:250}") long throttleMs) {
        this.companyRepository = companyRepository;
        this.dailyPriceRepository = dailyPriceRepository;
        this.client = client;
        this.jdbcTemplate = jdbcTemplate;
        this.throttleMs = throttleMs;
    }

    /**
     * @param stocksTotal   워치리스트 종목 수
     * @param stocksFetched 실제 조회한 종목 수
     * @param stocksSkipped 이미 구간이 덮여 조회를 생략한 종목 수
     * @param stocksEmpty   조회는 했으나 행이 0인 종목(없는 코드·신규 상장 등)
     * @param rowsInserted  새로 적재된 행 수(중복은 do nothing이라 제외)
     */
    public record BackfillReport(int stocksTotal, int stocksFetched, int stocksSkipped, int stocksEmpty,
                                 int rowsFetched, int rowsInserted, String from, String to,
                                 long elapsedMs, String note) {}

    /**
     * @param years     조회 연수(기본 10). 하락장 표본(2018·2020·2022)을 포함하려면 10년이 필요하다.
     * @param limit     테스트용 종목 수 제한(null=전체)
     * @param startDate 명시하면 years 대신 사용(YYYYMMDD) — 증분 갱신용 짧은 창.
     * @param force     true면 커버리지 검사 없이 전 종목 재조회(포맷 변경·결손 의심 시).
     */
    public BackfillReport backfill(Integer years, Integer limit, String startDate, boolean force) {
        long started = System.currentTimeMillis();
        ZonedDateTime now = ZonedDateTime.now(SEOUL);
        LocalDate today = now.toLocalDate();
        // 오늘 행은 장중이면 '부분봉'이라 반드시 걸러야 한다 — 안 거르면 백테스트가 존재하지 않던 봉을 본다.
        String maxDate = now.toLocalTime().isBefore(BAR_FINAL_AFTER)
                ? today.minusDays(1).format(YYYYMMDD)
                : today.format(YYYYMMDD);
        String from = (startDate != null && startDate.matches("\\d{8}"))
                ? startDate
                : today.minusYears(years == null || years <= 0 ? DEFAULT_YEARS : years).format(YYYYMMDD);
        String to = today.format(YYYYMMDD);

        List<String> codes = companyRepository.findAllStockCodes();
        int total = codes.size();
        if (limit != null && limit > 0 && limit < codes.size()) codes = codes.subList(0, limit);

        Map<String, String[]> coverage = force ? Map.of() : loadCoverage();

        int fetched = 0, skipped = 0, empty = 0, rowsFetched = 0, rowsInserted = 0;
        for (String code : codes) {
            if (!force && covered(coverage.get(code), from, maxDate)) {
                skipped++;
                continue;
            }
            List<NaverDailyPriceClient.Bar> bars = client.fetchDaily(code, from, to, maxDate);
            fetched++;
            if (bars.isEmpty()) {
                empty++;
            } else {
                rowsFetched += bars.size();
                rowsInserted += insertBatch(code, bars);
            }
            if (throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (fetched % 200 == 0) {
                log.info("[일봉적재] 진행 {}/{} 종목 · 적재 {}행", fetched, codes.size(), rowsInserted);
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        String note = "생존편향 주의: 대상이 오늘 기준 워치리스트라 결과는 낙관 상한. 파라미터 간 상대 비교에 쓸 것";
        log.info("[일봉적재] 완료 조회 {}종목(생략 {}·빈응답 {}) · {}행 적재 · {}ms",
                fetched, skipped, empty, rowsInserted, elapsed);
        return new BackfillReport(total, fetched, skipped, empty, rowsFetched, rowsInserted,
                from, maxDate, elapsed, note);
    }

    /** 적재 현황 요약(조회 전용). */
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("stocks", dailyPriceRepository.countDistinctStocks());
        m.put("rows", dailyPriceRepository.count());
        m.put("from", dailyPriceRepository.minBusinessDate());
        m.put("to", dailyPriceRepository.maxBusinessDate());
        m.put("watchlist", companyRepository.count());
        return m;
    }

    /** 종목별 [min, max] 커버리지. */
    private Map<String, String[]> loadCoverage() {
        Map<String, String[]> m = new HashMap<>();
        for (Object[] r : dailyPriceRepository.summarizeCoverage()) {
            m.put((String) r[0], new String[]{(String) r[1], (String) r[2]});
        }
        return m;
    }

    /**
     * 이미 요청 구간을 덮고 있는가.
     *
     * <p>⚠️ 하한은 <b>느슨하게</b> 본다 — 신규 상장 종목은 요청 시작일보다 데이터가 늦게 시작하는 게 정상이라
     * 엄격히 보면 매 실행마다 영원히 재조회한다. 판단은 <b>상단이 최신인가</b>를 주로 보고,
     * 하한은 "이미 가진 것보다 더 과거를 요구하면 재조회"로만 쓴다.</p>
     */
    static boolean covered(String[] minMax, String from, String maxDate) {
        if (minMax == null || minMax[0] == null || minMax[1] == null) return false;
        if (minMax[1].compareTo(maxDate) < 0) return false;   // 최신이 아니면 갱신 필요
        return minMax[0].compareTo(from) <= 0;                // 더 과거를 요구하면 재조회
    }

    private int insertBatch(String code, List<NaverDailyPriceClient.Bar> bars) {
        int inserted = 0;
        for (int i = 0; i < bars.size(); i += BATCH_SIZE) {
            List<NaverDailyPriceClient.Bar> chunk = bars.subList(i, Math.min(i + BATCH_SIZE, bars.size()));
            int[] counts = jdbcTemplate.batchUpdate(UPSERT, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    NaverDailyPriceClient.Bar b = chunk.get(idx);
                    ps.setString(1, code);
                    ps.setString(2, b.businessDate());
                    ps.setLong(3, b.open());
                    ps.setLong(4, b.high());
                    ps.setLong(5, b.low());
                    ps.setLong(6, b.close());
                    ps.setLong(7, b.volume());
                    if (b.frgnHoldPct() == null) ps.setNull(8, Types.DOUBLE);
                    else ps.setDouble(8, b.frgnHoldPct());
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
            for (int c : counts) if (c > 0) inserted += c;
        }
        return inserted;
    }
}
