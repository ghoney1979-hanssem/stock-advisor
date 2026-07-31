package com.stockadvisor.service;

import com.stockadvisor.domain.Disclosure;
import com.stockadvisor.repository.DisclosureRepository;
import com.stockadvisor.repository.TradeOutcomeRepository;
import com.stockadvisor.strategy.StrategyScope;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 공시 1건을 공시 기반(DISCLOSURE) 전략으로 평가하는 진입점.
 * 실제 평가/기록/알림은 {@link StrategyEvaluator}(종목당 독립 트랜잭션)에 위임한다.
 */
@Component
public class DisclosureAlertProcessor {

    // 공시 기반 알림 전략 — 진입 완료 시 재평가 중단 판단 키
    private static final String MOMENTUM = "MOMENTUM_A";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StrategyEvaluator evaluator;
    private final DisclosureRepository disclosureRepository;
    private final TradeOutcomeRepository tradeOutcomeRepository;

    public DisclosureAlertProcessor(StrategyEvaluator evaluator,
                                    DisclosureRepository disclosureRepository,
                                    TradeOutcomeRepository tradeOutcomeRepository) {
        this.evaluator = evaluator;
        this.disclosureRepository = disclosureRepository;
        this.tradeOutcomeRepository = tradeOutcomeRepository;
    }

    /**
     * @return 실시간 알림 발송 건수
     */
    public int process(Long disclosureId) {
        Disclosure disclosure = disclosureRepository.findById(disclosureId).orElse(null);
        if (disclosure == null || disclosure.isNotified()) {
            return 0;
        }
        String reportName = disclosure.getReportName() == null ? "공시" : disclosure.getReportName();
        int alerts = evaluator.evaluateStock(
                disclosure.getStockCode(), disclosureId, reportName, StrategyScope.DISCLOSURE);

        // 공시 기반 전략(A)이 오늘 이 종목에 진입했으면 재평가 중단
        String today = ZonedDateTime.now(SEOUL).format(YYYYMMDD);
        if (tradeOutcomeRepository.existsByStrategyAndStockCodeAndAlertDate(
                MOMENTUM, disclosure.getStockCode(), today)) {
            disclosureRepository.markNotified(disclosureId);
        }
        return alerts;
    }
}
