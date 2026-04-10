package com.fun90.airopscat.service.singbox;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SingBoxConnectionResolver {

    private static final String NODE_TAG_PREFIX = "node_";
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("/node_(\\d+)");

    public Optional<Account> resolveExactAccount(ClashConnection connection, Map<String, Account> accountByAccountNo) {
        String authUser = extractAuthUser(connection);
        if (authUser == null || accountByAccountNo == null || accountByAccountNo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountByAccountNo.get(authUser));
    }

    public String extractAuthUser(ClashConnection connection) {
        if (connection == null || connection.metadata() == null) {
            return null;
        }
        String authUser = connection.metadata().authUser();
        if (authUser == null || authUser.isBlank()) {
            return null;
        }
        return authUser.trim();
    }

    public String extractInboundTag(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        int slashIndex = type.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String tag = type.substring(slashIndex + 1).trim();
        return tag.isBlank() ? null : tag;
    }

    public Long extractNodeIdFromConnection(ClashConnection connection) {
        if (connection == null || connection.metadata() == null || connection.metadata().type() == null) {
            return null;
        }
        Matcher matcher = NODE_ID_PATTERN.matcher(connection.metadata().type());
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

    public Long extractNodeIdFromTag(String tag) {
        if (tag == null || !tag.startsWith(NODE_TAG_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(tag.substring(NODE_TAG_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
