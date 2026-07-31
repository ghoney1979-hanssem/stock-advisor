package com.stockadvisor.dart;

import com.stockadvisor.common.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * DART 전체 기업 고유번호 파일(corpCode.xml)을 내려받아 파싱한다.
 *
 * <p>파일은 모든 DART 등록 법인을 담고 있으며, 그중 <b>stock_code 가 있는 상장사</b>만 추린다.
 * 시장 구분/시가총액은 제공하지 않으므로 별도로 KIS 시세에서 보강한다.</p>
 */
@Service
public class DartCorpCodeService {

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeService.class);

    private final DartApiClient dartApiClient;

    public DartCorpCodeService(DartApiClient dartApiClient) {
        this.dartApiClient = dartApiClient;
    }

    /** 상장 종목 매핑 (corpCode, corpName, stockCode). */
    public record ListedCompany(String corpCode, String corpName, String stockCode) {
    }

    /**
     * corpCode.xml 을 받아 상장사(주식코드 보유) 목록을 반환한다.
     */
    public List<ListedCompany> fetchListedCompanies() {
        byte[] zipBytes = dartApiClient.downloadCorpCodeZip();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            if (zis.getNextEntry() == null) {
                throw new ExternalApiException("DART", "corpCode zip 이 비어 있습니다.");
            }
            List<ListedCompany> companies = parse(zis);
            log.info("corpCode 파싱 완료: 상장사 {}건", companies.size());
            return companies;
        } catch (ExternalApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ExternalApiException("DART", "corpCode 파싱 실패", ex);
        }
    }

    /** CORPCODE.xml 을 StAX 로 스트리밍 파싱 (대용량 30MB 대응). */
    private List<ListedCompany> parse(InputStream xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // 외부 엔티티 비활성화 (XXE 방지)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLStreamReader reader = factory.createXMLStreamReader(xml, "UTF-8");
        List<ListedCompany> result = new ArrayList<>();

        String corpCode = null, corpName = null, stockCode = null, current = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    current = reader.getLocalName();
                    if ("list".equals(current)) {
                        corpCode = corpName = stockCode = null;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && current != null) {
                    String text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    switch (current) {
                        case "corp_code" -> corpCode = append(corpCode, text);
                        case "corp_name" -> corpName = append(corpName, text);
                        case "stock_code" -> stockCode = append(stockCode, text);
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("list".equals(reader.getLocalName())) {
                        // 상장사만 (stock_code 가 실제 6자리 코드인 경우)
                        if (stockCode != null && stockCode.length() == 6) {
                            result.add(new ListedCompany(corpCode, corpName, stockCode));
                        }
                    }
                    current = null;
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    private String append(String prev, String text) {
        return prev == null ? text : prev + text;
    }
}
