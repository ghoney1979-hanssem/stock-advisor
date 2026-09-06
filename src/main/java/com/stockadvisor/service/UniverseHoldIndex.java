package com.stockadvisor.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * <b>유니버스 동일가중 단순보유 벤치마크</b>(순수 — DB 없이 단위테스트 가능).
 *
 * <p>키는 (진입일, k거래일)이고 값은 그날 유동성 필터를 통과한 <b>전 종목</b>을 동일가중으로 k거래일 들고 있었을 때의
 * 평균 등락률(%)이다. 멀티데이 청산 시뮬의 <b>반사실</b>로 쓴다.</p>
 *
 * <p>🔴 <b>왜 이게 필요한가</b>(2026-09-07 실측): {@code multiday-exit-comparison} 은 절대 net만 보고
 * "보유 D+15가 최고"(G +9.10 · F +6.23 · H +5.76)라고 권장했는데, 완주 코호트의 진입일이 반등 구간
 * (7/20~8/13)에 몰려 있어 <b>그 수치의 정체가 시장 드리프트</b>였다. 같은 진입일 분포로 유니버스를 그냥 들고
 * 있었으면 D+15가 +8% 수준이라, 10전략 중 <b>9개가 유니버스에 졌다</b>(B −7.79%p · E −5.53%p · F −3.59%p).
 * 유일한 양수였던 C(+1.24%p)조차 완주 176건 중 134건이 20260626 하루라, 그날을 빼면 <b>−3.20%p</b>로 뒤집힌다.
 * 즉 반사실 없이 이 엔드포인트의 {@code recommended} 를 따르면 <b>지수에 지는 규칙을 채택</b>하게 된다.</p>
 *
 * <p>⚠️ <b>보유기간을 함께 맞추는 것이 설계의 핵심</b>이다. 진입일만 맞추고 horizon을 고정하면
 * 트레일·MA이탈처럼 <b>경로마다 청산일이 다른</b> 방식에서 "짧게 들고 나온 것"을 "15일 들고 있던 시장"과
 * 비교하게 된다. 그래서 경로별 실제 청산 거래일(k)로 조회한다.</p>
 *
 * <p>⚠️ 유동성 필터(가격·거래대금)는 <b>진입일 시점에만</b> 적용한다 — 라이브 진입 판정과 같은 자리다.
 * 이 가드가 없으면 동전주 호가 튐이 벤치마크를 부풀린다(2026-08-28 실측: 미적용 시 연 49% 초과 →
 * 필터 적용 시 −0.45%).</p>
 *
 * <p>⚠️ 생존편향은 <b>양쪽에 공통</b>으로 걸린다 — 유니버스가 폐지 종목을 포함한 {@code daily_price} 전체라
 * 오히려 전략 쪽(오늘의 워치리스트)보다 덜 편향돼 있다. 즉 여기서 나온 초과수익은 <b>보수적</b>이다.</p>
 */
public record UniverseHoldIndex(Map<String, Map<Integer, Double>> byDateAndK, int rows) {

    /** 벤치마크를 못 만들었을 때(일봉 미적재·조회 실패) — 모든 조회가 empty. */
    public static UniverseHoldIndex unavailable() {
        return new UniverseHoldIndex(Map.of(), 0);
    }

    public boolean available() {
        return !byDateAndK.isEmpty();
    }

    /**
     * 저장소 행 {@code (base_date, k, ret_pct, n)} → 인덱스(순수).
     *
     * <p>{@code minStocks} 미만으로 집계된 (일자,k)는 버린다 — 표본 몇 종목짜리 평균은 벤치마크가 아니라 잡음이다.</p>
     */
    public static UniverseHoldIndex of(List<Object[]> rows, int minStocks) {
        Map<String, Map<Integer, Double>> m = new HashMap<>();
        int kept = 0;
        for (Object[] r : rows) {
            if (r == null || r.length < 4 || r[0] == null || r[1] == null || r[2] == null) continue;
            long n = r[3] == null ? 0 : ((Number) r[3]).longValue();
            if (n < minStocks) continue;
            m.computeIfAbsent((String) r[0], k -> new HashMap<>())
                    .put(((Number) r[1]).intValue(), ((Number) r[2]).doubleValue());
            kept++;
        }
        return new UniverseHoldIndex(m, kept);
    }

    /** (진입일, k거래일) 유니버스 단순보유 gross %. 없으면 empty → 그 표본은 초과수익 집계에서 빠진다. */
    public OptionalDouble hold(String entryDate, int k) {
        if (entryDate == null || k <= 0) return OptionalDouble.empty();
        Map<Integer, Double> byK = byDateAndK.get(entryDate);
        if (byK == null) return OptionalDouble.empty();
        Double v = byK.get(k);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /** 인덱스가 덮는 진입일(진단용). */
    public List<String> dates() {
        List<String> out = new ArrayList<>(byDateAndK.keySet());
        out.sort(null);
        return out;
    }
}
