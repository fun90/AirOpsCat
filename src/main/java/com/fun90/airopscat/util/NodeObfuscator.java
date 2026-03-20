package com.fun90.airopscat.util;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.enums.CoreType;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * 混淆节点工具类
 * 将 activeNodes 按 nodeMultiple 倍数复制，并对 name 字段进行混淆
 */
public class NodeObfuscator {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\{(\\d+)}");

    /**
     * 混淆节点列表
     *
     * @param activeNodes  原始节点列表
     * @param nodeMultiple 复制倍数（1~10）
     * @param nodePrefix 编号前缀
     * @return 混淆后的节点列表
     */
    public static List<NodeDto> obfuscate(List<NodeDto> activeNodes, int nodeMultiple, String nodePrefix) {
        String prefix = nodePrefix == null ? "" : nodePrefix;
        // 倍数限制
        int multiple = Math.max(1, Math.min(10, nodeMultiple));

        return activeNodes.stream()
                .flatMap(node -> obfuscateNode(node, multiple, prefix).stream())
                .collect(Collectors.toList());
    }

    /**
     * 对单个节点按倍数生成多个混淆副本
     */
    private static List<NodeDto> obfuscateNode(NodeDto original, int multiple, String prefix) {
        int startIndex = original.getNo() * 10;
        List<NodeDto> result = new ArrayList<>(multiple);

        for (int i = 0; i < multiple; i++) {
            NodeDto copy = deepCopy(original);
            int currentIndex = startIndex + i;

            // 替换 name 中的 {数字} 为新编号，没有则追加
            copy.setName(buildName(original.getName(), currentIndex, prefix));

            result.add(copy);
        }

        return result;
    }


    /**
     * 构建新的 name：将编号拼接到最后
     */
    private static String buildName(String originalName, int index, String prefix) {
        return originalName + "-" + prefix + index;
    }

    /**
     * NodeDto 深拷贝（保证各副本互相独立）
     */
    private static NodeDto deepCopy(NodeDto src) {
        NodeDto copy = new NodeDto();

        copy.setId(src.getId());
        copy.setServerId(src.getServerId());
        copy.setServerIp(src.getServerIp());
        copy.setServerHost(src.getServerHost());
        copy.setBackupServerId(src.getBackupServerId());
        copy.setBackupServerIp(src.getBackupServerIp());
        copy.setBackupServerHost(src.getBackupServerHost());
        copy.setPort(src.getPort());
        copy.setProtocol(src.getProtocol());
        copy.setCoreType(src.getCoreType());
        copy.setType(src.getType());
        copy.setTypeDescription(src.getTypeDescription());
        copy.setOutId(src.getOutId());
        copy.setOutName(src.getOutName());
        copy.setOutServerHost(src.getOutServerHost());
        copy.setOutPort(src.getOutPort());
        copy.setLevel(src.getLevel());
        copy.setDeployed(src.getDeployed());
        copy.setDisabled(src.getDisabled());
        copy.setName(src.getName());
        copy.setNo(src.getNo());
        copy.setRemark(src.getRemark());
        copy.setCreateTime(src.getCreateTime());
        copy.setUpdateTime(src.getUpdateTime());

        // 深拷贝 Map 字段
        copy.setInbound(src.getInbound() != null ? new HashMap<>(src.getInbound()) : new HashMap<>());
        copy.setRule(src.getRule() != null ? new HashMap<>(src.getRule()) : new HashMap<>());

        // 深拷贝 Set<Tag>
        copy.setTags(src.getTags() != null ? new HashSet<>(src.getTags()) : new HashSet<>());

        return copy;
    }
}
