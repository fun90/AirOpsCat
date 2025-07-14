package com.fun90.airopscat.security;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * 用户状态检查服务
 * 在阻塞上下文中处理数据库操作
 */
@ApplicationScoped
public class UserStatusService {

    @Inject
    UserRepository userRepository;

    /**
     * 检查用户状态
     * 此方法在阻塞线程上运行，可以安全地执行阻塞操作
     */
    @Transactional
    public SecurityIdentity checkUserStatus(SecurityIdentity identity) {
        String email = identity.getPrincipal().getName();
        
        Optional<User> userOptional = userRepository.findByEmail(email);
        
        if (userOptional.isEmpty()) {
            // 用户不存在，返回匿名身份
            return QuarkusSecurityIdentity.builder()
                    .setAnonymous(true)
                    .build();
        }
        
        User user = userOptional.get();
        
        // 检查用户是否已禁用
        if (user.getDisabled() != null && user.getDisabled() == 1) {
            // 用户被禁用，返回匿名身份
            return QuarkusSecurityIdentity.builder()
                    .setAnonymous(true)
                    .build();
        }
        
        // 用户正常，返回原身份
        return identity;
    }
}