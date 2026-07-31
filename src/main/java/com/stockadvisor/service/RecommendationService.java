package com.stockadvisor.service;

import com.stockadvisor.dart.DartFinancialService;
import com.stockadvisor.dart.FinancialSummary;
import com.stockadvisor.domain.Company;
import com.stockadvisor.domain.Recommendation;
import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.market.KisApiClient;
import com.stockadvisor.market.dto.KisQuoteResponse;
import com.stockadvisor.repository.CompanyRepository;
import com.stockadvisor.repository.RecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 투자 의견(추천) 산출 서비스.
 *
 * <p>밸류에이션(KIS 시세의 PER/PBR)과 재무 펀더멘털(DART 매출성장·영업이익률·부채비율)을
 * 가중 합산해 점수를 낸다. 재무데이터가 없는 종목은 밸류에이션만으로 평가한다.</p>
 *
 * <p>어디까지나 참고용 정보 제공을 위한 예시 휴리스틱이며 투자 권유가 아니다. (자본시장법 준수)</p>
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    // 밸류에이션 : 재무 펀더멘털 가중치 (재무데이터가 있을 때만 적용)
    private static final double VALUATION_WEIGHT = 0.5;
    private static final double FINANCIAL_WEIGHT = 0.5;

    private final KisApiClient kisApiClient;
    private final DartFinancialService dartFinancialService;
    private final CompanyRepository companyRepository;
    private final RecommendationRepository recommendationRepository;
    private final SectorValuationService sectorValuationService;

    // 밸류에이션 스코어 anchors(env 튜닝 가능). PER/PBR 각 0~50점, "good 이하 만점 → max 이상 floor" 완만 감점.
    private final double perGood, perMax, pbrGood, pbrMax, highFloor, lossScore;
    // 업종 상대평가 anchors — (종목값 ÷ 업종 중앙값) 비율 기준.
    private final double sectorGoodRatio, sectorMaxRatio;

    public RecommendationService(KisApiClient kisApiClient,
                                 DartFinancialService dartFinancialService,
                                 CompanyRepository companyRepository,
                                 RecommendationRepository recommendationRepository,
                                 SectorValuationService sectorValuationService,
                                 @Value("${stockadvisor.valuation.per-good:10}") double perGood,
                                 @Value("${stockadvisor.valuation.per-max:40}") double perMax,
                                 @Value("${stockadvisor.valuation.pbr-good:1.0}") double pbrGood,
                                 @Value("${stockadvisor.valuation.pbr-max:4.0}") double pbrMax,
                                 @Value("${stockadvisor.valuation.high-floor:10}") double highFloor,
                                 @Value("${stockadvisor.valuation.loss-score:15}") double lossScore,
                                 @Value("${stockadvisor.valuation.sector.good-ratio:0.8}") double sectorGoodRatio,
                                 @Value("${stockadvisor.valuation.sector.max-ratio:1.5}") double sectorMaxRatio) {
        this.kisApiClient = kisApiClient;
        this.dartFinancialService = dartFinancialService;
        this.companyRepository = companyRepository;
        this.recommendationRepository = recommendationRepository;
        this.sectorValuationService = sectorValuationService;
        this.perGood = perGood;
        this.perMax = perMax;
        this.pbrGood = pbrGood;
        this.pbrMax = pbrMax;
        this.highFloor = highFloor;
        this.lossScore = lossScore;
        this.sectorGoodRatio = sectorGoodRatio;
        this.sectorMaxRatio = sectorMaxRatio;
    }

    /**
     * 종목코드에 대한 투자 의견을 산출하고 이력으로 저장한다.
     */
    @Transactional
    public Recommendation recommend(String stockCode) {
        return recommendationRepository.save(computeRecommendation(stockCode));
    }

    /**
     * 추천을 산출하되 저장하지 않는다(전략 평가 등 빈번한 조회용 — 이력 테이블 비대화 방지).
     */
    public Recommendation computeRecommendation(String stockCode) {
        Company company = companyRepository.findById(stockCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "등록되지 않은 종목코드입니다: " + stockCode));

        // 1) 밸류에이션 점수 (KIS 시세)
        KisQuoteResponse.Output output = kisApiClient.fetchCurrentQuote(stockCode).output();
        double per = parseDouble(output.per());
        double pbr = parseDouble(output.pbr());
        double valuationScore = scoreByValuation(per, pbr, output.sectorName());

        // 2) 재무 펀더멘털 점수 (DART) — 없으면 밸류에이션만 사용
        Optional<FinancialSummary> financials =
                dartFinancialService.getLatestAnnualSummary(company.getCorpCode());

        double finalScore;
        String reason;
        if (financials.isPresent()) {
            FinancialSummary fs = financials.get();
            double financialScore = scoreByFinancials(fs);
            finalScore = VALUATION_WEIGHT * valuationScore + FINANCIAL_WEIGHT * financialScore;
            reason = buildReason(company, per, pbr, fs, valuationScore, financialScore);
        } else {
            finalScore = valuationScore;
            reason = String.format("%s(%s) 밸류에이션 기준 PER=%.2f, PBR=%.2f (재무데이터 미반영)",
                    company.getName(), company.getMarket(), per, pbr);
        }

        Recommendation recommendation = Recommendation.builder()
                .stockCode(stockCode)
                .recommendationType(classify(finalScore))
                .score(round2(finalScore))
                .reason(reason)
                .build();

        log.debug("추천 산출 완료 stockCode={} score={} (재무반영={})",
                stockCode, finalScore, financials.isPresent());
        return recommendation;
    }

    /**
     * 밸류에이션 점수 (0~100). PER·PBR 각 0~50점, "good 이하 만점 → max 이상 바닥(highFloor)"으로 완만 감점.
     * <p><b>업종 상대평가 우선</b>: 해당 업종 중앙값이 산출돼 있으면 (종목값 ÷ 업종 중앙값) 비율로 평가
     * (업종 평균보다 싸면 가점) — 테크/바이오 등 업종 고PER 편차 흡수. 업종 중앙값 없으면 절대기준 fallback.</p>
     * ⚠️ 구버전(40−PBR×20)은 PBR≥2면 0점이라 성장주가 BUY 불가였음 → highFloor로 고평가도 바닥점 유지,
     * 적자(PER/PBR≤0)는 불확실로 lossScore. anchors는 env stockadvisor.valuation.*.
     */
    private double scoreByValuation(double per, double pbr, String sector) {
        SectorValuationService.SectorStat st = sectorValuationService.statOf(sector);
        if (st != null) {   // 업종 상대평가
            double perScore = per <= 0 ? lossScore
                    : band(per / st.medianPer(), sectorGoodRatio, sectorMaxRatio, 50, highFloor);
            double pbrScore = pbr <= 0 ? lossScore
                    : band(pbr / st.medianPbr(), sectorGoodRatio, sectorMaxRatio, 50, highFloor);
            return Math.min(100, perScore + pbrScore);
        }
        // 절대기준 fallback (업종 중앙값 미산출/표본부족)
        double perScore = per <= 0 ? lossScore : band(per, perGood, perMax, 50, highFloor);
        double pbrScore = pbr <= 0 ? lossScore : band(pbr, pbrGood, pbrMax, 50, highFloor);
        return Math.min(100, perScore + pbrScore);
    }

    /** good 이하면 full, max 이상이면 floor, 사이는 선형 보간. (low 지표일수록 가점) */
    private static double band(double x, double good, double max, double full, double floor) {
        if (x <= good) return full;
        if (x >= max) return floor;
        return full + (x - good) / (max - good) * (floor - full);
    }

    /**
     * 재무 펀더멘털 점수 (0~100). 예시 휴리스틱:
     *  - 매출 성장률(최대 40점): 성장률 2%당 1점, 상한 40점
     *  - 영업이익률(최대 30점): 1%당 2점, 상한 30점
     *  - 부채 건전성(최대 30점): 부채비율 100% 이하 만점, 초과분 0.15점씩 감점
     */
    private double scoreByFinancials(FinancialSummary fs) {
        double growthScore = clamp(fs.revenueGrowthRate() / 2.0, 0, 40);
        double marginScore = clamp(fs.operatingMargin() * 2.0, 0, 30);
        double debtScore = clamp(30 - Math.max(0, fs.debtRatio() - 100) * 0.15, 0, 30);
        return clamp(growthScore + marginScore + debtScore, 0, 100);
    }

    private RecommendationType classify(double score) {
        if (score >= 70) return RecommendationType.BUY;
        if (score >= 40) return RecommendationType.HOLD;
        return RecommendationType.SELL;
    }

    private String buildReason(Company company, double per, double pbr,
                               FinancialSummary fs, double valuationScore, double financialScore) {
        return String.format(
                "%s(%s) | 밸류에이션 PER=%.2f PBR=%.2f → %.1f점 | "
                        + "재무[%s년 %s] 매출성장 %.1f%% 영업이익률 %.1f%% 부채비율 %.1f%% → %.1f점",
                company.getName(), company.getMarket(), per, pbr, valuationScore,
                fs.businessYear(), fs.fsDiv(),
                fs.revenueGrowthRate(), fs.operatingMargin(), fs.debtRatio(), financialScore);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
