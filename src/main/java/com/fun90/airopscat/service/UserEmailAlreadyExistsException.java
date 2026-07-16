package com.fun90.airopscat.service;

public class UserEmailAlreadyExistsException extends IllegalArgumentException {
    public UserEmailAlreadyExistsException(String message) {
        super(message);
    }
}
