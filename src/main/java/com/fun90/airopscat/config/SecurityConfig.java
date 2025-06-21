package com.fun90.airopscat.config;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.LoginLockService;
import com.fun90.airopscat.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;

    private final UserService userService;

    private final LoginLockService lockService;

    private final LoginAttemptFilter loginAttemptFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationSuccessHandler authSuccessHandler,
                                                   AuthenticationFailureHandler authFailureHandler) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .addFilterBefore(loginAttemptFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/static/**", "/login",  "/register", "/subscribe/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/partner/**").hasAnyRole("ADMIN", "PARTNER")
                .requestMatchers("/api/vip/**").hasAnyRole("ADMIN", "PARTNER", "VIP")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/api/login/auth")
//                .defaultSuccessUrl("/index", true)
                .failureUrl("/login?error=true")
                .usernameParameter("email") // 登录表单中的用户名参数
                .passwordParameter("password") // 登录表单中的密码参数
                .successHandler(authSuccessHandler)
                .failureHandler(authFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            )
            .sessionManagement(session -> session
                .invalidSessionUrl("/login")  // 会话无效时跳转到登录页面
                .maximumSessions(1)  // 每个用户最多只能有一个会话
                .expiredUrl("/login?expired=true")  // 会话过期时跳转到登录页面;
            );

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler authSuccessHandler() {
        return (request, response, authentication) -> {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String username = userDetails.getUsername();

            // 重置失败尝试计数
            lockService.resetFailedAttempts(username);

            // 重定向到主页或其他页面
            response.sendRedirect("/dashboard"); // 登录成功后的处理
        };
    }

    @Bean
    public AuthenticationFailureHandler authFailureHandler() {
        return (request, response, exception) -> {
            String errorMessage = null;
            // 账户锁定逻辑
            String email = request.getParameter("email");
            Optional<User> userOpt = userService.getByEmail(email);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (lockService.isAccountNonLocked(user)) {
                    lockService.increaseFailedAttempts(user);

                    if (lockService.isAttemptLimitReached(user)) {
                        lockService.lock(user);
                        exception = new LockedException("您的账户因多次登录失败已被锁定。请" + LoginLockService.LOCK_TIME_DURATION + "分钟后再试。");
                        errorMessage = exception.getMessage();
                    } else {
                        // 获取剩余尝试次数
                        int remainingAttempts = lockService.getRemainingAttempts(user);
                        errorMessage = exception.getMessage() + ", " + String.format(" 您还有%d次尝试机会。", remainingAttempts);
                    }
                }
            }

            // 设置错误消息并重定向到登录页
            request.getSession().setAttribute("error", errorMessage);
            response.sendRedirect("/login?error");
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        //将编写的UserDetailsService注入进来
        provider.setUserDetailsService(userDetailsService);
        //将使用的密码编译器加入进来
        provider.setPasswordEncoder(passwordEncoder);
        //将provider放置到AuthenticationManager 中
        return new ProviderManager(provider);
    }
}