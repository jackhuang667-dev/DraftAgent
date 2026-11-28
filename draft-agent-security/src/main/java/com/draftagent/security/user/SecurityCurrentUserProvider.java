package com.draftagent.security.user;

import com.draftagent.common.core.exception.ServiceException;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 Spring SecurityContext 读取当前用户；未认证抛 401 业务异常。
 */
@Component
public class SecurityCurrentUserProvider implements CurrentUserProvider {

    private static final int STATUS_UNAUTHORIZED = 401;

    @Override
    public CurrentUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser) {
            return (CurrentUser) authentication.getPrincipal();
        }
        throw new ServiceException(STATUS_UNAUTHORIZED, "未认证");
    }
}
