package com.stockadvisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockadvisor.dart.dto.DartFinancialResponse;
import com.stockadvisor.market.dto.KisQuoteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * 외부 API(DART, KIS) 응답을 Redis 에 캐싱하기 위한 설정.
 * 요구사항에 따라 기본 TTL 을 1시간으로 둔다.
 *
 * <p>캐시 값은 record DTO 이므로, 캐시별로 <b>구체 타입을 지정한</b> 직렬화기를 사용한다.
 * (다형성 @class 메타 없이 정확한 타입으로 역직렬화 — final record 안전)</p>
 */
@Configuration
public class RedisCacheConfig {

    /** 캐시 이름 상수 - @Cacheable 의 value 와 일치시켜 사용한다. */
    public static final String DART_FINANCIALS = "dartFinancials";
    public static final String KIS_QUOTE = "kisQuote";

    /** 모든 캐시에 공통 적용되는 기본 TTL(1시간) 설정. */
    @Bean
    public RedisCacheConfiguration cacheConfiguration(
            @Value("${stockadvisor.cache.api-response-ttl:1h}") Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues();
    }

    /** 캐시별로 응답 DTO 타입에 바인딩된 JSON 직렬화기를 등록한다. */
    @Bean
    public RedisCacheManagerBuilderCustomizer cacheManagerCustomizer(
            @Value("${stockadvisor.cache.api-response-ttl:1h}") Duration ttl,
            ObjectMapper objectMapper) {

        return builder -> builder
                .withCacheConfiguration(KIS_QUOTE, typedConfig(ttl, objectMapper, KisQuoteResponse.class))
                .withCacheConfiguration(DART_FINANCIALS, typedConfig(ttl, objectMapper, DartFinancialResponse.class));
    }

    private <T> RedisCacheConfiguration typedConfig(Duration ttl, ObjectMapper objectMapper, Class<T> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }
}
