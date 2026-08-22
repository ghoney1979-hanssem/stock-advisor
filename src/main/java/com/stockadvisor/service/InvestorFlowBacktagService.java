package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisInvestorResponse;
import com.stockadvisor.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외국인·기관 수급(순매수 비중) <b>소급 태깅</b>.
 *
 * <p>이 축이 특별한 이유는 <b>소급이 된다</b>는 것이다. 뉴스·체결강도·호가불균형은 전부 forward-only라
 * "몇 주 모아야 판정 가능"했는데, KIS 투자자매매동향은 <b>1콜에 최근 30거래일치</b>를 준다
 * (실측 20260709~20260821). 즉 <b>종목당 1콜로 그 종목의 모든 과거 진입일이 덮인다</b> —
 * 실측 기준 고유종목 896개 콜로 33,689행(진입 4,729 + 대조군 28,960)을 채운다.</p>
 *
 * <p>그래서 순서가 뒤집힌다: 태깅 코드를 매매 경로에 붙여 비용(후보당 1콜)을 내기 <b>전에</b>,
 * 이미 쌓인 표본으로 가설을 먼저 검증할 수 있다. 지면 붙이지 않으면 된다.</p>
 *
 * <ul>
 *   <li><b>대조군도 함께</b> 채운다 → {@code edgeVsControlPct}가 처음부터 나온다(뉴스 축의 실패를 반복하지 않는다).</li>
 *   <li>null인 행만 채운다. 재실행 안전(이미 채운 종목은 조회조차 하지 않는다).</li>
 *   <li>종목별 실패는 격리 — 한 종목이 실패해도 나머지는 진행한다.</li>
 * </ul>
 *
 * <p>⚠️ 기준일은 <b>진입일 직전 거래일</b>이다({@link KisInvestorResponse#priorTo}) — 당일 행은 look-ahead다.
 * 소급 태깅은 미래를 보기가 너무 쉬워서, 이 규칙이 feature 유효성의 전부라 해도 과언이 아니다.</p>
 *
 * <p>⚠️ API 이력이 ~30거래일이라 <b>그보다 오래된 진입은 채울 수 없다</b>(null 유지 → 분석에서 자동 제외).
 * 오래된 표본을 원하면 지금 돌려야 한다 — 창은 매일 뒤로 밀린다.</p>
 */
@Service
public class InvestorFlowBacktagService {

    private static final Logger log = LoggerFactory.getLogger(InvestorFlowBacktagService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** API가 주는 이력(~30거래일)보다 넉넉히 잡은 조회 하한 — 어차피 못 채우는 행을 조회로 확인만 한다. */
    private static final int DEFAULT_LOOKBACK_DAYS = 60;

    private final TradeOutcomeRepository repository;
    private final KisApiClient kisApiClient;

    public InvestorFlowBacktagService(TradeOutcomeRepository repository, KisApiClient kisApiClient) {
        this.repository = repository;
        this.kisApiClient = kisApiClient;
    }

    /** 소급 결과 요약. {@code skippedNoData}는 API 이력 창 밖(오래된 진입)이 대부분이다. */
    public record BacktagReport(int stocksQueried, int stocksFailed, int rowsTagged,
                                int rowsSkippedNoData, int rowsAlreadyTagged, String oldestBasisDate) {}

    @Transactional
    public BacktagReport backfill(Integer lookbackDays) {
        int days = (lookbackDays == null || lookbackDays <= 0) ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
        String cutoff = LocalDate.now(SEOUL).minusDays(days).format(YYYYMMDD);
        List<TradeOutcome> rows = repository.findByAlertDateGreaterThanEqual(cutoff);

        // 종목별로 묶어 1콜로 그 종목의 모든 진입일을 처리 — 이 묶음이 비용 절감의 핵심.
        Map<String, List<TradeOutcome>> byStock = new LinkedHashMap<>();
        int alreadyTagged = 0;
        for (TradeOutcome o : rows) {
            if (o.getEntryFrgnNtbyRatio() != null) { alreadyTagged++; continue; }   // 재실행 안전
            byStock.computeIfAbsent(o.getStockCode(), k -> new ArrayList<>()).add(o);
        }

        int queried = 0, failed = 0, tagged = 0, noData = 0;
        String oldest = null;
        for (Map.Entry<String, List<TradeOutcome>> e : byStock.entrySet()) {
            String stockCode = e.getKey();
            List<KisInvestorResponse.Daily> daily;
            try {
                KisInvestorResponse r = kisApiClient.fetchInvestorDaily(stockCode);
                queried++;
                daily = (r == null) ? null : r.output();
            } catch (Exception ex) {
                failed++;
                log.debug("수급 소급 조회 실패 [{}]: {}", stockCode, ex.getMessage());
                continue;                                   // 종목 실패 격리
            }
            if (daily == null || daily.isEmpty()) { noData += e.getValue().size(); continue; }

            for (TradeOutcome o : e.getValue()) {
                KisInvestorResponse.Flow f = KisInvestorResponse.priorTo(daily, o.getAlertDate());
                if (f == null) { noData++; continue; }       // 이력 창 밖(오래된 진입) — null 유지
                o.setEntryInvestorFlow(f.frgnRatioPct(), f.orgnRatioPct(), f.basisDate());
                tagged++;
                if (oldest == null || f.basisDate().compareTo(oldest) < 0) oldest = f.basisDate();
            }
        }
        log.info("수급 소급 태깅 완료 — 종목 {}개 조회(실패 {}), {}행 태깅, {}행 데이터없음, {}행 기태깅",
                queried, failed, tagged, noData, alreadyTagged);
        return new BacktagReport(queried, failed, tagged, noData, alreadyTagged, oldest);
    }
}
