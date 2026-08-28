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

/**
 * 일봉 히스토리(수정주가) — <b>멀티데이 전략 백테스트용</b> 장기 가격 저장소.
 *
 * <p>이 테이블이 필요한 이유는 KIS 일봉({@code inquire-daily-price}, FHKST01010400)이 <b>~30거래일</b>뿐이라
 * 그동안 이 시스템이 백테스트를 아예 못 했기 때문이다(인트라데이는 과거 분봉 부재라 포워드 섀도우가 유일한 검증
 * 수단이었다). 반면 <b>멀티데이 전략은 일봉만으로 완전히 재현</b>되므로, 장기 일봉만 확보하면 진짜 백테스트가 열린다.</p>
 *
 * <p>⚠️ <b>수정주가</b>다(액면분할·무상증자 소급 조정). 백테스트 연속성엔 이게 맞지만, 과거 시점의 실제 호가·틱
 * 사이즈와는 다르다 — 슬리피지 추정을 이 값으로 하면 미세하게 어긋난다(멀티데이 스케일에선 무시 가능).</p>
 *
 * <p>⚠️ 이 테이블은 <b>연구용</b>이다. 라이브 매매 경로는 종전대로 KIS를 쓴다(소스 이원화가 의도).</p>
 */
@Entity
@Table(name = "daily_price", indexes = {
        @Index(name = "idx_daily_price_code_date", columnList = "stock_code, business_date", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 종목코드. ⚠️ 코스닥 신형 <b>영문 코드</b>(예: 0001A0)가 있어 length 6으로는 부족할 수 있어 10으로 둔다. */
    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    /** 거래일(YYYYMMDD). */
    @Column(name = "business_date", length = 8, nullable = false)
    private String businessDate;

    @Column(name = "open_price", nullable = false)
    private long openPrice;

    @Column(name = "high_price", nullable = false)
    private long highPrice;

    @Column(name = "low_price", nullable = false)
    private long lowPrice;

    @Column(name = "close_price", nullable = false)
    private long closePrice;

    @Column(name = "volume", nullable = false)
    private long volume;

    /** 외국인 소진율(%). 소스가 안 주는 종목(ETF 등)이 있어 nullable. */
    @Column(name = "frgn_hold_pct")
    private Double frgnHoldPct;

    public DailyPrice(String stockCode, String businessDate, long openPrice, long highPrice,
                      long lowPrice, long closePrice, long volume, Double frgnHoldPct) {
        this.stockCode = stockCode;
        this.businessDate = businessDate;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
        this.frgnHoldPct = frgnHoldPct;
    }
}
