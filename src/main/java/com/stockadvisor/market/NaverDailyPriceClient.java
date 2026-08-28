package com.stockadvisor.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 금융 일봉(수정주가) 조회 — <b>백테스트 히스토리 확보 전용</b>. 라이브 매매 경로는 종전대로 KIS를 쓴다.
 *
 * <p><b>왜 KIS가 아니라 여기인가</b>(2026-08-28 실측 비교):</p>
 * <ul>
 *   <li>KIS {@code inquire-daily-price}(FHKST01010400, 현재 {@link KisApiClient#fetchDailyPrices}) = <b>~30거래일</b>뿐.</li>
 *   <li>KIS {@code inquire-daily-itemchartprice}(FHKST03010100)는 기간 지정이 되지만 <b>1콜 100건</b> →
 *       10년이면 종목당 ~25콜 × 1,500종목 = 37,500콜, rateGate(8/s)로 <b>~78분</b> + 장중 라이브 호출과 경합.</li>
 *   <li>여기는 <b>1콜에 10년치(실측 2,613행/161KB)</b> — 종목당 1콜로 끝난다. 인증도 불필요.</li>
 * </ul>
 *
 * <p>실측 확인 사항: ① <b>수정주가 반영</b>(2017년 삼성전자 36,100원 = 50:1 분할 소급 조정)
 * ② 2000년까지 존재 ③ <b>상장폐지 종목도 폐지 시점까지</b> 남아 있음(000030: ~2019-02-12)
 * ④ 코스닥 신형 <b>영문 코드</b>(0001A0)도 조회됨 ⑤ 15콜/2초에 차단 없음.</p>
 *
 * <p>⚠️ 비공식 엔드포인트다. 포맷·가용성이 바뀔 수 있어 <b>일회성 연구 데이터 확보</b>에만 쓰고,
 * 실패는 조용히 빈 결과로 흘린다(매매 경로가 아니라 중단시킬 이유가 없다).</p>
 */
@Component
public class NaverDailyPriceClient {

    private static final Logger log = LoggerFactory.getLogger(NaverDailyPriceClient.class);

    /**
     * 응답 본문의 데이터 행. 헤더 행은 <b>작은따옴표</b>라(['날짜', ...]) 이 패턴에 안 걸린다 — 그게 헤더를 거르는 방식이다.
     * 7번째 필드(외국인소진율)는 종목에 따라 없을 수 있어 선택 그룹.
     */
    private static final Pattern ROW = Pattern.compile(
            "\\[\"(\\d{8})\",\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)(?:,\\s*(-?[\\d.]+))?");

    /** 일봉 1행. */
    public record Bar(String businessDate, long open, long high, long low, long close,
                      long volume, Double frgnHoldPct) {}

    private final RestClient restClient;

    public NaverDailyPriceClient() {
        // 공용 빌더(read timeout 5s)를 안 쓰는 이유: 10년치 페이로드(~161KB)라 여유가 필요하고,
        // 이 대량 잡의 타임아웃 설정이 라이브 API 클라이언트에 새어 나가면 안 된다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl("https://api.finance.naver.com")
                .requestFactory(factory)
                .build();
    }

    /**
     * 한 종목의 일봉을 <b>1콜</b>로 조회한다.
     *
     * @param maxDateInclusive 이 날짜(YYYYMMDD)까지만 채택 — <b>오늘 부분봉을 거르는 장치</b>.
     *                         장중 조회 시 오늘 행은 미완성 거래량이 담겨 오므로(실측 10:47에 삼성전자 579만주 vs 전일 1,683만주)
     *                         그대로 넣으면 백테스트가 존재하지 않던 봉을 본다.
     * @return 날짜 오름차순. 조회 실패·없는 종목이면 빈 리스트(예외 없음).
     */
    public List<Bar> fetchDaily(String stockCode, String startDate, String endDate, String maxDateInclusive) {
        try {
            byte[] body = restClient.get()
                    .uri(b -> b.path("/siseJson.naver")
                            .queryParam("symbol", stockCode)
                            .queryParam("requestType", "1")
                            .queryParam("startTime", startDate)
                            .queryParam("endTime", endDate)
                            .queryParam("timeframe", "day")
                            .build())
                    .retrieve()
                    .body(byte[].class);
            if (body == null) return List.of();
            // 본문은 EUC-KR이지만 한글은 헤더 행뿐이고 우리가 읽는 건 전부 ASCII(숫자·날짜)라
            // ISO-8859-1로 바이트 보존 디코딩하면 깨질 여지가 없다(EUC-KR 디코더 의존 제거).
            return parse(new String(body, StandardCharsets.ISO_8859_1), maxDateInclusive);
        } catch (Exception e) {
            log.warn("[일봉소급] 조회 실패 {} ({}~{}): {}", stockCode, startDate, endDate, e.toString());
            return List.of();
        }
    }

    /**
     * 응답 본문 파싱(순수 함수 — 네트워크 없이 테스트된다).
     *
     * <p>버리는 행 둘:</p>
     * <ul>
     *   <li><b>OHLC에 0이 섞인 행</b> — 상장폐지 종목의 마지막 행에 나타난다(실측 000030 20190212 시가 0).</li>
     *   <li><b>{@code maxDateInclusive} 초과 행</b> — 오늘 부분봉.</li>
     * </ul>
     */
    public static List<Bar> parse(String body, String maxDateInclusive) {
        List<Bar> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        Matcher m = ROW.matcher(body);
        while (m.find()) {
            String date = m.group(1);
            if (maxDateInclusive != null && date.compareTo(maxDateInclusive) > 0) continue;
            long open = Long.parseLong(m.group(2));
            long high = Long.parseLong(m.group(3));
            long low = Long.parseLong(m.group(4));
            long close = Long.parseLong(m.group(5));
            long volume = Long.parseLong(m.group(6));
            if (open <= 0 || high <= 0 || low <= 0 || close <= 0) continue;   // 폐지 잔행 등 이상치
            if (volume < 0) continue;
            Double frgn = null;
            if (m.group(7) != null) {
                try {
                    frgn = Double.parseDouble(m.group(7));
                } catch (NumberFormatException ignored) {
                    // 소진율은 부가 정보 — 파싱 실패해도 가격 행은 살린다.
                }
            }
            out.add(new Bar(date, open, high, low, close, volume, frgn));
        }
        out.sort((a, b) -> a.businessDate().compareTo(b.businessDate()));
        return out;
    }
}
