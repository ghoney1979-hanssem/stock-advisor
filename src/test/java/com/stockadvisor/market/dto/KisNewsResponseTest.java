package com.stockadvisor.market.dto;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 뉴스 feature 계산(순수 함수) — 최근 1시간 건수·최신 경과분. 진입 태깅의 근거 값.
 */
class KisNewsResponseTest {

    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 7, 14, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

    private KisNewsResponse.NewsItem item(String date, String time) {
        return new KisNewsResponse.NewsItem(date, time, "제목");
    }

    @Test
    void 최근1시간_건수와_최신_경과분() {
        var f = KisNewsResponse.features(List.of(
                item("20260714", "095500"),   // 5분 전 (윈도 내)
                item("20260714", "091000"),   // 50분 전 (윈도 내)
                item("20260714", "083000"),   // 90분 전 (윈도 밖)
                item("20260713", "150000")    // 어제 (윈도 밖)
        ), NOW, 60);

        assertThat(f.recentCount()).isEqualTo(2);
        assertThat(f.latestAgeMin()).isEqualTo(5);
    }

    @Test
    void 뉴스없으면_건수0_경과분null() {
        var f = KisNewsResponse.features(List.of(), NOW, 60);
        assertThat(f.recentCount()).isZero();
        assertThat(f.latestAgeMin()).isNull();
    }

    @Test
    void 신선_악재키워드_뉴스면_키워드_반환() {
        // SK디앤디 사건 재현: 13분 전 "유상증자" 뉴스 → 반등 계열 진입 보류 근거
        var items = List.of(new KisNewsResponse.NewsItem("20260714", "094700",
                "BBB 회사채 한파에 PF 규제도 강화…SK디앤디 \"유상증자로 재무구조 개선\""));
        String kw = KisNewsResponse.freshBadNews(items, NOW, 60, List.of("유상증자", "감자", "구조조정"));
        assertThat(kw).isEqualTo("유상증자");
    }

    @Test
    void 호재_중립_뉴스는_통과() {
        var items = List.of(new KisNewsResponse.NewsItem("20260714", "095500", "대규모 수주 계약 체결"));
        assertThat(KisNewsResponse.freshBadNews(items, NOW, 60, List.of("유상증자", "감자"))).isNull();
    }

    @Test
    void 윈도_밖_악재뉴스는_무시() {
        var items = List.of(new KisNewsResponse.NewsItem("20260714", "070000", "유상증자 결정"));   // 3시간 전
        assertThat(KisNewsResponse.freshBadNews(items, NOW, 60, List.of("유상증자"))).isNull();
    }

    @Test
    void 형식불량_항목은_무시하고_계산() {
        var f = KisNewsResponse.features(List.of(
                item("bad", "time"),
                item(null, null),
                item("20260714", "093000")   // 30분 전
        ), NOW, 60);
        assertThat(f.recentCount()).isEqualTo(1);
        assertThat(f.latestAgeMin()).isEqualTo(30);
    }
}
