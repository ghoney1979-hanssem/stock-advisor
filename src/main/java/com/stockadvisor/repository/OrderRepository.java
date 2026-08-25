package com.stockadvisor.repository;

import com.stockadvisor.domain.Order;
import com.stockadvisor.domain.OrderSide;
import com.stockadvisor.domain.OrderStatus;
import com.stockadvisor.domain.TradingMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 체결조회 대상 — 특정 모드·상태의 주문 (LIVE 미체결/부분체결 보정용). */
    List<Order> findByModeAndStatusIn(TradingMode mode, Collection<OrderStatus> statuses);

    /** 특정 모드·방향의 청산 완료 주문 (집행품질 분석: 실현손익 대조용). */
    List<Order> findByModeAndSideAndClosed(TradingMode mode, OrderSide side, boolean closed);

    /** 특정 모드·방향의 일자 이후 주문 (일일 실매매 리포트용). */
    List<Order> findByModeAndSideAndOrderDateGreaterThanEqual(TradingMode mode, OrderSide side, String orderDate);

    /** 특정 상태의 주문 (승인 대기 목록/만료 처리용). */
    List<Order> findByStatus(OrderStatus status);

    /** 중복 주문 방지(멱등성). 취소/거부/실패분은 제외 → 재주문(추격) 허용. */
    boolean existsByIdempotencyKeyAndStatusNotIn(String idempotencyKey, Collection<OrderStatus> statuses);

    /** 당일 특정 방향 주문 수 (1일 주문 한도 검증용). */
    long countByOrderDateAndSide(String orderDate, OrderSide side);

    /**
     * 현재 동시 보유 종목 수 = 미청산 매수 포지션 (최대 보유 한도 검증용).
     * ⚠️ REJECTED/FAILED/CANCELLED(체결된 적 없는 죽은 주문)는 제외 — closed=false여도 포지션 아님.
     * (미포함 시 실패·거부 주문이 한도를 채워 실거래를 막던 버그 — 실측 수정.)
     */
    @Query("select count(o) from Order o where o.side = com.stockadvisor.domain.OrderSide.BUY "
            + "and o.closed = false and o.status not in ("
            + "com.stockadvisor.domain.OrderStatus.REJECTED, "
            + "com.stockadvisor.domain.OrderStatus.FAILED, "
            + "com.stockadvisor.domain.OrderStatus.CANCELLED)")
    long countOpenPositions();

    /** 당일 확정 손익 합계(원). 손익 없으면 0. (일일 손실 한도 검증용) */
    @Query("select coalesce(sum(o.realizedPnl), 0) from Order o where o.orderDate = :orderDate")
    long sumRealizedPnlByDate(@Param("orderDate") String orderDate);

    /** 특정 섹터의 미청산 매수 포지션 수 (섹터 집중 한도 검증용). REJECTED/FAILED/CANCELLED 제외. */
    @Query("select count(o) from Order o where o.side = com.stockadvisor.domain.OrderSide.BUY "
            + "and o.closed = false and o.sector = :sector and o.status not in ("
            + "com.stockadvisor.domain.OrderStatus.REJECTED, "
            + "com.stockadvisor.domain.OrderStatus.FAILED, "
            + "com.stockadvisor.domain.OrderStatus.CANCELLED)")
    long countOpenPositionsBySector(@Param("sector") String sector);

    /**
     * 특정 종목의 활성(미청산) 매수 포지션 수 — 승인대기/접수/부분·전량체결 포함, 종료(취소·거부·실패)는 제외.
     * 크로스전략 같은-종목 중복 진입 방지용(한 종목에 여러 전략이 스태킹되는 것 차단 → 종목당 실포지션 1개).
     */
    @Query("select count(o) from Order o where o.side = com.stockadvisor.domain.OrderSide.BUY "
            + "and o.closed = false and o.stockCode = :stockCode "
            + "and o.status in (com.stockadvisor.domain.OrderStatus.PENDING_APPROVAL, "
            + "com.stockadvisor.domain.OrderStatus.NEW, "
            + "com.stockadvisor.domain.OrderStatus.DRY_RUN, "
            + "com.stockadvisor.domain.OrderStatus.SUBMITTED, "
            + "com.stockadvisor.domain.OrderStatus.PARTIALLY_FILLED, "
            + "com.stockadvisor.domain.OrderStatus.FILLED)")
    long countActivePositionsByStockCode(@Param("stockCode") String stockCode);

    /**
     * 특정 <b>전략</b>의 활성(미청산) 매수 포지션 수 — 전략별 포지션 상한 검증용(2026-08-25).
     * 상태 조건은 {@link #countActivePositionsByStockCode}와 동일(승인대기/접수/체결 포함, 죽은 주문 제외).
     */
    @Query("select count(o) from Order o where o.side = com.stockadvisor.domain.OrderSide.BUY "
            + "and o.closed = false and o.strategy = :strategy "
            + "and o.status in (com.stockadvisor.domain.OrderStatus.PENDING_APPROVAL, "
            + "com.stockadvisor.domain.OrderStatus.NEW, "
            + "com.stockadvisor.domain.OrderStatus.DRY_RUN, "
            + "com.stockadvisor.domain.OrderStatus.SUBMITTED, "
            + "com.stockadvisor.domain.OrderStatus.PARTIALLY_FILLED, "
            + "com.stockadvisor.domain.OrderStatus.FILLED)")
    long countActivePositionsByStrategy(@Param("strategy") String strategy);

    /** 미청산 매수 포지션 (청산 대상). REJECTED/FAILED/CANCELLED 제외. */
    @Query("select o from Order o where o.side = com.stockadvisor.domain.OrderSide.BUY and o.closed = false "
            + "and o.status in (com.stockadvisor.domain.OrderStatus.DRY_RUN, "
            + "com.stockadvisor.domain.OrderStatus.SUBMITTED, "
            + "com.stockadvisor.domain.OrderStatus.PARTIALLY_FILLED, "
            + "com.stockadvisor.domain.OrderStatus.FILLED)")
    List<Order> findOpenBuyPositions();
}
