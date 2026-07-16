package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceCreateTest {

    @Test
    void shouldNormalizeEmailApplyDefaultsAndHashPassword() {
        FakeUserRepository repository = new FakeUserRepository();
        UserService service = new UserService(repository, new AccountRepository());
        User user = new User();
        user.setEmail("  New.User@Example.COM ");
        user.setPassword("secret123");

        User saved = service.saveUser(user);

        assertEquals("new.user@example.com", saved.getEmail());
        assertEquals("VIP", saved.getRole());
        assertEquals(0, saved.getDisabled());
        assertNotEquals("secret123", saved.getPassword());
        assertTrue(saved.getPassword().startsWith("$2"));
        assertEquals(saved, repository.persisted);
    }

    @Test
    void shouldRejectDuplicateEmailIgnoringCase() {
        FakeUserRepository repository = new FakeUserRepository();
        repository.existing = new User();
        UserService service = new UserService(repository, new AccountRepository());
        User user = new User();
        user.setEmail("EXISTING@example.com");
        user.setPassword("secret123");

        assertThrows(UserEmailAlreadyExistsException.class, () -> service.saveUser(user));
    }

    @Test
    void shouldRejectInvalidEmailAndShortPassword() {
        UserService service = new UserService(new FakeUserRepository(), new AccountRepository());
        User invalidEmail = new User();
        invalidEmail.setEmail("invalid");
        invalidEmail.setPassword("secret123");
        User shortPassword = new User();
        shortPassword.setEmail("valid@example.com");
        shortPassword.setPassword("12345");

        assertThrows(IllegalArgumentException.class, () -> service.saveUser(invalidEmail));
        assertThrows(IllegalArgumentException.class, () -> service.saveUser(shortPassword));
    }

    static class FakeUserRepository extends UserRepository {
        User existing;
        User persisted;

        @Override
        public Optional<User> findByEmailIgnoreCase(String email) {
            return Optional.ofNullable(existing);
        }

        @Override
        public void persist(User user) {
            user.setId(1L);
            persisted = user;
        }
    }
}
