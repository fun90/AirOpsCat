# AirOpsCat
一个轻量、灵活、高效的服务器管理系统，保持敏捷，掌控一切。🐱💨

# 目标
* 简单、小巧
* 有设计、扩展性强


# 设计图

# 打包
mvn clean package

./mvnw clean package

mvn -Pnative native:compile

./target/airopscat --spring.config.location=file:src/main/resources/application.yaml


用户账户
- 用户管理
- 账户管理
- 账户流量

基础设备
- 域名
- 服务器

代理设置
- 配置模板
- 节点管理

财务
- 账单流水

