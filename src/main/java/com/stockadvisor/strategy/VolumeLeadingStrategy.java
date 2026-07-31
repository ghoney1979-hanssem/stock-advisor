package com.stockadvisor.strategy;

import com.stockadvisor.config.properties.SignalProperties;
import org.springframework.stereotype.Component;

/**
 * 전략 B — 거래량 선행형 (섀도우).
 * 거래량은 급증했는데 가격은 아직 횡보(minChange ~ maxChange%)인 종목에 진입. "돌파 前" 선취 가설.
 * ⚠️ 밴드는 비대칭 — 하락 중(등락률 &lt; minChange)은 분산(매도)일 수 있어 제외(상방 가설 유지).
 */
@Component
public class VolumeLeadingStrategy implements TradingStrategy {

    private final SignalProperties props;

    public VolumeLeadingStrategy(SignalProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "VOLUME_LEADING_B";
    }

    @Override
    public String label() {
        return "거래량 선행형 (B)";
    }

    @Override
    public boolean shouldEnter(StrategyContext ctx) {
        return rejectReason(ctx) == null;
    }

    // 인버스 지수 확인 위임(2026-07-29): B 인버스는 ETF 거래량만 보고 진입해 혼조일 휩쏘 반복
    // (실측 7/21~29 실현 4전 4패 평균 −0.31% — 지수가 실제로 무너지는지 안 물었던 탓).
    // I의 지수 판정(당일 밴드·mom 동시하락·낙폭상한·반등일 fade)을 그대로 통과해야 진입 — 로직 단일 소스.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InverseIndexStrategy inverseIndexStrategy;
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.volume-leading-inverse-require-index:true}")
    private boolean inverseRequireIndex = true;
    // B 전용 볼륨 하한 실험(2026-07-30): 3주 진단 — 약한 급증(2~6배)이 표본 87%인데 −1.8~−2.0%/승률 19~23%,
    // 강한 급증(6~15배)만 −0.59%/35%로 상대 우위. "거래량 선행" 가설은 확실한 급증에서만 작동 → 공통 문턱(2배)과
    // 별도로 B만 하한 상향. ⚠️ n=20 소표본·위기국면 데이터 기반 실험 — 탈락분(WEAK_VOLUME) 대조군으로 역검증,
    // 대조군이 진입분보다 좋으면 원복. 0=비활성(코드 기본 — prod는 env로 6.0 설정). 인버스 경로 미적용.
    @org.springframework.beans.factory.annotation.Value("${stockadvisor.signal.volume-leading-min-ratio:0}")
    private double minVolumeRatio = 0;
    void setMinVolumeRatio(double v) { this.minVolumeRatio = v; }   // 테스트용
    void setInverseIndexStrategy(InverseIndexStrategy s) { this.inverseIndexStrategy = s; }   // 테스트용
    void setInverseRequireIndex(boolean b) { this.inverseRequireIndex = b; }                   // 테스트용

    @Override
    public String rejectReason(StrategyContext ctx) {
        if (!ctx.signal().volumeSpike()) return "NO_VOLUME";
        if (!ctx.inverse() && minVolumeRatio > 0 && ctx.signal().volumeRatio() < minVolumeRatio) {
            return "WEAK_VOLUME";   // 약한 급증(2~minRatio배) — B 하한 실험, 대조군 추적
        }
        double change = ctx.signal().changeRate();
        if (change < props.volumeLeadingMinChange()) return "DIRECTION_DOWN";   // 하락 중(분산 회피) — 인버스도 동일(하락=시장상승이라 롱 안 함)
        // 인버스 ETF는 하락장에 이미 몇 % 급등한 채 스캔되므로 완화된 상한 적용(포착률↑). 일반주는 기존 횡보 상한.
        double maxChange = ctx.inverse() ? props.volumeLeadingInverseMaxChange() : props.volumeLeadingMaxChange();
        if (change > maxChange) return "ALREADY_UP";                            // 이미 급등(횡보 선취 실패)
        if (ctx.recScore() < props.minOpinionScore()) return "SCORE";
        if (ctx.inverse() && inverseRequireIndex && inverseIndexStrategy != null) {
            String idxReject = inverseIndexStrategy.rejectReason(ctx);
            // I 자체 비활성(DISABLED)은 위임 대상 아님 — 지수 판정 사유만 상속
            if (idxReject != null && !"DISABLED".equals(idxReject)) return "IDX_" + idxReject;
        }
        return null;
    }

    @Override
    public StrategyScope scope() {
        return StrategyScope.MARKET_SCAN;   // 공시 무관, 전 종목 스캔
    }

    @Override
    public boolean alerts() {
        return true;
    }
}
