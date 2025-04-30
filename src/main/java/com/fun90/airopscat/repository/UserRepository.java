package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    List<User> findByDisabled(Short disabled);

    List<User> findByRole(String role);

    List<User> findByReferrer(Integer referrer);

    @Query("SELECT u FROM User u WHERE u.nickName LIKE %:keyword% OR u.email LIKE %:keyword% OR u.role = :keyword")
    List<User> searchByKeyword(@Param("keyword") String keyword);
}