package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.UserDto;
import com.fun90.airopscat.model.entity.User;
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

@ApplicationScoped
public class UserService {

    private final UserRepository userRepository;

    @Inject
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
                conditions.add("disabled = 0");
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
        dto.setRole(user.getRole());
        return dto;
    }

    @Transactional
    public User saveUser(User user) {
        if (user.getDisabled() == null) {
            user.setDisabled(0);
        }
        userRepository.persist(user);
        return user;
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