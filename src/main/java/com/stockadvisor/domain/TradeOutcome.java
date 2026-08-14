package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * 전략별 "가상 매수" 성과 추적(섀도우 paper-trade). 각 (공시, 전략) 쌍마다 1건.
 * 알림(가정 매수) 시점 가격을 매수가로 잡고, 여러 horizon 가격을 수집해 수익률을 비교한다.
 *
 * <p>실제 매매가 아니라 정보 제공용 가정 추적이다(자본시장법 면책 동일 적용).</p>
 */
@Entity
@Table(name = "trade_outcome",
        indexes = {
                @Index(name = "idx_trade_outcome_completed", columnList = "completed"),
                @Index(name = "idx_trade_outcome_strategy_stock_date",
                        columnList = "strategy, stock_code, alert_date")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 전략 식별자 (MOMENTUM_A / VOLUME_LEADING_B / MEAN_REVERSION_C) */
    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    /** 근거 공시 id (DISCLOSURE 전략만 채움. MARKET_SCAN 전략은 null) */
    @Column(name = "disclosure_id")
    private Long disclosureId;

    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Column(name = "alert_time", nullable = false)
    private Instant alertTime;

    /** 가정 매수일 YYYYMMDD(KST) — 당일/익일 종가 매칭용 */
    @Column(name = "alert_date", length = 8, nullable = false)
    private String alertDate;

    /** 알림 시점 가격(가정 매수가) */
    @Column(name = "buy_price", nullable = false)
    private long buyPrice;

    // 수집 horizon 가격들 (미수집 시 null)
    @Column(name = "price_5min")
    private Long price5min;
    @Column(name = "price_10min")
    private Long price10min;
    @Column(name = "price_30min")
    private Long price30min;
    @Column(name = "price_close")        // 당일 종가
    private Long priceClose;
    @Column(name = "price_next_close")   // 익일(D+1) 종가
    private Long priceNextClose;
    @Column(name = "price_d2")           // D+2 거래일 종가 (휴장 제외, 진입일 이후 2번째 거래일)
    private Long priceD2;
    @Column(name = "price_d3")           // D+3 거래일 종가 (휴장 제외, 진입일 이후 3번째 거래일)
    private Long priceD3;

    /** A전략 +10분 후속 Discord 발송 완료 여부 */
    @Column(name = "follow_up_sent", nullable = false)
    private boolean followUpSent;

    /** 모든 horizon 수집(또는 만료) 완료 — 추적 종료 */
    @Column(name = "completed", nullable = false)
    private boolean completed;

    /** 보유 중 최고가(MFE)·최저가(MAE) — 매 분 갱신. TP/SL 수준 선정 근거. */
    @Column(name = "peak_price")   private Long peakPrice;
    @Column(name = "trough_price") private Long troughPrice;

    // ── 진입 시점 feature (승자/패자 분석용) ──
    @Column(name = "entry_change_rate")  private Double entryChangeRate;   // 당일 등락률(%)
    @Column(name = "entry_volume_ratio") private Double entryVolumeRatio;  // 거래량배수(시간보정)
    @Column(name = "entry_rec_score")    private Double entryRecScore;     // 추천 점수
    @Column(name = "entry_per")          private Double entryPer;          // PER
    @Column(name = "entry_pbr")          private Double entryPbr;          // PBR
    @Column(name = "entry_market", length = 10) private String entryMarket;     // KOSPI/KOSDAQ
    @Column(name = "entry_market_cap")   private Long entryMarketCap;      // 시가총액(억원)
    @Column(name = "entry_sector", length = 40) private String entrySector;     // 업종
    @Column(name = "entry_market_change") private Double entryMarketChange;     // 진입시점 해당시장 지수 등락률(%)
    @Column(name = "entry_market_trend", length = 10) private String entryMarketTrend; // 진입시점 시장 국면(BULL/NEUTRAL/BEAR, MA기반) — 국면조건부 성과게이트용
    @Column(name = "entry_slippage_pct") private Double entrySlippagePct;        // 진입시점 추정 왕복 슬리피지(%) — net 현실화용(ExecutionCostModel)
    // 진입 시점 지수(프록시 ETF) 장중 흐름 — "고점 대비 위치"가 아니라 "이전값 대비 방향/기울기"(오전↑→오후↓ 반전 포착). nullable(측정용).
    @Column(name = "entry_index_mom10") private Double entryIndexMom10;          // 지수 프록시 최근 ~10분 등락(%) — 근접 흐름
    @Column(name = "entry_index_mom30") private Double entryIndexMom30;          // 지수 프록시 최근 ~30분 등락(%) — 반시간 추세
    @Column(name = "entry_index_mom60") private Double entryIndexMom60;          // 지수 프록시 최근 ~60분 등락(%) — 1시간 전 대비 흐름
    // 셋업(setup) feature — 진입 종목의 상태. 이미 조회한 일봉에서 계산(추가 KIS 0). "어떤 종류 종목/셋업이 이기나" 분석용.
    @Column(name = "entry_atr_pct")      private Double entryAtrPct;             // ATR%(변동성)
    @Column(name = "entry_dist_high_pct") private Double entryDistHighPct;       // 직전 고가 대비 거리%(보통 ≤0)
    @Column(name = "entry_ret5d_pct")    private Double entryRet5dPct;           // 최근 5거래일 수익률%
    // 개장 갭 축(2026-08-14) — K는 정체성이 갭 전략인데 갭 크기가 저장되지 않아 "갭 3%와 갭 9% 중 뭐가 이기나"를
    // 사후에 물을 수 없었다(갭 상한 튜닝의 전제). SignalResult.gapPct는 이미 계산돼 있어 태깅 비용 0. forward-only.
    @Column(name = "entry_gap_pct")       private Double entryGapPct;            // 종목 당일 갭%((시가−전일종가)/전일종가)
    @Column(name = "entry_index_gap_pct") private Double entryIndexGapPct;       // 해당 시장 지수 당일 갭% — "지수 통째 갭업일"이었나
    // 시장 폭(breadth) — 진입 시점(직전 스캔) 상승종목 비율%. 지수(수준)·mom(흐름)과 다른 "참여 넓이" 축.
    @Column(name = "entry_breadth_pct")        private Double entryBreadthPct;

    /** 진입 시점 최근 1시간 뉴스 건수(KIS 종목뉴스) — 뉴스 촉매 여부 검증용(forward-only). */
    @Column(name = "entry_news_cnt_1h")
    private Integer entryNewsCnt1h;

    /** 진입 시점 최신 뉴스 경과분(당일 뉴스 없으면 null). */
    @Column(name = "entry_news_age_min")
    private Integer entryNewsAgeMin;

    /** 진입 시점 당일 체결강도(%, 매수체결/매도체결×100 — >100 매수 우위). 뉴스와 같은 측정-먼저 feature. */
    @Column(name = "entry_exec_strength")
    private Double entryExecStrength;      // 전체 워치리스트 상승비율%
    @Column(name = "entry_market_breadth_pct") private Double entryMarketBreadthPct; // 해당 시장(KOSPI/KOSDAQ) 상승비율%
    // 스윙 트레일링 검증(C) — 보유 중 고점(>매수) 대비 3/5/7% 되돌림 첫 도달가. 미도달=null(→익일종가 청산으로 간주).
    @Column(name = "trail3_price") private Long trail3Price;
    @Column(name = "trail5_price") private Long trail5Price;
    @Column(name = "trail7_price") private Long trail7Price;
    // ⚠️ boolean NOT NULL 컬럼은 @ColumnDefault 없으면 기존 행 있는 테이블에 ddl-auto ADD가 실패(Postgres) → 컬럼 미생성 → 전체 조회 깨짐. 반드시 default 지정.
    @Column(name = "control_sample", nullable = false) @ColumnDefault("false") private boolean control;  // 대조군(미진입) 표본 — 알림·주문 없이 수익률만 추적(필터 검증용)
    @Column(name = "reject_reason", length = 24) private String rejectReason;     // 대조군 탈락 사유(SCORE/DIRECTION_DOWN/DROP_RANGE/NO_REBOUND/NOT_FRESH/ALREADY_UP)

    public TradeOutcome(String strategy, Long disclosureId, String stockCode,
                        String alertDate, long buyPrice) {
        this.strategy = strategy;
        this.disclosureId = disclosureId;
        this.stockCode = stockCode;
        this.alertDate = alertDate;
        this.buyPrice = buyPrice;
        this.alertTime = Instant.now();
        this.followUpSent = false;
        this.completed = false;
    }

    /** 진입 시점 feature 기록 (분석용). */
    public void recordEntryFeatures(double changeRate, double volumeRatio, double recScore,
                                    double per, double pbr, String market, long marketCap,
                                    String sector, Double marketChange) {
        this.entryChangeRate = changeRate;
        this.entryVolumeRatio = volumeRatio;
        this.entryRecScore = recScore;
        this.entryPer = per;
        this.entryPbr = pbr;
        this.entryMarket = market;
        this.entryMarketCap = marketCap;
        this.entrySector = sector;
        this.entryMarketChange = marketChange;
    }

    /** 보유 중 최고/최저가 갱신 (매 분 현재가로 호출). */
    public void updatePeakTrough(long current) {
        if (peakPrice == null || current > peakPrice) peakPrice = current;
        if (troughPrice == null || current < troughPrice) troughPrice = current;
    }

    public void setPrice5min(long p) { this.price5min = p; }
    public void setPrice10min(long p) { this.price10min = p; }
    public void setPrice30min(long p) { this.price30min = p; }
    public void setEntryMarketTrend(String trend) { this.entryMarketTrend = trend; }
    public void setEntryMarket(String market) { this.entryMarket = market; }
    /** 대조군(미진입) 표본으로 표시 + 탈락 사유 기록. */
    public void markControl(String reason) { this.control = true; this.rejectReason = reason; }

    /**
     * 인버스 control→entry 승격 — 오전에 대조군으로 기록된 행을 현재 시점 진입으로 갱신(행 id 유지).
     * 오전 판정이 하루를 고정하지 않게, 오후에 조건 충족 시 진입으로 전환. 매수가·시각을 현재로 갱신.
     */
    public void promoteFromControl(long buyPrice) {
        this.control = false;
        this.rejectReason = null;
        this.buyPrice = buyPrice;
        this.alertTime = Instant.now();
    }
    public void setEntrySlippagePct(Double pct) { this.entrySlippagePct = pct; }
    public void setEntryIntradayFlow(Double mom10Pct, Double mom30Pct, Double mom60Pct) {
        this.entryIndexMom10 = mom10Pct;
        this.entryIndexMom30 = mom30Pct;
        this.entryIndexMom60 = mom60Pct;
    }
    /** 소급(backfill) — mom30/60만 채움(주어진 값만). mom10은 null 유지 = 라이브 태그와 구분자(라이브는 mom10도 채워짐). */
    public void backfillIntradayFlow(Double mom30Pct, Double mom60Pct) {
        if (mom30Pct != null) this.entryIndexMom30 = mom30Pct;
        if (mom60Pct != null) this.entryIndexMom60 = mom60Pct;
    }
    /**
     * 개장 갭 feature 기록 — 종목 갭%와 (해당 시장) 지수 갭%.
     * 0/null은 데이터 없음으로 보고 null 저장(K 외 전략은 대부분 갭이 0이라 분석에서 자동 제외).
     */
    public void setEntryGapFeatures(double gapPct, Double indexGapPct) {
        this.entryGapPct = gapPct == 0 ? null : gapPct;
        this.entryIndexGapPct = indexGapPct;
    }

    /** 셋업 feature(진입 종목 상태) 기록 — 0은 데이터 없음(null로 저장). */
    public void setEntrySetupFeatures(double atrPct, double distFromHighPct, double ret5dPct) {
        this.entryAtrPct = atrPct == 0 ? null : atrPct;
        this.entryDistHighPct = distFromHighPct == 0 ? null : distFromHighPct;
        this.entryRet5dPct = ret5dPct == 0 ? null : ret5dPct;
    }
    /** 진입 시점 시장 폭(직전 스캔 상승비율%). null=미집계. */
    /** 진입 시점 당일 체결강도(%) — 조회 실패 시 null 유지. */
    public void setEntryExecStrength(Double strength) {
        this.entryExecStrength = strength;
    }

    /** 진입 시점 뉴스 feature(측정 먼저) — 최근 1시간 뉴스 건수 + 최신 뉴스 경과분. 조회 실패 시 null 유지. */
    public void setEntryNews(Integer cnt1h, Integer ageMin) {
        this.entryNewsCnt1h = cnt1h;
        this.entryNewsAgeMin = ageMin;
    }

    public void setEntryBreadth(Double overallPct, Double marketPct) {
        this.entryBreadthPct = overallPct;
        this.entryMarketBreadthPct = marketPct;
    }

    /**
     * 스윙 트레일링 검증용 — 고점(&gt;매수) 대비 3/5/7% 되돌림 첫 도달가 기록(각 1회).
     * <b>이익 구간(peak&gt;매수)에서만 arm</b> — C의 초기 눌림(peak≈매수)을 조기 컷하지 않도록(엣지 보존). 라이브 트레일과 동일 규칙.
     */
    public void updateSwingTrail(long current) {
        if (current <= 0 || peakPrice == null || peakPrice <= buyPrice) return;
        if (trail3Price == null && current <= peakPrice * 0.97) trail3Price = current;
        if (trail5Price == null && current <= peakPrice * 0.95) trail5Price = current;
        if (trail7Price == null && current <= peakPrice * 0.93) trail7Price = current;
    }
    public void setPriceClose(long p) { this.priceClose = p; }
    public void setPriceNextClose(long p) { this.priceNextClose = p; }
    public void setPriceD2(long p) { this.priceD2 = p; }
    public void setPriceD3(long p) { this.priceD3 = p; }
    public void markFollowUpSent() { this.followUpSent = true; }
    public void markCompleted() { this.completed = true; }
}
