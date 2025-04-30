package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.UserDto;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Page<User> getUserPage(int page, int size, String search, String role, String status) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // Create specification for dynamic filtering
        Specification<User> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in email and nickName
            if (StringUtils.hasText(search)) {
                Predicate emailPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("email")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate nickNamePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("nickName")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(emailPredicate, nickNamePredicate));
            }

            // Filter by role
            if (StringUtils.hasText(role)) {
                predicates.add(criteriaBuilder.equal(root.get("role"), role));
            }

            // Filter by status
            if ("active".equals(status)) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), 0));
            } else if ("disabled".equals(status)) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), 1));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return userRepository.findAll(spec, pageable);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public UserDto convertToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setNickName(user.getNickName());
        dto.setRole(user.getRole());
        return dto;
    }

    public List<User> findAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> findUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public List<User> findUsersByRole(String role) {
        return userRepository.findByRole(role);
    }

    public List<User> findActiveUsers() {
        return userRepository.findByDisabled((short) 0);
    }

    public List<User> findUsersByReferrer(Integer referrerId) {
        return userRepository.findByReferrer(referrerId);
    }

    public List<User> searchUsers(String keyword) {
        return userRepository.searchByKeyword(keyword);
    }

    public User createUser(User user) {
        LocalDateTime now = LocalDateTime.now();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        if (user.getDisabled() == null) {
            user.setDisabled((short) 0);
        }

        return userRepository.save(user);
    }

    public Optional<User> updateUser(Long id, User userDetails) {
        return userRepository.findById(id)
                .map(existingUser -> {
                    if (userDetails.getEmail() != null) {
                        existingUser.setEmail(userDetails.getEmail());
                    }
                    if (userDetails.getNickName() != null) {
                        existingUser.setNickName(userDetails.getNickName());
                    }
                    if (userDetails.getPassword() != null) {
                        existingUser.setPassword(userDetails.getPassword());
                    }
                    if (userDetails.getRemark() != null) {
                        existingUser.setRemark(userDetails.getRemark());
                    }
                    if (userDetails.getRole() != null) {
                        existingUser.setRole(userDetails.getRole());
                    }
                    if (userDetails.getReferrer() != null) {
                        existingUser.setReferrer(userDetails.getReferrer());
                    }
                    if (userDetails.getDisabled() != null) {
                        existingUser.setDisabled(userDetails.getDisabled());
                    }

                    existingUser.setUpdateTime(LocalDateTime.now());
                    return userRepository.save(existingUser);
                });
    }

    @Transactional
    public boolean deleteUser(Long id) {
        if (userRepository.existsById(id)) {
            userRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public Optional<User> disableUser(Long id) {
        return userRepository.findById(id)
                .map(user -> {
                    user.setDisabled((short) 1);
                    user.setUpdateTime(LocalDateTime.now());
                    return userRepository.save(user);
                });
    }

    @Transactional
    public Optional<User> enableUser(Long id) {
        return userRepository.findById(id)
                .map(user -> {
                    user.setDisabled((short) 0);
                    user.setUpdateTime(LocalDateTime.now());
                    return userRepository.save(user);
                });
    }
}