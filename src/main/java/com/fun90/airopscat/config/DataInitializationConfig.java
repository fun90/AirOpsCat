package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

@Configuration
public class DataInitializationConfig {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner initializeData() {
        return args -> {
            // 检查系统中是否已有管理员用户
            Optional<User> adminUser = userRepository.findByEmail("admin@example.com");
            
            if (adminUser.isEmpty()) {
                // 创建默认管理员用户
                User admin = new User();
                admin.setEmail("admin@example.com");
                admin.setNickName("管理员");
                admin.setPassword(passwordEncoder.encode("admin123"));
                admin.setRole("ADMIN");
                admin.setDisabled((short) 0);
                admin.setCreateTime(LocalDateTime.now());
                admin.setUpdateTime(LocalDateTime.now());
                userRepository.save(admin);
                
                // 创建合作伙伴账户示例
                User partner = new User();
                partner.setEmail("partner@example.com");
                partner.setNickName("合作伙伴");
                partner.setPassword(passwordEncoder.encode("partner123"));
                partner.setRole("PARTNER");
                partner.setDisabled((short) 0);
                partner.setCreateTime(LocalDateTime.now());
                partner.setUpdateTime(LocalDateTime.now());
                userRepository.save(partner);
                
                // 创建VIP账户示例
                User vip = new User();
                vip.setEmail("vip@example.com");
                vip.setNickName("VIP用户");
                vip.setPassword(passwordEncoder.encode("vip123"));
                vip.setRole("VIP");
                vip.setDisabled((short) 0);
                vip.setCreateTime(LocalDateTime.now());
                vip.setUpdateTime(LocalDateTime.now());
                userRepository.save(vip);
                
                System.out.println("初始化数据完成!");
            }
        };
    }
}