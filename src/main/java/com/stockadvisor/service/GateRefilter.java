package com.stockadvisor.service;

import com.stockadvisor.domain.TradeOutcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 성과게이트 <b>구표본 자동 재필터</b> (2026-08-12, strategy-since의 정밀판).
 *
 * <p>전략에 <b>조이는 필터</b>(진입 제거)를 추가했을 때, 구표본을 전부 버리는 since 리셋 대신
 * <b>새 필터의 임계 조건을 구표본에 다시 적용</b>해 "새 로직이라면 여전히 진입했을 표본"만 남긴다.
 * → fail-closed 공백 없이 유효 구표본을 보존(넷 리셋의 낭비 제거). ⚠️ 단 조건 feature가 {@link TradeOutcome}에
 * <b>태깅(persisted)</b>돼 있어야 함 — 분봉 신선도(H-3 확인·F 신선도)처럼 저장 안 되는 transient는 재필터 불가(since 사용).</p>
 *
 * <p>설정 문법(env {@code TRADING_PERF_GATE_REFILTER}):
 * {@code STRATEGY:cond;cond|STRATEGY2:cond} — {@code |} 전략 구분, {@code :} 전략/조건 구분, {@code ;} 조건 AND.
 * cond = {@code feature OP value}, OP ∈ {@code >= <= > <}. 예:
 * {@code VOLUME_LEADING_B:volume_ratio>=6|SQUEEZE_BREAKOUT_H:exec_strength<200;ret5d<10}.
 * 조건 feature가 null인 구표본(태깅 이전)은 확인 불가 → 제외(보수적).</p>
 *
 * <p><b>전역 규칙 {@code *}</b>(2026-08-18): 전략 자리에 {@code *}를 쓰면 <b>모든 전략</b>에 적용된다.
 * 전략 무관 진입 필터(당일등락률·거래량배수·시총 상하한)를 추가했을 때 11개 전략을 일일이 나열하지 않아도 되고,
 * 무엇보다 <b>since 리셋의 fail-closed 공백 없이</b> 구표본을 새 임계로 재채점할 수 있다.
 * 전략별 규칙과 함께 지정하면 <b>둘 다 AND</b>로 적용된다. 예:
 * {@code *:change_rate<10;volume_ratio<15;market_cap>=1000|VOLUME_LEADING_B:volume_ratio>=6}.</p>
 */
public final class GateRefilter {

    /** 재필터 가능한 persisted feature — 이름 → 추출자. */
    private static final Map<String, Function<TradeOutcome, Double>> FEATURES = new HashMap<>();
    static {
        FEATURES.put("volume_ratio", TradeOutcome::getEntryVolumeRatio);
        FEATURES.put("change_rate", TradeOutcome::getEntryChangeRate);
        FEATURES.put("rec_score", TradeOutcome::getEntryRecScore);
        FEATURES.put("per", TradeOutcome::getEntryPer);
        FEATURES.put("pbr", TradeOutcome::getEntryPbr);
        FEATURES.put("atr", TradeOutcome::getEntryAtrPct);
        FEATURES.put("dist_high", TradeOutcome::getEntryDistHighPct);
        FEATURES.put("ret5d", TradeOutcome::getEntryRet5dPct);
        FEATURES.put("exec_strength", TradeOutcome::getEntryExecStrength);
        FEATURES.put("mom30", TradeOutcome::getEntryIndexMom30);
        FEATURES.put("breadth", TradeOutcome::getEntryBreadthPct);
        FEATURES.put("market_cap", o -> o.getEntryMarketCap() == null ? null : o.getEntryMarketCap().doubleValue());
    }

    private record Cond(Function<TradeOutcome, Double> feature, String op, double value) {
        boolean test(TradeOutcome o) {
            Double v = feature.apply(o);
            if (v == null) return false;   // 태깅 이전 구표본 → 확인 불가, 보수적 제외
            return switch (op) {
                case ">=" -> v >= value;
                case "<=" -> v <= value;
                case ">" -> v > value;
                case "<" -> v < value;
                default -> false;
            };
        }
    }

    private final List<Cond> conds;

    private GateRefilter(List<Cond> conds) { this.conds = conds; }

    /** 모든 조건(AND) 통과 여부. */
    public boolean test(TradeOutcome o) {
        for (Cond c : conds) if (!c.test(o)) return false;
        return true;
    }

    /** 전역 규칙 키 — 모든 전략에 AND로 적용. */
    public static final String ALL = "*";

    /**
     * 해당 전략에 적용할 술어 — 전역({@code *})과 전략별 규칙을 AND로 합친다. 둘 다 없으면 null(재필터 없음).
     * 호출측이 {@code map.get(strategy)}만 보면 전역 규칙을 놓치므로 항상 이 메서드를 쓸 것.
     */
    public static GateRefilter forStrategy(Map<String, GateRefilter> map, String strategy) {
        GateRefilter all = map.get(ALL);
        GateRefilter own = map.get(strategy);
        if (all == null) return own;
        if (own == null) return all;
        List<Cond> merged = new ArrayList<>(all.conds);
        merged.addAll(own.conds);
        return new GateRefilter(merged);
    }

    /** "STRATEGY:cond;cond|STRATEGY2:cond" → {STRATEGY: GateRefilter}. 파싱 실패 조건은 무시(안전). */
    public static Map<String, GateRefilter> parse(String csv) {
        Map<String, GateRefilter> map = new HashMap<>();
        if (csv == null || csv.isBlank()) return map;
        for (String part : csv.split("\\|")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String strategy = part.substring(0, colon).trim();
            String condStr = part.substring(colon + 1).trim();
            List<Cond> conds = new ArrayList<>();
            for (String c : condStr.split(";")) {
                Cond cond = parseCond(c.trim());
                if (cond != null) conds.add(cond);
            }
            if (!strategy.isBlank() && !conds.isEmpty()) map.put(strategy, new GateRefilter(conds));
        }
        return map;
    }

    private static Cond parseCond(String s) {
        for (String op : new String[]{">=", "<=", ">", "<"}) {   // 긴 연산자 먼저
            int i = s.indexOf(op);
            if (i > 0) {
                String feat = s.substring(0, i).trim();
                Function<TradeOutcome, Double> f = FEATURES.get(feat);
                if (f == null) return null;
                try {
                    return new Cond(f, op, Double.parseDouble(s.substring(i + op.length()).trim()));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
