package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.domain.RecommendationType;
import com.stockadvisor.service.SignalResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 B 밴드 — 일반주는 상한 1%, 인버스 ETF는 완화 상한(8%)으로 "이미 오른" 급등도 포착.
 * 하한(DIRECTION_DOWN 컷)은 인버스도 동일.
 */
class VolumeLeadingStrategyTest {

    private final VolumeLeadingStrategy strategy = new VolumeLeadingStrategy(props());

    private static SignalProperties props() {
        return new SignalProperties(
                20, 2.0, 1.5, 40.0, Duration.ofHours(1),
                5, 0.3, 1.5,
                0.0, 1.0, 8.0,           // volumeLeading: min=0, max=1, inverse-max=8
                3.0, 12.0, 40.0, true,
                2.0, 12.0, 40.0, true,
                20, 40.0, 0.0,
                1000, "09:00", "15:20", true);
    }

    private static SignalResult signal(double changeRate) {
        return new SignalResult(3.0, changeRate, 10_000, 1_000_000, true, false, false, 0, false, false, false);   // volumeSpike=true
    }

    private static StrategyContext ctx(double changeRate, boolean inverse) {
        return new StrategyContext("005930", signal(changeRate), 50, RecommendationType.HOLD, null, inverse, false);
    }

    private static StrategyContext inverseCtx() {
        return ctx(3.0, true);   // 인버스 +3% 급등(완화 밴드 내) — 지수 판정만 남는 상태
    }

    private static VolumeLeadingStrategy strategyForInverseTest() {
        return new VolumeLeadingStrategy(props());
    }

    @Test
    void 일반주_상한1퍼초과면_ALREADY_UP() {
        assertThat(strategy.rejectReason(ctx(3.0, false))).isEqualTo("ALREADY_UP");
    }

    @Test
    void 인버스는_완화상한으로_3퍼센트급등도_진입() {
        assertThat(strategy.rejectReason(ctx(3.0, true))).isNull();
    }

    @Test
    void 인버스도_완화상한8퍼_초과면_ALREADY_UP() {
        assertThat(strategy.rejectReason(ctx(9.0, true))).isEqualTo("ALREADY_UP");
    }

    @Test
    void 인버스도_하락중이면_DIRECTION_DOWN_동일() {
        assertThat(strategy.rejectReason(ctx(-0.5, true))).isEqualTo("DIRECTION_DOWN");
    }

    @org.junit.jupiter.api.Test
    void 인버스는_지수판정_거부시_IDX_사유로_거부() {
        // 2026-07-29: B 인버스 실현 4전4패(혼조일 휩쏘) — ETF 볼륨만 보고 지수를 안 물었던 탓.
        // I의 지수 판정을 위임받아, 지수 미약세(INDEX_NOT_WEAK)면 B도 진입 안 함.
        VolumeLeadingStrategy s = strategyForInverseTest();
        InverseIndexStrategy idx = org.mockito.Mockito.mock(InverseIndexStrategy.class);
        org.mockito.Mockito.when(idx.rejectReason(org.mockito.ArgumentMatchers.any())).thenReturn("INDEX_NOT_WEAK");
        s.setInverseIndexStrategy(idx);
        s.setInverseRequireIndex(true);
        org.assertj.core.api.Assertions.assertThat(s.rejectReason(inverseCtx())).isEqualTo("IDX_INDEX_NOT_WEAK");
    }

    @org.junit.jupiter.api.Test
    void 인버스_지수판정_통과시_기존_조건대로_진입() {
        VolumeLeadingStrategy s = strategyForInverseTest();
        InverseIndexStrategy idx = org.mockito.Mockito.mock(InverseIndexStrategy.class);
        org.mockito.Mockito.when(idx.rejectReason(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        s.setInverseIndexStrategy(idx);
        s.setInverseRequireIndex(true);
        org.assertj.core.api.Assertions.assertThat(s.rejectReason(inverseCtx())).isNull();
    }

    @org.junit.jupiter.api.Test
    void 일반주_약한_급증은_WEAK_VOLUME() {
        // 2026-07-30 실험: 하한 6배 설정 시 3배 급증(기존 통과 수준)은 보류 — 강한 급증만 취함
        VolumeLeadingStrategy s = new VolumeLeadingStrategy(props());
        s.setMinVolumeRatio(6.0);
        org.assertj.core.api.Assertions.assertThat(s.rejectReason(ctx(0.5, false))).isEqualTo("WEAK_VOLUME");
    }

    @org.junit.jupiter.api.Test
    void 인버스는_볼륨하한_미적용() {
        VolumeLeadingStrategy s = new VolumeLeadingStrategy(props());
        s.setMinVolumeRatio(6.0);
        InverseIndexStrategy idx2 = org.mockito.Mockito.mock(InverseIndexStrategy.class);
        org.mockito.Mockito.when(idx2.rejectReason(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        s.setInverseIndexStrategy(idx2);
        org.assertj.core.api.Assertions.assertThat(s.rejectReason(ctx(3.0, true))).isNull();
    }
}
