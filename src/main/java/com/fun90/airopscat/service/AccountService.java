package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Autowired
    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    public Page<Account> getAccountPage(int page, int size, String search, String periodType, String status, Long userId) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // Create specification for dynamic filtering
        Specification<Account> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in uuid and authCode
            if (StringUtils.hasText(search)) {
                Predicate uuidPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("uuid")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate authCodePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("authCode")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(uuidPredicate, authCodePredicate));
            }

            // Filter by periodType
            if (StringUtils.hasText(periodType)) {
                predicates.add(criteriaBuilder.equal(root.get("periodType"), periodType));
            }

            // Filter by userId
            if (userId != null) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            }

            // Filter by status
            if ("active".equals(status)) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), 0));
            } else if ("disabled".equals(status)) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), 1));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return accountRepository.findAll(spec, pageable);
    }

    public Account getAccountById(Long id) {
        return accountRepository.findById(id).orElse(null);
    }

    public Optional<Account> getByUuid(String uuid) {
        return accountRepository.findByUuid(uuid);
    }

    public AccountDto convertToDto(Account account) {
        AccountDto dto = new AccountDto();
        BeanUtils.copyProperties(account, dto);
        
        // 获取关联用户信息
        if (account.getUserId() != null) {
            userRepository.findById(account.getUserId()).ifPresent(user -> {
                dto.setUserEmail(user.getEmail());
            });
        }
        
        return dto;
    }

    @Transactional
    public Account saveAccount(Account account) {
        if (account.getDisabled() == null) {
            account.setDisabled(0);
        }
        
        // 生成唯一UUID如果未提供
        if (account.getUuid() == null || account.getUuid().isEmpty()) {
            account.setUuid(UUID.randomUUID().toString());
        }
        
        return accountRepository.save(account);
    }

    @Transactional
    public Account updateAccount(Account account) {
        Account existingAccount = accountRepository.findById(account.getId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        // 使用工具方法复制非null属性
        copyNonNullProperties(account, existingAccount);

        return accountRepository.save(existingAccount);
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Object src, Object target) {
        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

    // 获取对象中所有为null的属性名
    private String[] getNullPropertyNames(Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
    }

    @Transactional
    public void deleteAccount(Long id) {
        accountRepository.deleteById(id);
    }

    @Transactional
    public Account toggleAccountStatus(Long id, boolean disabled) {
        Optional<Account> optionalAccount = accountRepository.findById(id);
        if (optionalAccount.isPresent()) {
            Account account = optionalAccount.get();
            account.setDisabled(disabled ? 1 : 0);
            return accountRepository.save(account);
        }
        return null;
    }
    
    public List<Account> findActiveAccountsByUserId(Long userId) {
        return accountRepository.findActiveAccountsByUserId(userId);
    }
    
    public List<Account> findByPeriodType(String periodType) {
        return accountRepository.findByPeriodType(periodType);
    }
}