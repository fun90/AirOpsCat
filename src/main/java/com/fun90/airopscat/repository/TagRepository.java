package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long>, JpaSpecificationExecutor<Tag> {

    Optional<Tag> findByName(String name);

    List<Tag> findByDisabled(Integer disabled);

    @Query("SELECT t FROM Tag t WHERE t.name LIKE %:keyword% OR t.description LIKE %:keyword%")
    List<Tag> searchByKeyword(@Param("keyword") String keyword);

    @Query(value = "SELECT t.* FROM tag t JOIN node_tag nt ON t.id = nt.tag_id WHERE nt.node_id = :nodeId", nativeQuery = true)
    List<Tag> findByNodeId(@Param("nodeId") Long nodeId);

    @Query(value = "SELECT t.* FROM tag t JOIN account_tag at ON t.id = at.tag_id WHERE at.account_id = :accountId", nativeQuery = true)
    List<Tag> findByAccountId(@Param("accountId") Long accountId);
    
    @Query("SELECT COUNT(n) FROM Node n JOIN n.tags t WHERE t.id = :tagId")
    int countNodesByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT COUNT(a) FROM Account a JOIN a.tags t WHERE t.id = :tagId")
    int countAccountsByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT n FROM Node n JOIN n.tags t WHERE t.id = :tagId")
    List<Node> findNodesByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT a FROM Account a JOIN a.tags t WHERE t.id = :tagId")
    List<Account> findAccountsByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT a FROM Account a JOIN a.tags t WHERE t.id IN :tagIds")
    List<Account> findAccountsByTagIds(@Param("tagIds") List<Long> tagIds);

    @Query("SELECT a FROM Account a JOIN a.tags t WHERE t.id IN :tagIds AND a.disabled = 0 AND (a.toDate IS NULL OR a.toDate > :currentTime)")
    List<Account> findActiveAccountsByTagIds(@Param("tagIds") List<Long> tagIds, @Param("currentTime") LocalDateTime currentTime);
    
    @Modifying
    @Query(value = "DELETE FROM node_tag WHERE node_id = :nodeId", nativeQuery = true)
    void deleteAllNodeTagsByNodeId(@Param("nodeId") Long nodeId);
    
    @Modifying
    @Query(value = "DELETE FROM account_tag WHERE account_id = :accountId", nativeQuery = true)
    void deleteAllAccountTagsByAccountId(@Param("accountId") Long accountId);
    
    @Modifying
    @Query(value = "INSERT INTO node_tag (node_id, tag_id) VALUES (:nodeId, :tagId)", nativeQuery = true)
    void insertNodeTag(@Param("nodeId") Long nodeId, @Param("tagId") Long tagId);
    
    @Modifying
    @Query(value = "INSERT INTO account_tag (account_id, tag_id) VALUES (:accountId, :tagId)", nativeQuery = true)
    void insertAccountTag(@Param("accountId") Long accountId, @Param("tagId") Long tagId);
    
    @Modifying
    @Query(value = "DELETE FROM node_tag WHERE node_id = :nodeId AND tag_id = :tagId", nativeQuery = true)
    void deleteNodeTag(@Param("nodeId") Long nodeId, @Param("tagId") Long tagId);
    
    @Modifying
    @Query(value = "DELETE FROM account_tag WHERE account_id = :accountId AND tag_id = :tagId", nativeQuery = true)
    void deleteAccountTag(@Param("accountId") Long accountId, @Param("tagId") Long tagId);
} 