package com.draftagent.business.aspect;

import com.draftagent.business.annotation.LogRecord;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/*
注解	作用
@Aspect	告诉 Spring 这是一个切面
@Component	把切面注册为 Spring Bean
@Before	指定目标方法执行前运行通知
 */

@Aspect
@Component
public class LogAspect {

    @Before("@annotation(logRecord)")
    public void recordLog(LogRecord logRecord) {

        System.out.println("========== 操作日志 ===Unbound pointcut parameter 'logRecord' =======");
        System.out.println("检测到 LogRecord 注解");
        System.out.println("开始执行日志记录");
        System.out.println(logRecord);
    }
}