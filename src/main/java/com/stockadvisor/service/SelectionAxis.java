package com.stockadvisor.service;

/**
 * 종목 선정 축(<b>순수 정적</b> — DB 없이 테스트). 전부 <b>일봉만으로</b> 계산돼 DART 한도와 무관하다.
 *
 * <p>각 축은 진입 시점 기준 <b>과거 데이터만</b> 참조한다(look-ahead 없음). 값의 대소 방향은 축마다 다르므로
 * 호출측이 {@code LOW}/{@code HIGH}를 지정하고, 반대 방향이 자동으로 <b>내장 대조군</b>이 된다.</p>
 *
 * <p>⚠️ 축마다 <b>사전 근거</b>가 있는 것만 넣었다 — 가설 없이 축을 늘리면 다중검정 허수가 는다는 게
 * 이 시스템이 2026-08-21 발굴 세션에서 얻은 교훈이다(필터 통과 pocket 50개가 전부 아티팩트였다).</p>
 */
public enum SelectionAxis {

    /** 최근 1개월 수익률 — <b>단기 반전</b>(LOW) 가설의 표준 창. 한국 시장에서 특히 문서화돼 있다. */
    RET_1M,
    /** 최근 3개월 수익률 — 반전과 모멘텀의 경계 구간. */
    RET_3M,
    /** 최근 6개월 수익률 — 중기 모멘텀. */
    RET_6M,
    /** 12개월 중 <b>직전 1개월 제외</b> 수익률 — 문헌 표준 모멘텀(단기 반전 오염을 빼는 게 요점). */
    RET_12_1,
    /** 최근 6개월 일간수익률 표준편차(%) — <b>저변동성</b>(LOW) 가설. 최악 코호트 문제에도 직접 대응한다. */
    VOL_6M,
    /** 최근 1개월 평균거래량 ÷ 직전 6개월 평균거래량 — <b>관심도 변화</b>. 이 시스템 자체 증거("이미 관심받은 걸 사면 진다")에서 나온 유일한 내부 가설. */
    VOLUME_TREND,
    /** 현재가 ÷ 최근 52주 최고가 — 신고가 근접도(HIGH=신고가 부근). 모멘텀 변형. */
    HIGH_52W,
    /** 최근 1개월 평균 거래대금(원) — 규모·유동성 프록시. */
    TURNOVER;

    /**
     * @param idx   진입 인덱스(이 시점까지의 과거만 본다)
     * @param i1m   1개월 전 인덱스, {@code i3m}/{@code i6m}/{@code i12m} 동일
     * @return 축 값. 계산 불가면 null(그 종목은 그 코호트에서 제외된다)
     */
    public Double value(int[] close, int[] high, long[] volume,
                        int idx, int i1m, int i3m, int i6m, int i12m) {
        switch (this) {
            case RET_1M:
                return ret(close, i1m, idx);
            case RET_3M:
                return ret(close, i3m, idx);
            case RET_6M:
                return ret(close, i6m, idx);
            case RET_12_1:
                // 직전 1개월을 빼는 게 이 축의 정체 — 단기 반전 효과가 모멘텀 측정을 오염시키는 것을 막는다.
                return (i12m < 0 || i1m < 0 || i12m >= i1m) ? null : ret(close, i12m, i1m);
            case VOL_6M: {
                if (i6m < 0 || idx - i6m < 20) return null;
                double sum = 0, sum2 = 0;
                int n = 0;
                for (int t = i6m + 1; t <= idx; t++) {
                    if (close[t - 1] <= 0) continue;
                    double r = (double) (close[t] - close[t - 1]) / close[t - 1] * 100;
                    sum += r;
                    sum2 += r * r;
                    n++;
                }
                if (n < 20) return null;
                double mean = sum / n;
                double var = sum2 / n - mean * mean;
                return var <= 0 ? 0.0 : Math.sqrt(var);
            }
            case VOLUME_TREND: {
                if (i1m < 0 || i6m < 0 || i6m >= i1m || idx - i1m < 5) return null;
                double recent = meanVol(volume, i1m + 1, idx);
                double base = meanVol(volume, i6m, i1m);
                return (base <= 0) ? null : recent / base;
            }
            case HIGH_52W: {
                if (i12m < 0 || close[idx] <= 0) return null;
                long peak = 0;
                for (int t = i12m; t <= idx; t++) if (high[t] > peak) peak = high[t];
                return peak <= 0 ? null : (double) close[idx] / peak;
            }
            case TURNOVER: {
                if (i1m < 0 || idx - i1m < 5) return null;
                double sum = 0;
                int n = 0;
                for (int t = i1m + 1; t <= idx; t++) {
                    sum += (double) close[t] * volume[t];
                    n++;
                }
                return n == 0 ? null : sum / n;
            }
            default:
                return null;
        }
    }

    private static Double ret(int[] close, int from, int to) {
        if (from < 0 || to < 0 || from >= to || close[from] <= 0) return null;
        return (double) (close[to] - close[from]) / close[from] * 100;
    }

    private static double meanVol(long[] volume, int from, int to) {
        if (from < 0 || to <= from) return 0;
        double sum = 0;
        for (int t = from; t <= to; t++) sum += volume[t];
        return sum / (to - from + 1);
    }
}
