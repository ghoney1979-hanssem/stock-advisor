package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 구표본 자동 재필터 파서·평가기 — 임계 통과/미달·null feature 제외·AND 조건. */
class GateRefilterTest {

    private TradeOutcome outcome(double volRatio, Double ret5d) {
        TradeOutcome o = new TradeOutcome("SQUEEZE_BREAKOUT_H", null, "005930", "20260810", 10_000);
        o.recordEntryFeatures(0, volRatio, 50, 10, 1, "KOSPI", 5000, "화학", null);
        if (ret5d != null) o.setEntrySetupFeatures(0, 0, ret5d);   // ret5d는 setup feature(atr·distHigh·ret5d)
        return o;
    }

    @Test
    void 단일_조건_임계_통과_미달() {
        Map<String, GateRefilter> m = GateRefilter.parse("VOLUME_LEADING_B:volume_ratio>=6");
        GateRefilter rf = m.get("VOLUME_LEADING_B");
        assertThat(rf.test(outcome(10, null))).isTrue();    // 10 >= 6
        assertThat(rf.test(outcome(3, null))).isFalse();    // 3 < 6
    }

    @Test
    void AND_조건_모두_통과해야() {
        GateRefilter rf = GateRefilter.parse("H:volume_ratio>=6;ret5d<10").get("H");
        assertThat(rf.test(outcome(10, 5.0))).isTrue();     // 10>=6 & 5<10
        assertThat(rf.test(outcome(10, 15.0))).isFalse();   // ret5d 15<10 실패
        assertThat(rf.test(outcome(3, 5.0))).isFalse();     // volume 실패
    }

    @Test
    void 조건_feature가_null인_구표본은_제외() {
        GateRefilter rf = GateRefilter.parse("H:ret5d<10").get("H");
        assertThat(rf.test(outcome(10, null))).isFalse();   // ret5d 미태깅 → 확인불가 제외
    }

    @Test
    void 전역규칙은_모든_전략에_적용된다() {
        // 전략 무관 진입 필터(당일등락률·거래량배수·시총)를 11개 전략에 일일이 안 쓰기 위한 경로.
        Map<String, GateRefilter> m = GateRefilter.parse("*:volume_ratio<15");
        GateRefilter rf = GateRefilter.forStrategy(m, "OPENING_GAP_K");   // 전략별 규칙이 없어도 전역이 적용
        assertThat(rf.test(outcome(10, null))).isTrue();
        assertThat(rf.test(outcome(20, null))).isFalse();
    }

    @Test
    void 전역규칙과_전략별규칙은_AND로_합쳐진다() {
        Map<String, GateRefilter> m = GateRefilter.parse("*:volume_ratio<15|VOLUME_LEADING_B:volume_ratio>=6");
        GateRefilter b = GateRefilter.forStrategy(m, "VOLUME_LEADING_B");
        assertThat(b.test(outcome(10, null))).isTrue();     // 6 <= 10 < 15
        assertThat(b.test(outcome(3, null))).isFalse();     // 전략별(>=6) 실패
        assertThat(b.test(outcome(20, null))).isFalse();    // 전역(<15) 실패
        // 전략별 규칙이 없는 전략은 전역만
        assertThat(GateRefilter.forStrategy(m, "MOMENTUM_A").test(outcome(3, null))).isTrue();
    }

    @Test
    void 전역규칙이_없으면_전략별만_그것도_없으면_null() {
        Map<String, GateRefilter> m = GateRefilter.parse("VOLUME_LEADING_B:volume_ratio>=6");
        assertThat(GateRefilter.forStrategy(m, "VOLUME_LEADING_B")).isNotNull();
        assertThat(GateRefilter.forStrategy(m, "MOMENTUM_A")).isNull();   // 재필터 없음 = 구표본 그대로 채점
    }

    @Test
    void 다중_전략_파싱_잘못된_조건_무시() {
        Map<String, GateRefilter> m = GateRefilter.parse("A:volume_ratio>=2|B:unknown_feat>1|C:rec_score>=40");
        assertThat(m).containsKeys("A", "C");
        assertThat(m).doesNotContainKey("B");   // unknown feature → 조건 없음 → 전략 등록 안 됨
    }
}
