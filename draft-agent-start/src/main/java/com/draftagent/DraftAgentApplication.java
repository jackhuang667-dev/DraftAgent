package com.draftagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.draftagent.business.service.DraftService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

/**
 * DraftAgent 应用启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.draftagent")
@MapperScan("com.draftagent.**.mapper")
public class DraftAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DraftAgentApplication.class, args);
    }
    @Bean
    public CommandLineRunner testAop(DraftService draftService) {
        return args -> {
            draftService.submitDraft();
            draftService.reviewDraft();
            draftService.queryDraft();
        };
    }
}
