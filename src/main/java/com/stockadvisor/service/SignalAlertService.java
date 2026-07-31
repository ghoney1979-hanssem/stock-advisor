package com.stockadvisor.service;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.Disclosure;
import com.stockadvisor.repository.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 미통지 공시를 대상으로 신호를 평가하고, 조건 충족 시 Discord 알림을 보낸다.
 *
 * <p>알림 조건: (공시 발생) + (거래량 급증 & 상승추이 신호) + (추천 점수 ≥ 중립 기준).
 * 실제 처리는 공시별 독립 트랜잭션({@link DisclosureAlertProcessor})으로 위임해
 * 한 종목 실패가 배치 전체를 롤백시키지 않도록 한다.</p>
 */
@Service
public class SignalAlertService {

    private static final Logger log = LoggerFactory.getLogger(SignalAlertService.class);

    private final DisclosureRepository disclosureRepository;
    private final DisclosureAlertProcessor processor;
    private final SignalProperties properties;

    public SignalAlertService(DisclosureRepository disclosureRepository,
                              DisclosureAlertProcessor processor,
                              SignalProperties properties) {
        this.disclosureRepository = disclosureRepository;
        this.processor = processor;
        this.properties = properties;
    }

    /**
     * 관찰 유효기간 내 미통지 공시를 평가하고 조건 충족 종목에 알림을 보낸다.
     *
     * @return 발송한 알림 건수
     */
    public int scanAndAlert() {
        Instant threshold = Instant.now().minus(properties.observationWindow());
        List<Disclosure> targets = disclosureRepository.findByNotifiedFalseAndDetectedAtAfter(threshold);
        if (targets.isEmpty()) {
            return 0;
        }

        int alerted = 0;
        for (Disclosure disclosure : targets) {
            try {
                alerted += processor.process(disclosure.getId());
            } catch (Exception ex) {
                // 한 종목 실패가 전체 배치를 막지 않도록 격리 (독립 트랜잭션이라 롤백도 해당 건만)
                log.warn("신호 평가 실패 stockCode={}: {}", disclosure.getStockCode(), ex.getMessage());
            }
        }
        if (alerted > 0) {
            log.info("신호 평가 완료: {}건 알림 발송 (평가 대상 {}건)", alerted, targets.size());
        }
        return alerted;
    }
}
