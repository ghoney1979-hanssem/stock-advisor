package com.stockadvisor.service;

import com.stockadvisor.config.properties.MarketRegimeProperties;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisDailyPriceResponse;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 과거 섀도우 표본의 시장 국면(entry_market_trend) 소급 태깅.
 *
 * <p>국면 태깅이 도입되기 전 진입분은 {@code entry_market_trend}가 null이라 국면조건부 성과게이트에서 제외된다.
 * 국면 판정({@link MarketRegimeService#computeRegime})이 프록시 ETF 일봉 종가만의 순수함수이므로,
 * <b>각 (시장, 진입일)에 대해 그날까지의 종가 시계열로 국면을 재구성</b>해 null 태그를 채운다.</p>
 *
 * <ul>
 *   <li>라이브 태깅과 동일한 판정식(MA{maPeriod}+기울기) — 다만 라이브는 진입 당시 잠정종가·캐시, 재구성은 확정종가라
 *       문턱 근처 날짜는 한 단계 다를 수 있음(근사).</li>
 *   <li><b>null만 채우고 기존 라이브 태그는 덮어쓰지 않음.</b> 프록시 일봉은 ~30영업일이라 그 창 밖 날짜는
 *       표본 부족으로 skip(오태깅 방지). 재실행 안전(이미 채워진 건 대상에서 빠짐).</li>
 * </ul>
 */
@Service
public class RegimeBacktagService {

    private static final Logger log = LoggerFactory.getLogger(RegimeBacktagService.class);

    private final KisApiClient kisApiClient;
    private final MarketRegimeProperties props;
    private final TradeOutcomeRepository tradeOutcomeRepository;

    public RegimeBacktagService(KisApiClient kisApiClient, MarketRegimeProperties props,
                                TradeOutcomeRepository tradeOutcomeRepository) {
        this.kisApiClient = kisApiClient;
        this.props = props;
        this.tradeOutcomeRepository = tradeOutcomeRepository;
    }

    public record BacktagResult(int targetCombos, int updated, List<String> details) {}

    private record DateClose(String date, double close) {}

    @Transactional
    public BacktagResult backfill() {
        List<Object[]> combos = tradeOutcomeRepository.findDistinctMarketDateWithNullTrend();
        // 시장별 날짜 그룹핑
        Map<String, List<String>> byMarket = new TreeMap<>();
        for (Object[] c : combos) {
            String market = (String) c[0];
            String date = (String) c[1];
            byMarket.computeIfAbsent(market, k -> new ArrayList<>()).add(date);
        }

        int updated = 0;
        List<String> details = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byMarket.entrySet()) {
            String market = e.getKey();
            String proxy = proxyFor(market);
            if (proxy == null) {
                details.add(market + ": 프록시 미정 → skip");
                continue;
            }
            List<DateClose> series = fetchSeries(proxy);
            if (series.isEmpty()) {
                details.add(market + ": 프록시 일봉 없음 → skip");
                continue;
            }
            for (String date : e.getValue()) {
                // 해당일까지의 종가(오래된→최신). yyyyMMdd 문자열 비교 = 시간순.
                List<Double> chrono = new ArrayList<>();
                for (DateClose dc : series) {
                    if (dc.date().compareTo(date) <= 0) chrono.add(dc.close());
                }
                MarketRegimeService.MarketRegime regime =
                        MarketRegimeService.computeRegime(market, proxy, chrono, date, props);
                if (!regime.available()) {
                    details.add(market + " " + date + ": 표본부족(n=" + chrono.size() + ") → skip");
                    continue;
                }
                String trend = regime.trend().name();
                int n = tradeOutcomeRepository.backfillTrend(market, date, trend);
                updated += n;
                details.add(market + " " + date + " → " + trend + " (" + n + "건)");
            }
        }
        log.info("국면 소급 태깅 완료: 대상 조합 {} / 갱신 {}건", combos.size(), updated);
        return new BacktagResult(combos.size(), updated, details);
    }

    private String proxyFor(String market) {
        if ("KOSPI".equals(market)) return props.kospiProxyCode();
        if ("KOSDAQ".equals(market)) return props.kosdaqProxyCode();
        return null;
    }

    /** 프록시 일봉 → (거래일, 종가) 시계열(오래된→최신). KIS 응답은 최신일 우선이라 뒤집는다. */
    private List<DateClose> fetchSeries(String proxy) {
        List<DateClose> out = new ArrayList<>();
        KisDailyPriceResponse resp = kisApiClient.fetchDailyPrices(proxy);
        List<KisDailyPriceResponse.DailyPrice> rows = resp == null ? null : resp.output();
        if (rows == null) return out;
        for (int i = rows.size() - 1; i >= 0; i--) {
            KisDailyPriceResponse.DailyPrice row = rows.get(i);
            double c = parse(row.closePrice());
            if (c > 0 && row.businessDate() != null && !row.businessDate().isBlank()) {
                out.add(new DateClose(row.businessDate(), c));
            }
        }
        return out;
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
