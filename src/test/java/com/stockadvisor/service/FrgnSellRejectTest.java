package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략별 외인 순매도 하한 판정 — {@link StrategyEvaluator#frgnSellReject}.
 *
 * <p>사전등록 "D 외인순매수 (10/01)"이 표본 밖에서 PASS해 D만 직전일 외인 순매수 비중 ≤ −8.93 진입을 보류한다.
 * 컷이 음수라 {@code parseDoubleCaps}(양수 전용)가 아니라 {@code parseSignedDoubles}를 쓴다.</p>
 */
@DisplayName("전략별 외인 순매도 하한 판정")
class FrgnSellRejectTest {

    private final Map<String, Double> floors = StrategyEvaluator.parseSignedDoubles("INDEX_RELATIVE_D:-8.93");

    @Test
    void 음수_컷이_파싱된다() {
        assertThat(floors).containsEntry("INDEX_RELATIVE_D", -8.93);
    }

    @Test
    void 지정_전략은_컷_이하_순매도면_보류() {
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", -8.93, floors)).isTrue();
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", -20.0, floors)).isTrue();
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", -8.92, floors)).isFalse();
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", 5.0, floors)).isFalse();   // 순매수는 그대로 진입
    }

    @Test
    void 미지정_전략은_영향_없음() {
        assertThat(StrategyEvaluator.frgnSellReject("RSI_REVERSAL_G", -30.0, floors)).isFalse();
    }

    @Test
    void 수급_미상이나_빈설정_오타면_통과() {
        // 조회 실패·설정 오타로 매매를 막지 않는다(degrade open)
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", null, floors)).isFalse();
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", -20.0, StrategyEvaluator.parseSignedDoubles(""))).isFalse();
        assertThat(StrategyEvaluator.frgnSellReject("INDEX_RELATIVE_D", -20.0, StrategyEvaluator.parseSignedDoubles("INDEX_RELATIVE_D:abc"))).isFalse();
    }
}
