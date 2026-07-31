package com.stockadvisor.common;

/**
 * 투자권유 면책조항 (자본시장법 준수).
 * 본 시스템이 제공하는 모든 추천 결과에는 반드시 면책조항이 동반되어야 한다.
 */
public final class Disclaimer {

    /** API 응답 등 공간이 충분한 곳에 포함되는 전체 면책조항 문구. */
    public static final String INVESTMENT_DISCLAIMER =
            "본 정보는 투자 참고용이며 특정 종목의 매매를 권유하는 것이 아닙니다. " +
            "투자에 대한 최종 판단과 책임은 투자자 본인에게 있으며, " +
            "과거의 수익률이 미래의 수익을 보장하지 않습니다. (자본시장과 금융투자업에 관한 법률 준수)";

    /** Discord 알림 등에 사용하는 한 줄 축약 면책조항. */
    public static final String SHORT = "⚠️ 투자 참고용이며 투자권유가 아닙니다.";

    private Disclaimer() {
    }
}