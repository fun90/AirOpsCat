package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.UserDto;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

@ApplicationScoped
public class UserService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    @Inject
    public UserService(UserRepository userRepository, AccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * 分页查询用户
     */
    public io.quarkus.hibernate.orm.panache.PanacheQuery<User> getUserPage(String search, String role, String status) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();
        
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(email) like :search or lower(nickName) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Role filter
        if (role != null && !role.trim().isEmpty()) {
            conditions.add("role = :role");
            params.put("role", role);
        }
        
        // Status filter
        if (status != null && !status.trim().isEmpty()) {
            if ("active".equals(status)) {
                conditions.add("(disabled = 0 or disabled is null) and (lockTime is null or lockTime < :unlockThreshold)");
                params.put("unlockThreshold", LocalDateTime.now().minusMinutes(LoginLockService.LOCK_TIME_DURATION));
            } else if ("locked".equals(status)) {
                conditions.add("(disabled = 0 or disabled is null) and lockTime is not null and lockTime >= :lockedThreshold");
                params.put("lockedThreshold", LocalDateTime.now().minusMinutes(LoginLockService.LOCK_TIME_DURATION));
            } else if ("disabled".equals(status)) {
                conditions.add("disabled = 1");
            }
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        
        if (query.isEmpty()) {
            return userRepository.findAll(sort);
        } else {
            return userRepository.find(query, sort, params);
        }
    }

    public User getUserById(Long id) {
        return userRepository.findById(id);
    }

    public User getByEmail(String email) {
        return userRepository.findByEmail(email).orElse(null);
    }

    public UserDto convertToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setNickName(user.getNickName());
        dto.setRemarkName(user.getRemarkName());
        dto.setRemark(user.getRemark());
        dto.setRole(user.getRole());
        dto.setDisabled(user.getDisabled());
        dto.setFailedAttempts(user.getFailedAttempts());
        dto.setLockTime(user.getLockTime());
        dto.setCreateTime(user.getCreateTime());
        dto.setUpdateTime(user.getUpdateTime());
        dto.setAccountCount(user.getId() == null ? 0 : Math.toIntExact(accountRepository.countByUserId(user.getId())));
        return dto;
    }

    public Map<String, Long> getUserStats() {
        LocalDateTime lockedThreshold = LocalDateTime.now().minusMinutes(LoginLockService.LOCK_TIME_DURATION);

        Map<String, Long> stats = new HashMap<>();
        stats.put("total", userRepository.count());
        stats.put("active", userRepository.count("(disabled = 0 or disabled is null) and (lockTime is null or lockTime < ?1)", lockedThreshold));
        stats.put("locked", userRepository.count("(disabled = 0 or disabled is null) and lockTime is not null and lockTime >= ?1", lockedThreshold));
        stats.put("disabled", userRepository.count("disabled = 1"));
        return stats;
    }

    @Transactional
    public User saveUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("用户资料不能为空");
        }

        String email = normalizeEmail(user.getEmail());
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("请输入有效的邮箱地址");
        }
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new UserEmailAlreadyExistsException("邮箱已存在");
        }

        String password = user.getPassword();
        if (password == null || password.trim().length() < 6) {
            throw new IllegalArgumentException("密码长度至少为 6 个字符");
        }

        user.setEmail(email);
        user.setPassword(BcryptUtil.bcryptHash(password));
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("VIP");
        }
        if (user.getDisabled() == null) {
            user.setDisabled(0);
        }
        userRepository.persist(user);
        return user;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("邮箱地址不能为空");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public User updateUser(User user) {
        User existingUser = userRepository.findById(user.getId());
        if (existingUser == null) {
            throw new EntityNotFoundException("User not found");
        }

        // 使用工具方法复制非null属性
        copyNonNullProperties(user, existingUser);

        // No need to call save/persist for updates in Panache
        return existingUser;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(User src, User target) {
        if (src.getEmail() != null) target.setEmail(src.getEmail());
        if (src.getNickName() != null) target.setNickName(src.getNickName());
        if (src.getRemarkName() != null) target.setRemarkName(src.getRemarkName());
        if (src.getRole() != null) target.setRole(src.getRole());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getPassword() != null) {
            // 加密密码后再设置
            target.setPassword(BcryptUtil.bcryptHash(src.getPassword()));
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }

    @Transactional
    public User toggleUserStatus(Long id, boolean disabled) {
        User user = userRepository.findById(id);
        if (user != null) {
            user.setDisabled(disabled ? 1 : 0);
            // No need to call save/persist for updates in Panache
            return user;
        }
        return null;
    }
}
