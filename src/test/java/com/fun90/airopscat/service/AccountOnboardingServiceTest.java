package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnboardingRequest;
import com.fun90.airopscat.model.dto.AccountOnboardingUserRequest;
import com.fun90.airopscat.model.dto.AccountRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.TransactionType;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountOnboardingServiceTest {

    @Test
    void shouldCreateNewUserAccountTagsAndInitialTransaction() {
        Fixture fixture = new Fixture();
        AccountOnboardingRequest request = newUserRequest();
        request.setAmount(new BigDecimal("199"));
        request.setPaymentMethod("WeChat");

        AccountOnboardingService.Result result = fixture.service.onboard(request);

        assertEquals(1, fixture.userService.savedUsers.size());
        assertEquals(result.user().getId(), result.account().getUserId());
        assertNotNull(result.account().getAccountNo());
        assertNotNull(result.account().getUuid());
        assertNotNull(result.account().getAuthCode());
        assertEquals(100, result.account().getMaxConnections());
        assertEquals(3, result.account().getMaxIps());
        assertEquals(500, result.account().getBandwidth());
        assertEquals(20, result.account().getDownloadMbps());
        assertEquals(10, result.account().getUploadMbps());
        assertEquals(4, result.account().getLevel());
        assertEquals(2, result.account().getNodeMultiple());
        assertEquals(List.of(8L, 9L), fixture.tagService.updatedTagIds);
        assertEquals(TransactionType.INCOME.getValue(), result.transaction().getType());
        assertEquals(new BigDecimal("199"), result.transaction().getAmount());
        assertEquals("account", result.transaction().getBusinessTable());
        assertEquals(result.account().getId(), result.transaction().getBusinessId());
        assertEquals("账号首期：1月", result.transaction().getDescription());
        assertEquals("WeChat", result.transaction().getPaymentMethod());
    }

    @Test
    void shouldCreateAccountForExistingUserWithoutTransaction() {
        Fixture fixture = new Fixture();
        User existing = new User();
        existing.setId(7L);
        fixture.userService.existingUser = existing;
        AccountOnboardingRequest request = existingUserRequest(7L);

        AccountOnboardingService.Result result = fixture.service.onboard(request);

        assertSame(existing, result.user());
        assertEquals(7L, result.account().getUserId());
        assertNull(result.transaction());
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.transactionService.savedTransactions.size());
    }

    @Test
    void shouldRejectAmbiguousUserSourceInvalidAmountAndMissingPaymentMethod() {
        Fixture fixture = new Fixture();
        AccountOnboardingRequest ambiguous = newUserRequest();
        ambiguous.setUserId(7L);
        AccountOnboardingRequest invalidAmount = newUserRequest();
        invalidAmount.setAmount(BigDecimal.ZERO);
        AccountOnboardingRequest missingPayment = newUserRequest();
        missingPayment.setAmount(BigDecimal.TEN);

        assertThrows(IllegalArgumentException.class, () -> fixture.service.onboard(ambiguous));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.onboard(invalidAmount));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.onboard(missingPayment));
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.accountService.savedAccounts.size());
    }

    @Test
    void shouldValidateTagsBeforePersistingUserAndAccount() {
        Fixture fixture = new Fixture();
        fixture.tagService.failValidation = true;

        assertThrows(EntityNotFoundException.class, () -> fixture.service.onboard(newUserRequest()));
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.accountService.savedAccounts.size());
    }

    @Test
    void shouldRejectInvalidAccountBeforeAnyPersistence() {
        Fixture fixture = new Fixture();
        AccountOnboardingRequest request = newUserRequest();
        request.getAccount().setNodeMultiple(0);

        assertThrows(IllegalArgumentException.class, () -> fixture.service.onboard(request));
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.accountService.savedAccounts.size());
        assertEquals(0, fixture.transactionService.savedTransactions.size());
    }

    @Test
    void shouldRejectMissingExistingUserBeforeCreatingAccount() {
        Fixture fixture = new Fixture();

        assertThrows(EntityNotFoundException.class, () -> fixture.service.onboard(existingUserRequest(404L)));
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.accountService.savedAccounts.size());
        assertEquals(0, fixture.transactionService.savedTransactions.size());
    }

    @Test
    void shouldStopBeforeAccountCreationWhenNewUserEmailAlreadyExists() {
        Fixture fixture = new Fixture();
        fixture.userService.failWithDuplicateEmail = true;

        assertThrows(UserEmailAlreadyExistsException.class, () -> fixture.service.onboard(newUserRequest()));
        assertEquals(0, fixture.userService.savedUsers.size());
        assertEquals(0, fixture.accountService.savedAccounts.size());
        assertEquals(0, fixture.transactionService.savedTransactions.size());
    }

    @Test
    void shouldDeclareTransactionalBoundaryAndPropagateTransactionFailure() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transactionService.fail = true;
        AccountOnboardingRequest request = newUserRequest();
        request.setAmount(BigDecimal.TEN);
        request.setPaymentMethod("AliPay");

        assertNotNull(AccountOnboardingService.class
                .getMethod("onboard", AccountOnboardingRequest.class)
                .getAnnotation(Transactional.class));
        assertThrows(IllegalStateException.class, () -> fixture.service.onboard(request));
    }

    private static AccountOnboardingRequest newUserRequest() {
        AccountOnboardingUserRequest user = new AccountOnboardingUserRequest();
        user.setEmail("new@example.com");
        user.setPassword("secret123");
        user.setRole("VIP");

        AccountOnboardingRequest request = new AccountOnboardingRequest();
        request.setNewUser(user);
        request.setAccount(accountRequest());
        return request;
    }

    private static AccountOnboardingRequest existingUserRequest(Long userId) {
        AccountOnboardingRequest request = new AccountOnboardingRequest();
        request.setUserId(userId);
        request.setAccount(accountRequest());
        return request;
    }

    private static AccountRequest accountRequest() {
        AccountRequest account = new AccountRequest();
        account.setLevel(4);
        account.setNodeMultiple(2);
        account.setNodePrefix("8");
        account.setFromDate(LocalDateTime.of(2026, 7, 16, 10, 0));
        account.setToDate(LocalDateTime.of(2026, 8, 16, 10, 0));
        account.setPeriodType("MONTHLY");
        account.setMaxConnections(100);
        account.setMaxIps(3);
        account.setBandwidth(500);
        account.setDownloadMbps(20);
        account.setUploadMbps(10);
        account.setRemark("测试账户");
        account.setTagIds(List.of(8L, 9L));
        return account;
    }

    static class Fixture {
        final FakeUserService userService = new FakeUserService();
        final FakeAccountService accountService = new FakeAccountService();
        final FakeTagService tagService = new FakeTagService();
        final FakeTransactionService transactionService = new FakeTransactionService();
        final AccountOnboardingService service = new AccountOnboardingService(
                userService, accountService, tagService, transactionService);
    }

    static class FakeUserService extends UserService {
        User existingUser;
        boolean failWithDuplicateEmail;
        final List<User> savedUsers = new ArrayList<>();

        FakeUserService() {
            super(null, null);
        }

        @Override
        public User getUserById(Long id) {
            return existingUser != null && id.equals(existingUser.getId()) ? existingUser : null;
        }

        @Override
        public User saveUser(User user) {
            if (failWithDuplicateEmail) {
                throw new UserEmailAlreadyExistsException("邮箱已存在");
            }
            user.setId(11L);
            savedUsers.add(user);
            return user;
        }
    }

    static class FakeAccountService extends AccountService {
        final List<Account> savedAccounts = new ArrayList<>();

        @Override
        public Account saveAccount(Account account) {
            account.setId(22L);
            if (account.getAccountNo() == null) account.setAccountNo("generated-account");
            if (account.getUuid() == null) account.setUuid("123e4567-e89b-12d3-a456-426614174000");
            if (account.getAuthCode() == null) account.setAuthCode("generated-auth-code");
            savedAccounts.add(account);
            return account;
        }
    }

    static class FakeTagService extends TagService {
        boolean failValidation;
        List<Long> updatedTagIds = List.of();

        FakeTagService() {
            super(null, null, null);
        }

        @Override
        public void validateAccountTagIds(List<Long> tagIds) {
            if (failValidation) throw new EntityNotFoundException("部分标签不存在");
        }

        @Override
        public void updateAccountTags(Long accountId, List<Long> tagIds) {
            updatedTagIds = List.copyOf(tagIds);
        }
    }

    static class FakeTransactionService extends TransactionService {
        boolean fail;
        final List<Transaction> savedTransactions = new ArrayList<>();

        FakeTransactionService() {
            super(null, null, null, null);
        }

        @Override
        public Transaction saveTransaction(Transaction transaction) {
            if (fail) throw new IllegalStateException("交易保存失败");
            transaction.setId(33L);
            savedTransactions.add(transaction);
            return transaction;
        }
    }
}
