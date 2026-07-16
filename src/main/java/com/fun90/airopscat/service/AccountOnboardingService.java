package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnboardingRequest;
import com.fun90.airopscat.model.dto.AccountOnboardingUserRequest;
import com.fun90.airopscat.model.dto.AccountRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.PaymentMethod;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.util.AccountTransactionDescriptionUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;

@ApplicationScoped
public class AccountOnboardingService {
    private final UserService userService;
    private final AccountService accountService;
    private final TagService tagService;
    private final TransactionService transactionService;

    @Inject
    public AccountOnboardingService(
            UserService userService,
            AccountService accountService,
            TagService tagService,
            TransactionService transactionService) {
        this.userService = userService;
        this.accountService = accountService;
        this.tagService = tagService;
        this.transactionService = transactionService;
    }

    @Transactional
    public Result onboard(AccountOnboardingRequest request) {
        validateRequest(request);
        tagService.validateAccountTagIds(request.getAccount().getTagIds());

        User user = resolveUser(request);
        Account account = toAccount(request.getAccount(), user.getId());
        Account savedAccount = accountService.saveAccount(account);

        if (request.getAccount().getTagIds() != null && !request.getAccount().getTagIds().isEmpty()) {
            tagService.updateAccountTags(savedAccount.getId(), request.getAccount().getTagIds());
        }

        Transaction transaction = createInitialTransaction(request, savedAccount);
        return new Result(user, savedAccount, transaction);
    }

    private void validateRequest(AccountOnboardingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("开户请求不能为空");
        }

        boolean hasExistingUser = request.getUserId() != null;
        boolean hasNewUser = request.getNewUser() != null;
        if (hasExistingUser == hasNewUser) {
            throw new IllegalArgumentException("请选择且仅选择一种用户来源");
        }

        AccountRequest account = request.getAccount();
        if (account == null) {
            throw new IllegalArgumentException("账户资料不能为空");
        }
        if (account.getFromDate() != null && account.getToDate() != null
                && !account.getToDate().isAfter(account.getFromDate())) {
            throw new IllegalArgumentException("到期时间必须晚于开始时间");
        }
        if (account.getNodeMultiple() == null || account.getNodeMultiple() < 1 || account.getNodeMultiple() > 10) {
            throw new IllegalArgumentException("节点倍数必须在 1 到 10 之间");
        }
        requireNonNegative(account.getLevel(), "级别");
        requireNonNegative(account.getMaxConnections(), "最大连接数");
        requireNonNegative(account.getMaxIps(), "最大 IP / 设备数");
        requireNonNegative(account.getBandwidth(), "流量限制");
        requirePositiveIfPresent(account.getDownloadMbps(), "下行限速");
        requirePositiveIfPresent(account.getUploadMbps(), "上行限速");

        BigDecimal amount = request.getAmount();
        if (amount != null) {
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("请输入有效金额");
            }
            if (request.getPaymentMethod() == null || request.getPaymentMethod().isBlank()) {
                throw new IllegalArgumentException("填写金额时必须选择付款方式");
            }
            if (PaymentMethod.fromValue(request.getPaymentMethod()) == null) {
                throw new IllegalArgumentException("付款方式不合法");
            }
        }
    }

    private void requireNonNegative(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(fieldName + "不能小于 0");
        }
    }

    private void requirePositiveIfPresent(Integer value, String fieldName) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(fieldName + "必须大于 0");
        }
    }

    private User resolveUser(AccountOnboardingRequest request) {
        if (request.getUserId() != null) {
            User user = userService.getUserById(request.getUserId());
            if (user == null) {
                throw new EntityNotFoundException("用户不存在");
            }
            return user;
        }

        AccountOnboardingUserRequest source = request.getNewUser();
        User user = new User();
        user.setEmail(source.getEmail());
        user.setPassword(source.getPassword());
        user.setNickName(source.getNickName());
        user.setRemarkName(source.getRemarkName());
        user.setRemark(source.getRemark());
        user.setRole(source.getRole());
        user.setDisabled(source.getDisabled());
        return userService.saveUser(user);
    }

    private Account toAccount(AccountRequest source, Long userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setAccountNo(source.getAccountNo());
        account.setLevel(source.getLevel());
        account.setNodeMultiple(source.getNodeMultiple());
        account.setNodePrefix(source.getNodePrefix());
        account.setFromDate(source.getFromDate());
        account.setToDate(source.getToDate());
        account.setPeriodType(source.getPeriodType());
        account.setUuid(source.getUuid());
        account.setAuthCode(source.getAuthCode());
        account.setMaxConnections(source.getMaxConnections());
        account.setMaxIps(source.getMaxIps());
        account.setBandwidth(source.getBandwidth());
        account.setDownloadMbps(source.getDownloadMbps());
        account.setUploadMbps(source.getUploadMbps());
        account.setDisabled(source.getDisabled());
        account.setRemark(source.getRemark());
        return account;
    }

    private Transaction createInitialTransaction(AccountOnboardingRequest request, Account account) {
        if (request.getAmount() == null) {
            return null;
        }

        Transaction transaction = new Transaction();
        transaction.setType(TransactionType.INCOME.getValue());
        transaction.setAmount(request.getAmount());
        transaction.setBusinessTable("account");
        transaction.setBusinessId(account.getId());
        transaction.setDescription(AccountTransactionDescriptionUtils.initialDescription(
                account.getFromDate(), account.getToDate()));
        transaction.setRemark(account.getRemark());
        transaction.setPaymentMethod(request.getPaymentMethod());
        return transactionService.saveTransaction(transaction);
    }

    public record Result(User user, Account account, Transaction transaction) {
    }
}
