package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Tag;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class TagRepository implements PanacheRepository<Tag> {

    public List<Tag> findByDisabled(Integer disabled) {
        return find("disabled", disabled).list();
    }


    public List<Tag> findByNodeId(Long nodeId) {
        // Use native query to join with the junction table since nodes relationship was removed
        return getEntityManager().createNativeQuery(
            "SELECT t.* FROM tag t INNER JOIN node_tag nt ON t.id = nt.tag_id WHERE nt.node_id = ?", 
            Tag.class)
            .setParameter(1, nodeId)
            .getResultList();
    }

    public List<Tag> findByAccountId(Long accountId) {
        // Use native query to join with the junction table since accounts relationship was removed
        return getEntityManager().createNativeQuery(
            "SELECT t.* FROM tag t INNER JOIN account_tag at ON t.id = at.tag_id WHERE at.account_id = ?", 
            Tag.class)
            .setParameter(1, accountId)
            .getResultList();
    }
    
    public int countNodesByTagId(Long tagId) {
        return Math.toIntExact(count("select count(n) from Node n join n.tags t where t.id = ?1", tagId));
    }
    
    public int countAccountsByTagId(Long tagId) {
        return Math.toIntExact(count("select count(a) from Account a join a.tags t where t.id = ?1", tagId));
    }
    
    public List<Node> findNodesByTagId(Long tagId) {
        return find("select n from Node n join n.tags t where t.id = ?1", tagId)
                .project(Node.class)
                .list();
    }
    
    public List<Account> findAccountsByTagId(Long tagId) {
        return find("select a from Account a join a.tags t where t.id = ?1", tagId)
                .project(Account.class)
                .list();
    }

    public List<Account> findActiveAccountsByTagIds(List<Long> tagIds, LocalDateTime currentTime) {
        return find("select a from Account a join a.tags t where t.id in ?1 and a.disabled = 0 and (a.toDate is null or a.toDate > ?2)", 
                   tagIds, currentTime)
                .project(Account.class)
                .list();
    }

    
    @Transactional
    public void deleteAllNodeTagsByNodeId(Long nodeId) {
        getEntityManager().createNativeQuery("DELETE FROM node_tag WHERE node_id = ?1")
                .setParameter(1, nodeId)
                .executeUpdate();
    }
    
    @Transactional
    public void deleteAllAccountTagsByAccountId(Long accountId) {
        getEntityManager().createNativeQuery("DELETE FROM account_tag WHERE account_id = ?1")
                .setParameter(1, accountId)
                .executeUpdate();
    }
    
    @Transactional
    public void insertNodeTag(Long nodeId, Long tagId) {
        getEntityManager().createNativeQuery("INSERT INTO node_tag (node_id, tag_id) VALUES (?1, ?2)")
                .setParameter(1, nodeId)
                .setParameter(2, tagId)
                .executeUpdate();
    }
    
    @Transactional
    public void insertAccountTag(Long accountId, Long tagId) {
        getEntityManager().createNativeQuery("INSERT INTO account_tag (account_id, tag_id) VALUES (?1, ?2)")
                .setParameter(1, accountId)
                .setParameter(2, tagId)
                .executeUpdate();
    }
    
    @Transactional
    public void deleteNodeTag(Long nodeId, Long tagId) {
        getEntityManager().createNativeQuery("DELETE FROM node_tag WHERE node_id = ?1 AND tag_id = ?2")
                .setParameter(1, nodeId)
                .setParameter(2, tagId)
                .executeUpdate();
    }
    
    @Transactional
    public void deleteAccountTag(Long accountId, Long tagId) {
        getEntityManager().createNativeQuery("DELETE FROM account_tag WHERE account_id = ?1 AND tag_id = ?2")
                .setParameter(1, accountId)
                .setParameter(2, tagId)
                .executeUpdate();
    }
}