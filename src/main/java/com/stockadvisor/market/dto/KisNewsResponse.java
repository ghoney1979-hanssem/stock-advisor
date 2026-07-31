package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 한국투자증권 종합 시황/공시 제목 조회(FHKST01011800, 국내주식-141) 응답.
 * output 은 최신순 뉴스 제목 배열(작성일자/시각/제목). rt_cd="0" 정상.
 *
 * <p>용도: 진입 시점 뉴스 feature 태깅(측정 먼저) — 뉴스 존재/신선도가 수익과 상관 있는지
 * outcome-analysis 로 검증한 뒤에야 필터/전략 승격을 판단한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisNewsResponse(
        @JsonProperty("rt_cd") String returnCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<NewsItem> output
) {

    public boolean isSuccess() {
        return "0".equals(returnCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewsItem(
            @JsonProperty("data_dt") String date,               // 작성일자 YYYYMMDD
            @JsonProperty("data_tm") String time,               // 작성시간 HHMMSS
            @JsonProperty("hts_pbnt_titl_cntt") String title    // 뉴스/공시 제목
    ) {}

    /** 진입 태깅용 뉴스 feature — 최근 windowMinutes분 내 건수 + 최신 뉴스 경과분(당일 뉴스 없으면 null). */
    public record NewsFeature(int recentCount, Integer latestAgeMin) {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 뉴스 feature 계산(순수 함수 — KIS 없이 단위테스트). 파싱 불가 항목은 무시.
     * latestAgeMin 은 가장 최근 뉴스의 경과분(음수 방지 0 클램프), 유효 항목이 없으면 null.
     */
    public static NewsFeature features(List<NewsItem> items, ZonedDateTime now, int windowMinutes) {
        int recent = 0;
        Integer latestAge = null;
        if (items != null) {
            LocalDateTime nowLocal = now.toLocalDateTime();
            for (NewsItem n : items) {
                if (n == null || n.date() == null || n.time() == null) continue;
                LocalDateTime at;
                try {
                    at = LocalDateTime.parse(n.date() + normalizeTime(n.time()), DT);
                } catch (Exception e) {
                    continue;   // 형식 불량 항목 무시
                }
                long ageMin = java.time.Duration.between(at, nowLocal).toMinutes();
                if (ageMin < 0) ageMin = 0;
                if (ageMin <= windowMinutes) recent++;
                if (latestAge == null || ageMin < latestAge) latestAge = (int) ageMin;
            }
        }
        return new NewsFeature(recent, latestAge);
    }

    /**
     * 최근 {@code windowMinutes}분 내 뉴스 제목에 악재 키워드가 있으면 그 키워드 반환(없으면 null).
     * 반등 계열(C/D/J) "이유 있는 하락" 가드용 — 호재/중립 뉴스는 매치 안 되므로 통과(하락 중 호재 시나리오 보존).
     */
    public static String freshBadNews(List<NewsItem> items, ZonedDateTime now, int windowMinutes, List<String> keywords) {
        if (items == null || keywords == null || keywords.isEmpty()) return null;
        LocalDateTime nowLocal = now.toLocalDateTime();
        for (NewsItem n : items) {
            if (n == null || n.title() == null || n.date() == null || n.time() == null) continue;
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(n.date() + normalizeTime(n.time()), DT);
            } catch (Exception e) {
                continue;
            }
            long age = java.time.Duration.between(at, nowLocal).toMinutes();
            if (age < 0) age = 0;
            if (age > windowMinutes) continue;
            for (String kw : keywords) {
                String k = kw.trim();
                if (!k.isEmpty() && n.title().contains(k)) return k;
            }
        }
        return null;
    }

    private static String normalizeTime(String t) {
        String s = t.trim();
        return s.length() >= 6 ? s.substring(0, 6) : "0".repeat(6 - s.length()) + s;
    }
}
