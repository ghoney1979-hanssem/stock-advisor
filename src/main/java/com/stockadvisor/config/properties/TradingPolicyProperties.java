package com.stockadvisor.config.properties;

import com.stockadvisor.domain.TradingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.List;

/**
 * 실전 매매 정책/안전장치 설정 (본인 계좌 자기매매).
 *
 * <p>기본값은 "꺼짐 + dry-run"으로, 명시적으로 켜기 전엔 실주문이 나가지 않는다.
 * 신호와 주문 사이의 {@link com.stockadvisor.service.PolicyGate}가 이 값으로 모든 진입을 검증한다.</p>
 *
 * @param enabled            마스터 스위치 — false면 모든 주문 차단(기본 false, 명시적 활성화 필요)
 * @param mode               DRY_RUN(주문 로깅만) | LIVE(실주문). 기본 DRY_RUN
 * @param maxOrderPct        1주문 금액 = 계좌 순자산 × 이 비율(%). 수량 버그 시에도 초과주문 거부(핵심 방어막).
 *                           <b>0 이하면 100/maxPositions 자동 산출</b>({@link #effectiveMaxOrderPct()}) — 소비처는 반드시 effective를 쓸 것.
 * @param maxOrdersPerDay    1일 최대 신규(매수) 주문 수. 0 이하면 무제한
 * @param dailyLossLimit     일일 손실 한도(원) — 당일 확정손실이 이 값 이상이면 신규진입 중단(킬)
 * @param maxPositions       최대 동시 보유 종목 수
 * @param sessionEnd         연속매매 종료(HH:mm) — 시간기반 청산이 이 시각을 넘기면 신규진입 금지
 * @param timeExitHoldMinutes 시간기반 청산 보유시간(분) — 진입시각 + 이 값 ≤ sessionEnd 일 때만 신규진입
 * @param manualConfirm      첫 실전 단계 수동 승인 게이트 사용 여부(true면 자동발사 안 함)
 * @param liveStrategies     LIVE 실주문을 허용할 전략명 화이트리스트("검증 안 된 전략 실전 금지").
 *                           DRY_RUN은 관찰용이라 무시(전 전략 기록), LIVE에서만 적용. 기본 빈 목록.
 * @param unfilledTimeoutMinutes 지정가 미체결 주문을 취소하기까지 대기(분). 경과 후 취소 → 다음 틱 재주문(가격 추격).
 * @param approvalTimeoutMinutes 수동 승인 대기 주문 만료(분). 경과 시 자동 거부(늦은 진입 방지).
 */
@ConfigurationProperties(prefix = "stockadvisor.trading")
public record TradingPolicyProperties(
        boolean enabled,
        TradingMode mode,
        double maxOrderPct,
        int maxOrdersPerDay,
        long dailyLossLimit,
        int maxPositions,
        String sessionEnd,
        int timeExitHoldMinutes,
        boolean manualConfirm,
        List<String> liveStrategies,
        int unfilledTimeoutMinutes,
        int approvalTimeoutMinutes,
        double dailyLossLimitPct   // 일일 손실 한도를 순자산 대비 %로(2026-07-24). >0이면 절대액(dailyLossLimit) 대신 사용, 0=절대액
) {

    /**
     * 종목당(1주문) 비중(%) — {@code maxOrderPct > 0}면 그 값(수동 고정), 0 이하면 <b>100 / maxPositions 자동 산출</b>
     * (최대 보유 종목 수를 바꾸면 종목당 비중이 자동 연동 — 총노출 100% 균등 분할). maxPositions도 무효면 10%.
     */
    public double effectiveMaxOrderPct() {
        if (maxOrderPct > 0) return maxOrderPct;
        return maxPositions > 0 ? 100.0 / maxPositions : 10.0;
    }

    /** 해당 전략이 LIVE 실주문 허용 목록에 있는지. */
    public boolean isLiveAllowed(String strategy) {
        return liveStrategies != null && liveStrategies.contains(strategy);
    }

    /** 연속매매 종료 시각 파싱(HH:mm). */
    public LocalTime sessionEndLocalTime() {
        return LocalTime.parse(sessionEnd);
    }
}
