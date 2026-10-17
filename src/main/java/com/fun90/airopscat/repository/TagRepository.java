package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Tag;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TagRepository implements PanacheRepository<Tag> {

    public List<Tag> findByDisabled(Integer disabled) {
        return find("disabled", disabled).list();
    }


    public List<Tag> findByNodeId(Long nodeId) {
        return getEntityManager().createQuery(
                "select distinct t from NodeServer ns join ns.tags t where ns.nodeId = :nodeId", Tag.class)
                .setParameter("nodeId", nodeId).getResultList();
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
        return Math.toIntExact(getEntityManager().createQuery(
                "select count(distinct ns.nodeId) from NodeServer ns join ns.tags t where t.id = :tagId", Long.class)
                .setParameter("tagId", tagId).getSingleResult());
    }
    
    public int countAccountsByTagId(Long tagId) {
        return Math.toIntExact(count("select count(a) from Account a join a.tags t where t.id = ?1", tagId));
    }
    
    public List<Node> findNodesByTagId(Long tagId) {
        return getEntityManager().createQuery(
                "select distinct n from Node n, NodeServer ns join ns.tags t " +
                        "where ns.nodeId = n.id and t.id = :tagId", Node.class)
                .setParameter("tagId", tagId).getResultList();
    }
    
    public List<Account> findAccountsByTagId(Long tagId) {
        return find("select a from Account a join a.tags t where t.id = ?1", tagId)
                .project(Account.class)
                .list();
    }

    public List<Account> findActiveAccountsByTagIds(List<Long> tagIds, LocalDateTime currentTime) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return find("select a from Account a join a.tags t where t.id in ?1 and a.disabled = 0 and (a.toDate is null or a.toDate > ?2)", 
                   tagIds, currentTime)
                .project(Account.class)
                .list();
    }

    public Map<Long, List<Long>> findTagIdsByPlacementIds(List<Long> placementIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (placementIds == null || placementIds.isEmpty()) {
            return result;
        }
        List<?> rows = getEntityManager().createQuery(
                "select ns.id, t.id from NodeServer ns join ns.tags t where ns.id in :ids")
                .setParameter("ids", placementIds).getResultList();
        for (Object row : rows) {
            Object[] values = (Object[]) row;
            result.computeIfAbsent(((Number) values[0]).longValue(), key -> new ArrayList<>())
                    .add(((Number) values[1]).longValue());
        }
        return result;
    }

    public Map<Long, List<Long>> findAccountIdsByTagIds(List<Long> tagIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (tagIds == null || tagIds.isEmpty()) {
            return result;
        }

        List<?> rows = getEntityManager().createQuery(
                "select t.id, a.id from Account a join a.tags t where t.id in ?1")
            .setParameter(1, tagIds)
            .getResultList();

        for (Object row : rows) {
            Object[] values = (Object[]) row;
            Long tagId = ((Number) values[0]).longValue();
            Long accountId = ((Number) values[1]).longValue();
            result.computeIfAbsent(tagId, key -> new ArrayList<>()).add(accountId);
        }
        return result;
    }

    public List<Node> findNodesByAccountIds(List<Long> accountIds) {
        return getEntityManager().createQuery(
            "SELECT DISTINCT n FROM Node n, NodeServer ns JOIN ns.tags nt " +
            "WHERE ns.nodeId = n.id AND nt.id IN (" +
            "  SELECT t.id FROM Account a " +
            "  JOIN a.tags t " +
            "  WHERE a.id IN :accountIds" +
            ")", Node.class)
            .setParameter("accountIds", accountIds)
            .getResultList();
    }

    public List<Long> findNodeIdsByTagNameLike(String searchLike) {
        List<?> results = getEntityManager().createNativeQuery(
                "SELECT DISTINCT ns.node_id FROM node_server_tag nst " +
                "JOIN node_server ns ON ns.id = nst.node_server_id " +
                "INNER JOIN tag t ON t.id = nst.tag_id " +
                "WHERE lower(t.name) like ?1")
            .setParameter(1, searchLike)
            .getResultList();
        List<Long> nodeIds = new ArrayList<>(results.size());
        for (Object result : results) {
            if (result instanceof Number number) {
                nodeIds.add(number.longValue());
            }
        }
        return nodeIds;
    }

    
    @Transactional
    public void deleteAllPlacementTagsByNodeId(Long nodeId) {
        getEntityManager().createNativeQuery("DELETE nst FROM node_server_tag nst JOIN node_server ns ON ns.id = nst.node_server_id WHERE ns.node_id = ?1")
                .setParameter(1, nodeId).executeUpdate();
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
    public void insertAccountTag(Long accountId, Long tagId) {
        getEntityManager().createNativeQuery("INSERT INTO account_tag (account_id, tag_id) VALUES (?1, ?2)")
                .setParameter(1, accountId)
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
