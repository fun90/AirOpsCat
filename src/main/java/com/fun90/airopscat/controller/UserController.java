package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {
    
    private final UserService userService;
    
    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getUserPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status
    ) {
        Page<User> userPage = userService.getUserPage(page, size, search, role, status);

        Map<String, Object> response = new HashMap<>();
        response.put("records", userPage.getContent());
        response.put("total", userPage.getTotalElements());
        response.put("pages", userPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        User user = userService.getUserById(id);
        if (user != null) {
            return ResponseEntity.ok(user);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) {
        User savedUser = userService.saveUser(user);
        return ResponseEntity.ok(savedUser);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User user) {
        User existingUser = userService.getUserById(id);
        if (existingUser == null) {
            return ResponseEntity.notFound().build();
        }

        user.setId(id);
        User updatedUser = userService.updateUser(user);
        return ResponseEntity.ok(updatedUser);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        User existingUser = userService.getUserById(id);
        if (existingUser == null) {
            return ResponseEntity.notFound().build();
        }

        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableUser(@PathVariable Long id) {
        User user = userService.toggleUserStatus(id, false);
        if (user != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableUser(@PathVariable Long id) {
        User user = userService.toggleUserStatus(id, true);
        if (user != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
}