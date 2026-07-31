package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 실전 매매 주문(본인 계좌 자기매매). 정책 게이트를 통과한 주문만 생성한다.
 *
 * <p>idempotencyKey 로 중복 주문을 차단한다(진입은 strategy:stockCode:date 1회).
 * 실제 KIS 주문 연동(OrderService) 전 단계에서는 DRY_RUN 상태로 기록돼 흐름을 검증한다.</p>
 *
 * <p>테이블명은 SQL 예약어 회피를 위해 {@code trade_order}.</p>
 */
@Entity
@Table(name = "trade_order", indexes = {
        // ⚠️ 전(全)유니크 금지: 거부/실패/취소분은 같은 멱등키로 재주문(추격)이 허용돼야 한다
        //    (PolicyGate 는 이미 status 인식). 활성분 유니크는 부분 유니크 인덱스(idx_order_idem_active,
        //    status NOT IN 종결실패)로 DB 마이그레이션에서 강제. 여기선 조회용 비유니크 인덱스만 둔다.
        @Index(name = "idx_order_idem", columnList = "idempotency_key"),
        @Index(name = "idx_order_date_side", columnList = "order_date, side"),
        @Index(name = "idx_order_open", columnList = "side, closed")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 낙관적 잠금(2026-07-22) — FillSync 청산확정 vs 청산점검 가격추적 저장의 동시성 레이스로
     * 청산이 스테일 저장에 덮여 유실된 실측(7/21 주문 692, +761원 유실) 방지. 늦은 쪽 저장이
     * OptimisticLockingFailure 로 실패 → 건별 try/catch 격리 후 다음 틱 재조회로 자연 회복.
     * ⚠️ 배포 前 수동 마이그레이션 필수: ALTER TABLE trade_order ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
     * (ddl-auto update 는 nullable 로 추가 → 기존 행 null 이면 isNew 오판(persist 시도)로 전 기능 마비 위험)
     */
    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version;

    /** 중복 주문 방지 키 (예: MEAN_REVERSION_C:005930:20260626). 유니크는 부분 인덱스로 상태조건부 강제(위 @Table 주석). */
    @Column(name = "idempotency_key", length = 60, nullable = false)
    private String idempotencyKey;

    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 18, nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 8, nullable = false)
    private TradingMode mode;

    /** 주문 수량(주) */
    @Column(name = "requested_qty", nullable = false)
    private long requestedQty;

    /** 주문 가격(원). 0 = 시장가. */
    @Column(name = "requested_price", nullable = false)
    private long requestedPrice;

    /** 주문 금액(원) = 수량 × 가격. 1주문 한도 검증·집계용. */
    @Column(name = "requested_krw", nullable = false)
    private long requestedKrw;

    /** KIS 주문번호(ODNO) — 접수 성공 시 기록. 체결조회·정정취소·reconcile 용. */
    @Column(name = "broker_order_no", length = 20)
    private String brokerOrderNo;

    /** KIS 거래소전송주문조직번호(KRX_FWDG_ORD_ORGNO) — 정정/취소 시 원주문 식별에 필요. */
    @Column(name = "broker_org_no", length = 10)
    private String brokerOrgNo;

    @Column(name = "filled_qty")
    private Long filledQty;

    @Column(name = "avg_fill_price")
    private Long avgFillPrice;

    /** 청산(매도) 시 확정 손익(원). 일일 손실 한도 집계용. */
    @Column(name = "realized_pnl")
    private Long realizedPnl;

    /** 포지션 종료 여부(매도 체결 완료). 동시 보유 종목 수 집계용. */
    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** 주문일 YYYYMMDD(KST) — 1일 한도·손익 집계용 */
    @Column(name = "order_date", length = 8, nullable = false)
    private String orderDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 업종(섹터) — 섹터 집중 한도 검증용. 진입 시 세팅(매도/수동은 null 가능) */
    @Column(name = "sector", length = 40)
    private String sector;

    /** 종목 시장(KOSPI/KOSDAQ/INVERSE) — 시장별 서킷브레이커(진입/청산) 판정용. 수동·구주문은 null(overall fallback). */
    @Column(name = "market", length = 10)
    private String market;

    /** 진입 후 최고가 — 트레일링 스탑 청산용(매 청산점검 시 갱신) */
    @Column(name = "peak_price")
    private Long peakPrice;

    /** 직전 점검 시 관측가 — 추세전환 청산(직전 대비 하락) 판정용 */
    @Column(name = "last_price")
    private Long lastPrice;

    /** 추세전환 청산 확인용 — 연속 하락 점검 횟수(N회 연속이어야 청산, 단일 틱 휩쏘 방지). 반등/보합이면 0으로 리셋. */
    @Column(name = "trend_down_count")
    @org.hibernate.annotations.ColumnDefault("0")
    private Integer trendDownCount;

    // 진입 시점에 락(lock)한 권장 청산 보유시간(분). PositionExitService가 이 값으로 청산(진입 후 갱신에 안 흔들림).
    // null이면(구주문·수동주문) live provider로 fallback.
    @Column(name = "hold_minutes")
    private Integer holdMinutes;

    /** 알림용 메모(예: 매도 청산 사유) — 비영속. 주문 생성~접수 알림 사이에서만 유효(승인 재로드 시 소실 무방, 매도는 동기 실행). */
    @jakarta.persistence.Transient
    private String note;

    public Order(String idempotencyKey, String strategy, String stockCode, OrderSide side,
                 long requestedQty, long requestedPrice, TradingMode mode, String orderDate) {
        this.idempotencyKey = idempotencyKey;
        this.strategy = strategy;
        this.stockCode = stockCode;
        this.side = side;
        this.requestedQty = requestedQty;
        this.requestedPrice = requestedPrice;
        this.requestedKrw = requestedQty * requestedPrice;
        this.mode = mode;
        this.orderDate = orderDate;
        this.status = mode == TradingMode.DRY_RUN ? OrderStatus.DRY_RUN : OrderStatus.NEW;
        this.closed = false;
        this.createdAt = Instant.now();
    }

    public void setSector(String sector) { this.sector = sector; }
    public void setMarket(String market) { this.market = market; }
    public void setNote(String note) { this.note = note; }

    /** 진입 시점 권장 청산 보유시간(분)을 락. */
    public void setHoldMinutes(Integer holdMinutes) { this.holdMinutes = holdMinutes; }

    /** 진입 후 최고가 갱신(트레일링용). */
    public void trackPeak(long price) { if (peakPrice == null || price > peakPrice) this.peakPrice = price; }
    public void setLastPrice(long price) { this.lastPrice = price; }
    public int getTrendDownCount() { return trendDownCount == null ? 0 : trendDownCount; }
    public void setTrendDownCount(int c) { this.trendDownCount = c; }

    public void markSubmitted() { this.status = OrderStatus.SUBMITTED; }

    public void markPendingApproval() { this.status = OrderStatus.PENDING_APPROVAL; }

    public void markDryRun() { this.status = OrderStatus.DRY_RUN; }

    public void markCancelled() { this.status = OrderStatus.CANCELLED; }

    public void markRejectedByApproval() { this.status = OrderStatus.REJECTED; }

    public void setBrokerOrderNo(String brokerOrderNo) { this.brokerOrderNo = brokerOrderNo; }

    public void setBrokerOrgNo(String brokerOrgNo) { this.brokerOrgNo = brokerOrgNo; }

    public void markFilled(long filledQty, long avgFillPrice) {
        this.status = OrderStatus.FILLED;
        this.filledQty = filledQty;
        this.avgFillPrice = avgFillPrice;
    }

    public void markPartiallyFilled(long filledQty, long avgFillPrice) {
        this.status = OrderStatus.PARTIALLY_FILLED;
        this.filledQty = filledQty;
        this.avgFillPrice = avgFillPrice;
    }

    public void markRejected() { this.status = OrderStatus.REJECTED; }

    public void markFailed() { this.status = OrderStatus.FAILED; }

    /** 부분매도 반영(2026-07-15 모베이스전자 사건) — 잔여 수량 차감 + 부분 실현손익 누적. 포지션은 미청산 유지 → 잔여분 재매도. */
    public void applyPartialSell(long soldQty, long partialPnl) {
        long held = (filledQty != null && filledQty > 0) ? filledQty : requestedQty;
        this.filledQty = Math.max(0, held - soldQty);
        this.realizedPnl = (this.realizedPnl == null ? 0 : this.realizedPnl) + partialPnl;
    }

    public void closePosition(long realizedPnl) {
        // 부분매도 누적분(applyPartialSell)이 있으면 합산 — 덮어쓰면 부분 손익이 유실된다.
        this.realizedPnl = (this.realizedPnl == null ? 0 : this.realizedPnl) + realizedPnl;
        this.closed = true;
    }

    /** reconcile 청산 — 실계좌에 없어 정리. 실현손익 미상(null 유지)으로 일일손익 집계 오염 방지. */
    public void markReconciledClosed() {
        this.closed = true;
    }
}
