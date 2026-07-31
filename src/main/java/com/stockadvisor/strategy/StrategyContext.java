package com.stockadvisor.strategy;

import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;

/**
 * 전략 판단에 필요한 입력 묶음. 공시 1건 평가 시 1회 구성해 모든 전략이 공유한다.
 *
 * @param stockCode    종목코드
 * @param signal       시장 신호 원시 지표
 * @param recScore     추천 점수
 * @param recType      추천 의견(매수/중립/매도)
 * @param marketChange 진입 시점 해당 시장(KOSPI/KOSDAQ) 지수 당일 등락률(%). 조회 실패/시장미상이면 null.
 *                     전략D(지수상대)가 잔차(종목등락률−이값)를 계산하는 데 사용.
 * @param inverse      인버스 ETF 여부 — 하락장에 급등하는 특성상 전략B가 상한(횡보 밴드)을 완화해 "이미 오른" 인버스도 포착.
 * @param undervalued  업종 대비 저평가 여부 — PER 또는 PBR이 업종 중앙값 미만(SectorValuationService). 전략J(저평가 반등)가 사용. 판정불가 시 false.
 * @param entryTrend   진입 시점 "어제까지의 시장 국면"(BULL/NEUTRAL/BEAR, MA기반). 전략K(개장갭)가 갭업 추종 방향 필터에 사용. 미상이면 null.
 * @param indexMom30   진입 시점 해당 시장 지수 장중 흐름(최근 30분 모멘텀 %, MarketRegimeService.intradayFlow). ≥0=흐름↑/미만=흐름↓.
 *                     전략D(지수상대)가 "흐름↓ 스킵" 필터에 사용. 흐름 미산출(개장 ~30분·조회실패)·인버스면 null(필터 미적용, degrade open).
 */
public record StrategyContext(
        String stockCode,
        SignalResult signal,
        double recScore,
        RecommendationType recType,
        Double marketChange,
        boolean inverse,
        boolean undervalued,
        String entryTrend,
        Double indexMom30
) {
    /** 8-인자 호환 — indexMom30 null 기본. 기존 호출·테스트 무변경 유지용. */
    public StrategyContext(String stockCode, SignalResult signal, double recScore, RecommendationType recType,
                           Double marketChange, boolean inverse, boolean undervalued, String entryTrend) {
        this(stockCode, signal, recScore, recType, marketChange, inverse, undervalued, entryTrend, null);
    }

    /** 기존 7-인자 호환 — entryTrend·indexMom30 null 기본. 테스트·구 호출 무변경 유지용. */
    public StrategyContext(String stockCode, SignalResult signal, double recScore, RecommendationType recType,
                           Double marketChange, boolean inverse, boolean undervalued) {
        this(stockCode, signal, recScore, recType, marketChange, inverse, undervalued, null, null);
    }
}
