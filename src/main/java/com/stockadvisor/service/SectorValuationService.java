package com.stockadvisor.service;

import com.stockadvisor.config.properties.SectorValuationProperties;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisQuoteResponse;
import com.stockadvisor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 업종별 밸류에이션 중앙값 산출/제공 (업종 상대평가용).
 *
 * <p>워치리스트 전 종목 시세를 집계해 <b>업종별 PER/PBR 중앙값</b>을 캐시한다. {@link RecommendationService}가
 * 이 중앙값 대비 비율로 밸류에이션을 점수화한다. 전 종목 순회라 무거워(~워치리스트 콜) 장전 1회 배치 + 수동만 갱신.
 * 갱신 전(또는 업종 표본 부족)이면 호출측이 절대기준으로 fallback.</p>
 */
@Service
public class SectorValuationService {

    private static final Logger log = LoggerFactory.getLogger(SectorValuationService.class);

    private final KisApiClient kisApiClient;
    private final CompanyRepository companyRepository;
    private final SectorValuationProperties props;

    private volatile Map<String, SectorStat> cache = Map.of();
    private volatile Instant lastRefresh;

    public SectorValuationService(KisApiClient kisApiClient, CompanyRepository companyRepository,
                                  SectorValuationProperties props) {
        this.kisApiClient = kisApiClient;
        this.companyRepository = companyRepository;
        this.props = props;
    }

    /** @param count PER·PBR 양쪽 모두 있는 종목 수(중앙값 신뢰도). */
    public record SectorStat(String sector, double medianPer, double medianPbr, int count) {}

    /** 업종 중앙값. 비활성/업종미상/표본부족이면 null → 호출측 절대기준 fallback. */
    public SectorStat statOf(String sector) {
        if (!props.enabled() || sector == null || sector.isBlank()) return null;
        return cache.get(sector);
    }

    public List<SectorStat> describe() {
        List<SectorStat> out = new ArrayList<>(cache.values());
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }

    /** 장전 1회 배치 — 업종 중앙값 재계산. */
    @Scheduled(cron = "${stockadvisor.scheduler.sector-valuation-cron}",
            zone = "${stockadvisor.scheduler.zone}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception ex) {
            log.error("업종 밸류에이션 갱신 스케줄 오류", ex);
        }
    }

    /** 워치리스트 시세 집계 → 업종별 PER/PBR 중앙값. @return 산출된 업종 수 */
    public int refresh() {
        Map<String, List<Double>> pers = new HashMap<>();
        Map<String, List<Double>> pbrs = new HashMap<>();
        int scanned = 0, quoted = 0;
        for (String code : companyRepository.findAllStockCodes()) {
            scanned++;
            try {
                KisQuoteResponse.Output o = kisApiClient.fetchCurrentQuote(code).output();
                if (o == null) continue;
                String sector = o.sectorName();
                if (sector == null || sector.isBlank()) continue;
                double per = parse(o.per());
                double pbr = parse(o.pbr());
                if (per > 0) pers.computeIfAbsent(sector, k -> new ArrayList<>()).add(per);
                if (pbr > 0) pbrs.computeIfAbsent(sector, k -> new ArrayList<>()).add(pbr);
                quoted++;
            } catch (Exception ignore) { /* 종목별 실패는 건너뜀 */ }
        }

        Map<String, SectorStat> map = new HashMap<>();
        Set<String> sectors = new HashSet<>(pers.keySet());
        sectors.retainAll(pbrs.keySet());   // PER·PBR 양쪽 다 있는 업종만
        for (String sector : sectors) {
            List<Double> pl = pers.get(sector);
            List<Double> bl = pbrs.get(sector);
            int cnt = Math.min(pl.size(), bl.size());
            if (cnt < props.minStocks()) continue;   // 표본 부족 → 제외(절대기준 fallback)
            map.put(sector, new SectorStat(sector, median(pl), median(bl), cnt));
        }
        cache = map;
        lastRefresh = Instant.now();
        log.info("업종 밸류에이션 갱신: {}개 업종(종목 {} / 스캔 {})", map.size(), quoted, scanned);
        return map.size();
    }

    /** 정렬 후 중앙값. */
    public static double median(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.sort(Double::compareTo);
        int n = v.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
