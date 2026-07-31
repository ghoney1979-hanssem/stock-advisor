package com.stockadvisor.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한국투자증권 hashkey 발급(/uapi/hashkey) 응답. POST 주문 본문 위변조 방지용 해시.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisHashResponse(
        @JsonProperty("HASH") String hash
) {}
