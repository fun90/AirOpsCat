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

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long>, JpaSpecificationExecutor<Tag> {

    Optional<Tag> findByName(String name);

    List<Tag> findByDisabled(Integer disabled);

    @Query("SELECT t FROM Tag t WHERE t.name LIKE %:keyword% OR t.description LIKE %:keyword%")
    List<Tag> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT t FROM Tag t JOIN t.nodes n WHERE n.id = :nodeId")
    List<Tag> findByNodeId(@Param("nodeId") Long nodeId);

    @Query("SELECT t FROM Tag t JOIN t.accounts a WHERE a.id = :accountId")
    List<Tag> findByAccountId(@Param("accountId") Long accountId);
    
    @Query("SELECT COUNT(n) FROM Node n JOIN n.tags t WHERE t.id = :tagId")
    int countNodesByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT COUNT(a) FROM Account a JOIN a.tags t WHERE t.id = :tagId")
    int countAccountsByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT n FROM Node n JOIN n.tags t WHERE t.id = :tagId")
    List<Node> findNodesByTagId(@Param("tagId") Long tagId);
    
    @Query("SELECT a FROM Account a JOIN a.tags t WHERE t.id = :tagId")
    List<Account> findAccountsByTagId(@Param("tagId") Long tagId);

    @Query("SELECT DISTINCT t FROM Tag t JOIN t.nodes n WHERE n.id IN :nodeIds")
    List<Tag> findByNodeIdIn(@Param("nodeIds") List<Long> nodeIds);

    @Query("SELECT DISTINCT t FROM Tag t JOIN t.accounts a WHERE a.id IN :accountIds")
    List<Tag> findByAccountIdIn(@Param("accountIds") List<Long> accountIds);
    
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