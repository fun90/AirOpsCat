package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.UserDto;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
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
    
    public User createUser(String email, String password, String nickName, String role) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickName(nickName);
        user.setRole(role);
        user.setDisabled((short) 0);
        return userRepository.save(user);
    }
}