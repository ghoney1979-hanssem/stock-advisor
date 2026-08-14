package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 전 종목 유니버스 스냅샷 (Phase 1 수집, 2026-08-14).
 *
 * <p><b>왜 필요한가</b>: 기존 {@link TradeOutcome}은 진입분이든 대조군이든 전부
 * <b>"거래량 급증한 종목" 안에서만</b> 뽑힌다({@code StrategyEvaluator}의 볼륨 게이트 이전 훅이 breadth뿐이라
 * 미급증 종목은 어떤 흔적도 남기지 않음). 즉 40일간 "전략이 안 통한다"고 측정해 온 것은 실제로는
 * <b>"거래량 급증 모집단 안에서는 안 통한다"</b>였다. 그런데 그 전제 자체가 반증되고 있다 —
 * 거래량배수 ≥15 pocket net −1.63%(n=141), B의 WEAK_VOLUME 대조군 +0.33% &gt; 진입분 −0.50%.</p>
 *
 * <p><b>무엇을 푸는가</b>: 승자만 모아 공통점을 찾으면 P(feature|승자)를 얻는데, 매매에 필요한 건
 * P(승자|feature)다. 후자는 <b>분모(전 종목)</b> 없이는 계산할 수 없다 — 승자의 80%가 거래량 급증이어도
 * 전체의 78%가 그렇다면 무가치. 이 테이블이 그 분모다. 정해진 시각마다 워치리스트 전 종목의
 * <b>진입 가능 시점 feature</b>와 <b>사후 실현 수익</b>을 한 줄로 남겨, feature 구간별 승률·평균수익을
 * 전체 base rate 대비 lift로 비교할 수 있게 한다(cross-sectional factor research).</p>
 *
 * <p><b>비용</b>: 수집 지점이 breadth 훅과 동일해 이미 조회한 일봉에서 전부 계산 → <b>추가 KIS 호출 0</b>.
 * ⚠️ 단 같은 이유로 quote 기반 feature(PER/PBR/시총/업종)는 <b>담지 않는다</b> — 볼륨 게이트 이후에야
 * 조회되므로 전 종목분을 받으려면 스냅샷당 ~1,500콜이 추가된다. v1은 일봉 기반 feature로 한정.</p>
 *
 * <p>⚠️ 사후 타깃은 <b>이후 전수 스캔이 같은 종목을 다시 만날 때</b> 채운다(추가 KIS 0). 스캔 주기가
 * 12분이라 +90분 타깃의 실제 경과는 90~102분 — 항상 목표 이상인 <b>일관된 방향의 근사</b>라
 * 횡단면 비교에는 무해하나, 절대 수익 해석 시 유의.</p>
 */
@Entity
@Table(name = "universe_snapshot", indexes = {
        @Index(name = "idx_universe_snap_date_time", columnList = "snap_date,snap_time"),
        @Index(name = "idx_universe_snap_code_pending", columnList = "stock_code,snap_date")
})
public class UniverseSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snap_date", nullable = false, length = 8)
    private String snapDate;              // yyyyMMdd

    /** 스냅샷 버킷 라벨(예 "09:30") — 분석에서 시간대 슬라이스/풀링의 키. */
    @Column(name = "snap_time", nullable = false, length = 5)
    private String snapTime;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;           // 실제 기록 시각(버킷 시각과 최대 window 분 차이)

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "market", length = 10)
    private String market;                // KOSPI/KOSDAQ (Company 룩업, 미상이면 null)

    // ── 스냅샷 시점 feature (전부 진입 가능 시점 = 사전 관측치) ──────────
    @Column(name = "price") private long price;                        // 스냅샷 시점 현재가
    @Column(name = "change_rate") private double changeRate;           // 당일 등락률%
    @Column(name = "gap_pct") private double gapPct;                   // 개장갭%
    @Column(name = "volume_ratio") private double volumeRatio;         // 시간보정 거래량 배수
    @Column(name = "volume_spike") private boolean volumeSpike;        // 기존 볼륨 게이트 통과 여부(모집단 비교용)
    @Column(name = "atr_pct") private double atrPct;                   // 변동성
    @Column(name = "dist_high_pct") private double distHighPct;        // 직전 20일 고가 대비 거리%
    @Column(name = "ret5d_pct") private double ret5dPct;               // 최근 5거래일 수익률%
    @Column(name = "ma_dist_pct") private double maDistPct;            // MA20 이격%
    @Column(name = "ma_cross_up") private boolean maCrossUp;           // 이벤트 플래그
    @Column(name = "rsi_cross_up") private boolean rsiCrossUp;
    @Column(name = "squeeze_breakout") private boolean squeezeBreakout;

    // ── 시장 컨텍스트(캐시 재사용, KIS 0) ──────────────────────────────
    @Column(name = "index_change") private Double indexChange;         // 해당 시장 지수 당일 등락률%
    @Column(name = "index_mom30") private Double indexMom30;           // 지수 장중 30분 모멘텀%
    @Column(name = "index_gap_pct") private Double indexGapPct;        // 지수 당일 갭%
    @Column(name = "breadth_pct") private Double breadthPct;           // 전체 상승비율%
    @Column(name = "market_breadth_pct") private Double marketBreadthPct;

    // ── 사후 타깃(이후 스캔이 채움) ────────────────────────────────────
    @Column(name = "price_90m") private Long price90m;                 // +90분(우리 실제 보유시간대) 근사가
    @Column(name = "price_close") private Long priceClose;             // 당일 종가 근사(마감 직전 마지막 스캔가)
    @Column(name = "price_next_close") private Long priceNextClose;    // 익일 종가 근사

    protected UniverseSnapshot() {
    }

    public UniverseSnapshot(String snapDate, String snapTime, String stockCode, String market, long price) {
        this.snapDate = snapDate;
        this.snapTime = snapTime;
        this.stockCode = stockCode;
        this.market = market;
        this.price = price;
        this.capturedAt = Instant.now();
    }

    /** 일봉 기반 feature 기록. */
    public void setFeatures(double changeRate, double gapPct, double volumeRatio, boolean volumeSpike,
                            double atrPct, double distHighPct, double ret5dPct, double maDistPct,
                            boolean maCrossUp, boolean rsiCrossUp, boolean squeezeBreakout) {
        this.changeRate = changeRate;
        this.gapPct = gapPct;
        this.volumeRatio = volumeRatio;
        this.volumeSpike = volumeSpike;
        this.atrPct = atrPct;
        this.distHighPct = distHighPct;
        this.ret5dPct = ret5dPct;
        this.maDistPct = maDistPct;
        this.maCrossUp = maCrossUp;
        this.rsiCrossUp = rsiCrossUp;
        this.squeezeBreakout = squeezeBreakout;
    }

    /** 시장 컨텍스트 기록(미산출이면 null 유지). */
    public void setMarketContext(Double indexChange, Double indexMom30, Double indexGapPct,
                                 Double breadthPct, Double marketBreadthPct) {
        this.indexChange = indexChange;
        this.indexMom30 = indexMom30;
        this.indexGapPct = indexGapPct;
        this.breadthPct = breadthPct;
        this.marketBreadthPct = marketBreadthPct;
    }

    public void setPrice90m(long p) { this.price90m = p; }
    public void setPriceClose(long p) { this.priceClose = p; }
    public void setPriceNextClose(long p) { this.priceNextClose = p; }

    public Long getId() { return id; }
    public String getSnapDate() { return snapDate; }
    public String getSnapTime() { return snapTime; }
    public Instant getCapturedAt() { return capturedAt; }
    public String getStockCode() { return stockCode; }
    public String getMarket() { return market; }
    public long getPrice() { return price; }
    public double getChangeRate() { return changeRate; }
    public double getGapPct() { return gapPct; }
    public double getVolumeRatio() { return volumeRatio; }
    public boolean isVolumeSpike() { return volumeSpike; }
    public double getAtrPct() { return atrPct; }
    public double getDistHighPct() { return distHighPct; }
    public double getRet5dPct() { return ret5dPct; }
    public double getMaDistPct() { return maDistPct; }
    public boolean isMaCrossUp() { return maCrossUp; }
    public boolean isRsiCrossUp() { return rsiCrossUp; }
    public boolean isSqueezeBreakout() { return squeezeBreakout; }
    public Double getIndexChange() { return indexChange; }
    public Double getIndexMom30() { return indexMom30; }
    public Double getIndexGapPct() { return indexGapPct; }
    public Double getBreadthPct() { return breadthPct; }
    public Double getMarketBreadthPct() { return marketBreadthPct; }
    public Long getPrice90m() { return price90m; }
    public Long getPriceClose() { return priceClose; }
    public Long getPriceNextClose() { return priceNextClose; }
}
