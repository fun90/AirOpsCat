package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class DataInitializationConfig {

    private static final Logger log = Logger.getLogger(DataInitializationConfig.class);

    @Inject
    UserRepository userRepository;

    @Inject
    io.quarkus.security.runtime.SecurityIdentityAssociation identityAssociation;
    
    /**
     * 使用BCrypt编码密码，与Quarkus Security兼容
     */
    private String encodePassword(String password) {
        return BcryptUtil.bcryptHash(password);
    }

    @Transactional
    public void onStart(@Observes StartupEvent event) {
        // 检查系统中是否已有管理员用户
        List<User> adminUser = userRepository.findByRole("ADMIN");
        
        if (adminUser.isEmpty()) {
            log.info("No admin user found, creating default users...");
            
            // 创建默认管理员用户
            User admin = new User();
            admin.setEmail("admin@example.com");
            admin.setNickName("管理员");
            admin.setPassword(encodePassword("admin123"));
            admin.setRole("ADMIN");
            admin.setDisabled(0);
            admin.setCreateTime(LocalDateTime.now());
            admin.setUpdateTime(LocalDateTime.now());
            userRepository.persist(admin);
            
            // 创建合作伙伴账户示例
            User partner = new User();
            partner.setEmail("partner@example.com");
            partner.setNickName("合作伙伴");
            partner.setPassword(encodePassword("partner123"));
            partner.setRole("PARTNER");
            partner.setDisabled(0);
            partner.setCreateTime(LocalDateTime.now());
            partner.setUpdateTime(LocalDateTime.now());
            userRepository.persist(partner);
            
            // 创建VIP账户示例
            User vip = new User();
            vip.setEmail("vip@example.com");
            vip.setNickName("VIP用户");
            vip.setPassword(encodePassword("vip123"));
            vip.setRole("VIP");
            vip.setDisabled(0);
            vip.setCreateTime(LocalDateTime.now());
            vip.setUpdateTime(LocalDateTime.now());
            userRepository.persist(vip);
            
            log.info("初始化数据完成!");
        } else {
            log.info("Admin user already exists, skipping initialization");
        }
    }
}