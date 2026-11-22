package com.draftagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DraftAgent 应用启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.draftagent")
@MapperScan("com.draftagent.**.mapper")
public class DraftAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DraftAgentApplication.class, args);
    }
}
