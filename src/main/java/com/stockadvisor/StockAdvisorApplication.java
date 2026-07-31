package com.stockadvisor;

import com.stockadvisor.config.properties.DartProperties;
import com.stockadvisor.config.properties.KisProperties;
import com.stockadvisor.config.properties.NotificationProperties;
import com.stockadvisor.config.properties.AdaptiveExitProperties;
import com.stockadvisor.config.properties.AdaptiveStopProperties;
import com.stockadvisor.config.properties.ExecutionCostProperties;
import com.stockadvisor.config.properties.ExitMethodProperties;
import com.stockadvisor.config.properties.MarketRegimeProperties;
import com.stockadvisor.config.properties.RiskProperties;
import com.stockadvisor.config.properties.SectorValuationProperties;
import com.stockadvisor.config.properties.SignalProperties;
import com.stockadvisor.config.properties.SizingProperties;
import com.stockadvisor.config.properties.StrategyPerformanceProperties;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties({
        DartProperties.class, KisProperties.class,
        NotificationProperties.class, SignalProperties.class,
        TradingPolicyProperties.class, StrategyPerformanceProperties.class,
        AdaptiveExitProperties.class, MarketRegimeProperties.class,
        RiskProperties.class, SizingProperties.class,
        ExecutionCostProperties.class, ExitMethodProperties.class,
        SectorValuationProperties.class, AdaptiveStopProperties.class
})
public class StockAdvisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAdvisorApplication.class, args);
    }
}