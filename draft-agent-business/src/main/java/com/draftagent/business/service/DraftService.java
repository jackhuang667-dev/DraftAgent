package com.draftagent.business.service;

import com.draftagent.business.annotation.LogRecord;
import org.springframework.stereotype.Service;

@Service
public class DraftService {

    @LogRecord("提交草案")
    public void submitDraft() {
        System.out.println("正在提交草案...");
    }

    @LogRecord("评审草案")
    public void reviewDraft() {
        System.out.println("正在评审草案...");
    }

    public void queryDraft() {
        System.out.println("正在查询草案...");
    }
}