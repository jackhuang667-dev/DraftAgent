package com.draftagent.controller;

import com.draftagent.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("service", "draft-agent");
        data.put("timestamp", System.currentTimeMillis());
        return R.ok(data);
    }
}
