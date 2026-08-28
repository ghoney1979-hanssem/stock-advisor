package com.stockadvisor.dart;

import com.stockadvisor.common.ExternalApiException;
import com.stockadvisor.config.RedisCacheConfig;
import com.stockadvisor.config.properties.DartProperties;
import com.stockadvisor.dart.dto.DartDisclosureResponse;
import com.stockadvisor.dart.dto.DartFinancialResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * DART(금융감독원 전자공시시스템) OpenAPI 클라이언트.
 * 상장기업의 주요 재무계정을 조회한다. 응답은 Redis 에 1시간 캐싱된다.
 */
@Component
public class DartApiClient {

    private static final Logger log = LoggerFactory.getLogger(DartApiClient.class);

    private final RestClient restClient;
    private final DartProperties properties;

    public DartApiClient(RestClient.Builder builder, DartProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    /**
     * 단일회사 주요계정 조회 (fnlttSinglAcnt).
     *
     * @param corpCode    DART 고유 기업코드(8자리)
     * @param businessYear 사업연도 (예: "2024")
     * @param reportCode  보고서 코드 (11011=사업보고서, 11012=반기, 11013/11014=분기)
     */
    @Cacheable(cacheNames = RedisCacheConfig.DART_FINANCIALS,
            key = "#corpCode + ':' + #businessYear + ':' + #reportCode")
    public DartFinancialResponse fetchSingleCompanyFinancials(
            String corpCode, String businessYear, String reportCode) {
        return callSingleCompanyFinancials(corpCode, businessYear, reportCode);
    }

    /**
     * 주요계정 조회 — <b>캐시 우회</b>. 과거 연도 대량 소급(10년×1,500종목=15,000콜) 전용이다.
     *
     * <p>소급은 키마다 <b>정확히 한 번</b>만 조회하므로 캐시가 히트할 일이 없고, 대신 Redis에 15,000건(수십 MB)이
     * 1시간 동안 쌓인다. ⚠️ 이 Redis는 다른 프로젝트와 <b>공유하는 컨테이너</b>(redis-local)이고 VM은 e2-medium(4GB)이라,
     * 얻는 것 없이 메모리만 압박하는 거래다. 라이브 추천 경로는 종전대로 캐시본을 쓴다.</p>
     */
    public DartFinancialResponse fetchSingleCompanyFinancialsUncached(
            String corpCode, String businessYear, String reportCode) {
        return callSingleCompanyFinancials(corpCode, businessYear, reportCode);
    }

    private DartFinancialResponse callSingleCompanyFinancials(
            String corpCode, String businessYear, String reportCode) {

        try {
            DartFinancialResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", properties.apiKey())
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", businessYear)
                            .queryParam("reprt_code", reportCode)
                            .build())
                    .retrieve()
                    .body(DartFinancialResponse.class);

            if (response == null || !response.isSuccess()) {
                String msg = response == null ? "응답 없음" : response.message();
                throw new ExternalApiException("DART", "재무정보 조회 실패: " + msg);
            }
            return response;

        } catch (RestClientException ex) {
            log.error("DART API 호출 오류 corpCode={}", corpCode, ex);
            throw new ExternalApiException("DART", "DART API 호출 중 오류가 발생했습니다.", ex);
        }
    }

    /**
     * 최신 공시 목록 조회 (list.json). 최신 접수 순으로 반환된다.
     * 실시간성을 위해 캐싱하지 않는다. 데이터 없음(013)은 빈 리스트로 처리.
     *
     * @param pageCount 한 페이지 건수 (최대 100)
     */
    public List<DartDisclosureResponse.Disclosure> fetchRecentDisclosures(int pageCount) {
        try {
            DartDisclosureResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/list.json")
                            .queryParam("crtfc_key", properties.apiKey())
                            .queryParam("page_no", 1)
                            .queryParam("page_count", Math.min(pageCount, 100))
                            .build())
                    .retrieve()
                    .body(DartDisclosureResponse.class);

            if (response == null || response.isNoData() || response.list() == null) {
                return List.of();
            }
            if (!response.isSuccess()) {
                throw new ExternalApiException("DART", "공시 조회 실패: " + response.message());
            }
            return response.list();

        } catch (RestClientException ex) {
            log.error("DART 공시 조회 오류", ex);
            throw new ExternalApiException("DART", "DART 공시 조회 중 오류가 발생했습니다.", ex);
        }
    }

    /**
     * 전체 기업 고유번호 파일(corpCode.xml) 다운로드. 응답은 CORPCODE.xml 을 담은 zip(byte[]) 이다.
     */
    public byte[] downloadCorpCodeZip() {
        try {
            byte[] body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/corpCode.xml")
                            .queryParam("crtfc_key", properties.apiKey())
                            .build())
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new ExternalApiException("DART", "corpCode 다운로드 응답이 비어 있습니다.");
            }
            return body;
        } catch (RestClientException ex) {
            log.error("DART corpCode 다운로드 오류", ex);
            throw new ExternalApiException("DART", "DART corpCode 다운로드 중 오류가 발생했습니다.", ex);
        }
    }
}