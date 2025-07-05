//package com.fun90.airopscat;
//
//import com.fun90.airopscat.model.entity.Account;
//import com.fun90.airopscat.model.entity.User;
//import com.fun90.airopscat.repository.AccountRepository;
//import com.fun90.airopscat.repository.UserRepository;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.annotation.Commit;
//import org.springframework.test.context.TestPropertySource;
//
//import java.io.BufferedReader;
//import java.io.FileReader;
//import java.io.IOException;
//import java.time.Instant;
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.util.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//@SpringBootTest(
//    properties = {
//        "spring.config.location=classpath:/application.properties",
//        "spring.profiles.active=default"
//    },
//    webEnvironment = SpringBootTest.WebEnvironment.NONE
//)
//@TestPropertySource(properties = {
////    "spring.jpa.hibernate.ddl-auto=create-drop",
//    "spring.jpa.show-sql=false"
//})
//public class DataMigrationTest {
//
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private AccountRepository accountRepository;
//
//    private static final Pattern AUTH_CODE_PATTERN = Pattern.compile("/subscribe2/\\w+");
//
//    // 存储旧用户ID到新用户ID的映射关系
//    private Map<Long, Long> userIdMapping = new HashMap<>();
//
//    /**
//     * 执行完整数据迁移
//     * 修改下面的文件路径为你的实际CSV文件路径
//     */
//    @Test
//    @Commit
//    public void migrateAllData() {
//        String userCsvPath = "/your_path/user.csv";  // 修改为你的用户CSV文件路径
//        String accountCsvPath = "/your_path/account.csv";  // 修改为你的账户CSV文件路径
//
//        System.out.println("开始执行数据迁移...");
//        System.out.println("用户CSV文件: " + userCsvPath);
//        System.out.println("账户CSV文件: " + accountCsvPath);
//
//        try {
//            // 第一步：迁移用户数据并建立ID映射
//            migrateUsers(userCsvPath);
//
//            // 第二步：迁移账户数据，使用ID映射
//            migrateAccounts(accountCsvPath);
//
//            System.out.println("数据迁移完成！");
//            System.out.println("用户ID映射关系: " + userIdMapping);
//
//        } catch (Exception e) {
//            System.err.println("数据迁移失败: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 迁移用户数据并建立ID映射关系
//     */
//    private void migrateUsers(String csvFilePath) throws IOException {
//        System.out.println("开始迁移用户数据，文件路径: " + csvFilePath);
//
//        List<User> users = new ArrayList<>();
//        List<Long> oldUserIds = new ArrayList<>(); // 保存旧用户ID列表
//        int successCount = 0;
//        int errorCount = 0;
//        Map<String, Integer> colIndex = null;
//
//        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
//            String line;
//            boolean isFirstLine = true;
//            while ((line = reader.readLine()) != null) {
//                if (isFirstLine) {
//                    // 解析标题行
//                    colIndex = parseHeader(line);
//                    isFirstLine = false;
//                    continue;
//                }
//                try {
//                    String[] fields = line.split(",", -1);
//                    if (fields.length < colIndex.size()) {
//                        System.err.println("用户数据字段不足: " + line);
//                        errorCount++;
//                        continue;
//                    }
//                    Long oldUserId = parseLong(fields, colIndex, "id");
//                    if (oldUserId != null) oldUserIds.add(oldUserId);
//                    User user = parseUserFromCsv(fields, colIndex);
//                    if (user != null) {
//                        user.setId(null);
//                        users.add(user);
//                        successCount++;
//                    }
//                } catch (Exception e) {
//                    System.err.println("解析用户数据失败: " + line + ", 错误: " + e.getMessage());
//                    errorCount++;
//                }
//            }
//        }
//        if (!users.isEmpty()) {
//            List<User> savedUsers = userRepository.saveAll(users);
//            for (int i = 0; i < oldUserIds.size() && i < savedUsers.size(); i++) {
//                userIdMapping.put(oldUserIds.get(i), savedUsers.get(i).getId());
//                System.out.println("用户ID映射: " + oldUserIds.get(i) + " -> " + savedUsers.get(i).getId());
//            }
//            System.out.println("用户数据迁移完成，成功: " + successCount + ", 失败: " + errorCount);
//        }
//    }
//
//    /**
//     * 迁移账户数据，使用用户ID映射关系
//     */
//    private void migrateAccounts(String csvFilePath) throws IOException {
//        System.out.println("开始迁移账户数据，文件路径: " + csvFilePath);
//        System.out.println("可用的用户ID映射: " + userIdMapping);
//
//        List<Account> accounts = new ArrayList<>();
//        int successCount = 0;
//        int errorCount = 0;
//        int skippedCount = 0;
//        Map<String, Integer> colIndex = null;
//
//        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
//            String line;
//            boolean isFirstLine = true;
//            while ((line = reader.readLine()) != null) {
//                if (isFirstLine) {
//                    colIndex = parseHeader(line);
//                    isFirstLine = false;
//                    continue;
//                }
//                try {
//                    String[] fields = line.split(",", -1);
//                    if (fields.length < colIndex.size()) {
//                        System.err.println("账户数据字段不足: " + line);
//                        errorCount++;
//                        continue;
//                    }
//                    Long oldUserId = parseLong(fields, colIndex, "user_id");
//                    Account account = parseAccountFromCsv(fields, colIndex);
//                    if (account != null) {
//                        if (oldUserId != null) {
//                            Long newUserId = userIdMapping.get(oldUserId);
//                            if (newUserId != null) {
//                                account.setUserId(newUserId);
//                                System.out.println("账户 " + account.getAccountNo() + " 关联到用户: " + oldUserId + " -> " + newUserId);
//                            } else {
//                                System.err.println("找不到用户ID映射: " + oldUserId + ", 跳过账户: " + account.getAccountNo());
//                                skippedCount++;
//                                continue;
//                            }
//                        } else {
//                            System.err.println("账户 " + account.getAccountNo() + " 没有关联用户ID，跳过");
//                            skippedCount++;
//                            continue;
//                        }
//                        account.setId(null);
//                        accounts.add(account);
//                        successCount++;
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                    System.err.println("解析账户数据失败: " + line + ", 错误: " + e.getMessage());
//                    errorCount++;
//                }
//            }
//        }
//        if (!accounts.isEmpty()) {
//            accountRepository.saveAll(accounts);
//            System.out.println("账户数据迁移完成，成功: " + successCount + ", 跳过: " + skippedCount + ", 失败: " + errorCount);
//        }
//    }
//
//    // 解析标题行，返回字段名到下标的映射
//    private Map<String, Integer> parseHeader(String headerLine) {
//        String[] headers = headerLine.split(",");
//        Map<String, Integer> map = new HashMap<>();
//        for (int i = 0; i < headers.length; i++) {
//            map.put(headers[i].trim(), i);
//        }
//        return map;
//    }
//
//    // 安全解析Long
//    private Long parseLong(String[] fields, Map<String, Integer> colIndex, String col) {
//        try {
//            int idx = colIndex.get(col);
//            String val = fields[idx].trim();
//            if (val.isEmpty()) return null;
//            return Long.parseLong(val);
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    // 安全解析Integer
//    private Integer parseInt(String[] fields, Map<String, Integer> colIndex, String col) {
//        try {
//            int idx = colIndex.get(col);
//            String val = fields[idx].trim();
//            if (val.isEmpty()) return null;
//            return Integer.parseInt(val);
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    // 解析用户
//    private User parseUserFromCsv(String[] fields, Map<String, Integer> colIndex) {
//        try {
//            User user = new User();
//            // create_time
//            Long createTime = parseLong(fields, colIndex, "create_time");
//            if (createTime != null) user.setCreateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(createTime), ZoneId.systemDefault()));
//            // update_time
//            Long updateTime = parseLong(fields, colIndex, "update_time");
//            if (updateTime != null) user.setUpdateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(updateTime), ZoneId.systemDefault()));
//            // email
//            user.setEmail(fields[colIndex.get("email")].trim());
//            // nick_name
//            user.setNickName(fields[colIndex.get("nick_name")].trim().isEmpty() ? null : fields[colIndex.get("nick_name")].trim());
//            // password
//            user.setPassword(fields[colIndex.get("password")].trim());
//            // remark
//            user.setRemark(fields[colIndex.get("remark")].trim().isEmpty() ? null : fields[colIndex.get("remark")].trim());
//            // role
//            user.setRole(fields[colIndex.get("role")].trim().toUpperCase());
//            // status -> disabled
//            int status = parseInt(fields, colIndex, "status");
//            user.setDisabled(status == 1 ? 0 : 1);
//            // 新增字段默认值
//            user.setRemarkName(null);
//            user.setReferrer(null);
//            user.setFailedAttempts(0);
//            user.setLockTime(null);
//            return user;
//        } catch (Exception e) {
//            System.err.println("解析用户数据数字字段失败: " + e.getMessage());
//            return null;
//        }
//    }
//
//    // 解析账户
//    private Account parseAccountFromCsv(String[] fields, Map<String, Integer> colIndex) {
//        try {
//            Account account = new Account();
//            // create_time
//            Long createTime = parseLong(fields, colIndex, "create_time");
//            if (createTime != null) account.setCreateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(createTime), ZoneId.systemDefault()));
//            // update_time
//            Long updateTime = parseLong(fields, colIndex, "update_time");
//            if (updateTime != null) account.setUpdateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(updateTime), ZoneId.systemDefault()));
//            // account_no
//            account.setAccountNo(fields[colIndex.get("account_no")].trim());
//            // level
//            Integer level = parseInt(fields, colIndex, "level");
//            if (level != null) account.setLevel(level);
//            // cycle -> period_type
//            Integer cycle = parseInt(fields, colIndex, "cycle");
//            if (cycle != null) account.setPeriodType(cycle == 365 ? "YEARLY" : "MONTHLY");
//            else account.setPeriodType("MONTHLY");
//            // from_date
//            Long fromDate = parseLong(fields, colIndex, "from_date");
//            if (fromDate != null) account.setFromDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(fromDate), ZoneId.systemDefault()));
//            // max_connection -> max_online_ips
//            Integer maxConn = parseInt(fields, colIndex, "max_connection");
//            if (maxConn != null) account.setMaxOnlineIps(maxConn);
//            // speed
//            Integer speed = parseInt(fields, colIndex, "speed");
//            if (speed != null) account.setSpeed(speed);
//            // status -> disabled
//            Integer status = parseInt(fields, colIndex, "status");
//            account.setDisabled((status != null && status == 1) ? 0 : 1);
//            // subscription_url -> auth_code
//            String subscriptionUrl = fields[colIndex.get("subscription_url")].trim();
//            account.setAuthCode(extractAuthCode(subscriptionUrl));
//            // to_date
//            Long toDate = parseLong(fields, colIndex, "to_date");
//            if (toDate != null) account.setToDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(toDate), ZoneId.systemDefault()));
//            // user_id
//            Long userId = parseLong(fields, colIndex, "user_id");
//            if (userId != null) account.setUserId(userId);
//            // uuid
//            account.setUuid(fields[colIndex.get("uuid")].trim());
//            // 新增字段默认值
//            account.setBandwidth(10240);
//            return account;
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.err.println("解析账户数据数字字段失败: " + e.getMessage());
//            return null;
//        }
//    }
//
//    /**
//     * 从subscription_url中提取auth_code
//     */
//    private String extractAuthCode(String subscriptionUrl) {
//        if (subscriptionUrl == null || subscriptionUrl.trim().isEmpty()) {
//            return generateAuthCode();
//        }
//
//        Matcher matcher = AUTH_CODE_PATTERN.matcher(subscriptionUrl);
//        if (matcher.find()) {
//            try {
//                String authCode = matcher.group(1);
//                if (authCode != null && authCode.length() == 32) {
//                    return authCode;
//                }
//            } catch (IndexOutOfBoundsException e) {
//                System.out.println("authCode 不匹配，重新生成");
//                return generateAuthCode();
//            }
//        }
//
//        return generateAuthCode();
//    }
//
//    /**
//     * 生成新的auth_code
//     */
//    private String generateAuthCode() {
//        return UUID.randomUUID().toString().replace("-", "");
//    }
//}