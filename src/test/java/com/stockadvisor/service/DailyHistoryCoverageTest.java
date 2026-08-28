package com.stockadvisor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재적재 생략 판정(순수 정적) — 1,500콜을 매번 다시 쏘지 않게 하는 장치.
 *
 * <p>⚠️ 하한을 <b>느슨하게</b> 보는 게 설계의 요점이다. 신규 상장 종목은 요청 시작일보다 데이터가 늦게
 * 시작하는 게 정상이라, 하한까지 엄격히 요구하면 그런 종목은 <b>매 실행마다 영원히 재조회</b>된다.</p>
 */
class DailyHistoryCoverageTest {

    @Test
    @DisplayName("상단이 최신이고 하한을 덮으면 재조회를 생략한다")
    void 이미_덮인_종목은_생략() {
        assertThat(DailyHistoryBackfillService.covered(
                new String[]{"20160104", "20260827"}, "20160828", "20260827")).isTrue();
    }

    @Test
    @DisplayName("상단이 낡았으면(최신 거래일 누락) 갱신을 위해 재조회한다")
    void 상단이_낡으면_재조회() {
        assertThat(DailyHistoryBackfillService.covered(
                new String[]{"20160104", "20260820"}, "20160828", "20260827")).isFalse();
    }

    @Test
    @DisplayName("이미 가진 것보다 더 과거를 요구하면 재조회한다")
    void 더_과거를_요구하면_재조회() {
        assertThat(DailyHistoryBackfillService.covered(
                new String[]{"20200104", "20260827"}, "20160828", "20260827")).isFalse();
    }

    @Test
    @DisplayName("미적재 종목·결손 커버리지는 항상 조회 대상")
    void 미적재는_조회() {
        assertThat(DailyHistoryBackfillService.covered(null, "20160828", "20260827")).isFalse();
        assertThat(DailyHistoryBackfillService.covered(
                new String[]{null, "20260827"}, "20160828", "20260827")).isFalse();
    }
}
