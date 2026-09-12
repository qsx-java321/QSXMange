| 组件分类 | 组件名称 | 选定版本 | 核心定位 |
| :--- | :--- | :--- | :--- |
| 运行环境 | JDK | 21 (LTS) | 虚拟线程与分代 ZGC 的高并发底座 |
| 核心框架 | Spring Boot | 3.5.16 | 3.5.x 系列最终收尾补丁版，极度稳定 |
| 微服务生态 | Spring Cloud | 2025.0.3 | 完美适配 Boot 3.5.x 的基石 |
| 阿里微服务 | SCA | 2025.0.0.0 | 官方验证的黄金组合 |
| 注册配置中心 | Nacos Server | 3.2.3 | 统一服务注册、远程配置下发|
| API 网关 | Spring Cloud Gateway(WebFlux) | 4.3.5 | 微服务统一流量入口，基于非阻塞 Netty 响应式模型 |
| 缓存/中间件 | Redis | 7.4.6 (LTS) | 高吞吐、高安全、长周期维护 |
| 消息队列 | RabbitMQ | 3.16.2-management | 强可靠、原生兼容、LTS 主线 |
| 消息队列 | Kafka | 4.3.1 | 稳定可靠 |

##🚨 模块隔离铁律（依赖管理红线）

为了防止 **`spring-boot-starter-web`（MVC）** 与 **`spring-boot-starter-webflux`（响应式）** 同时存在导致 Servlet 容器启动冲突，强制执行以下**模块分层隔离规范**：

| 模块类型 | 必须引入的 Starter | **绝对禁止引入** | 启动容器 | 核心通信组件 |
| :--- | :--- | :--- | :--- | :--- |
| **网关模块 (Gateway)** | `spring-boot-starter-webflux` <br> `spring-cloud-starter-gateway` | **`spring-boot-starter-web`** <br> **`spring-cloud-starter-openfeign`** | Netty (非阻塞) | 原生 `WebClient` 或 `@HttpExchange` |
| **业务模块 (Service/API)** | `spring-boot-starter-web` <br> `spring-cloud-starter-openfeign` | **`spring-boot-starter-webflux`** （注释：若需 WebClient 请单独引入 `spring-webflux` 并排除其自动配置，但强烈建议使用 `RestClient` 替代） | Tomcat (阻塞) | `@FeignClient` |

> **特别修正说明**：文档中提到的 `spring-cloud-starter-gateway-server-webflux` 是非标准坐标，**请统一替换为官方坐标**：
> ```xml
> <dependency>
>     <groupId>org.springframework.cloud</groupId>
>     <artifactId>spring-cloud-starter-gateway</artifactId>
> </dependency>
> ```

---

# Sentinel1.8.9 BlockRequestHandler + status(枚举.value())
在 Spring Boot 3.5 / Spring WebFlux 6.2 环境下，使用 Sentinel 1.8.9 时抛出：

```txxt
java.lang.NoSuchMethodError: 
'org.springframework.web.reactive.function.server.ServerResponse$BodyBuilder 
org.springframework.web.reactive.function.server.ServerResponse.status(org.springframework.http.HttpStatus)'
    at com.alibaba.csp.sentinel.adapter.spring.webflux.callback.DefaultBlockRequestHandler.handleRequest(DefaultBlockRequestHandler.java:45)
根本原因：Spring Framework 6.2 删除了 ServerResponse.status(HttpStatus) 方法，仅保留 ServerResponse.status(int)，而 Sentinel 1.8.9 的默认处理器仍调用旧 API。
```

✅ 解决方案核心：自定义 BlockRequestHandler
Sentinel 提供了官方扩展点 BlockRequestHandler，用于完全自定义限流响应逻辑。通过注册自定义实现，可以绕过有问题的 DefaultBlockRequestHandler。

📝 完整代码模板
```java
package com.qsx.gateway.config;

import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.BlockRequestHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Configuration
public class SentinelBlockHandlerConfig {

    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return (exchange, ex) -> {
            // 自定义响应内容
            String message = "请求被限流，请稍后重试";
            
            return ServerResponse
                    // ⚠️ 关键：使用 .value() 转为 int，避免调用已删除的 status(HttpStatus)
                    .status(HttpStatus.TOO_MANY_REQUESTS.value())
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .bodyValue(message);
        };
    }
}
```

🔑 关键点解析
关键点	说明
status(int) 而非 status(HttpStatus)	使用 HttpStatus.TOO_MANY_REQUESTS.value() 得到 int 值，调用新 API，避免 NoSuchMethodError
BlockRequestHandler 替换默认实现	Sentinel 的 SentinelBlockExceptionHandler 会优先使用注入的 BlockRequestHandler Bean，完全绕过 DefaultBlockRequestHandler
响应格式自定义	可根据需要设置 MediaType（JSON / TEXT / XML）和响应内容
UTF-8 编码	使用 new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8) 避免中文乱码

# 注意事项
1. Sentinel 使用
    - 在 Controller 方法上添加 @SentinelResource(value="资源名", blockHandlerClass = xxx.class, blockHandler = "xxx")
    - 降级处理类存放静态方法（方法参数比原方法多一个 BlockException ，返回类型与原方法一致，如 Mono<String> ）
    - 控制台地址配置： spring.cloud.sentinel.transport.dashboard: localhost:8090
2. Sentinel 配置类
    - 全局兜底降级处理器（ XxxHandler ）
    - 处理 没有配置 @SentinelResource 注解的接口 被 URL 规则限流时的响应
    - 返回状态码 429 + UTF-8 编码的中文提示信息


#  MySQL
```bath
qsx@DESKTOP-SUFEJ61:~$ docker volume create mysql-data
mysql-data
qsx@DESKTOP-SUFEJ61:~$ docker run -d --name mysql -p 3306:3306 -v mysql-data:/var/lib/mysql -e MYSQL_ROOT_PASSWORD=123456 mysql:8.4
```

### 🚨 导入 SQL 必须指定 utf8mb4 字符集（防止中文乱码）
`sql/init.sql` 已**自包含建库**（内置 `CREATE DATABASE IF NOT EXISTS QSXManager ... utf8mb4` + `USE` + `SET NAMES utf8mb4`），无需再手工建库，导入一条命令即可完成「建库 + 建表 + 预置数据」全部初始化。注意：该脚本为首次初始化/全量重建用途，会 DROP 重建全部表、清空已有数据。

建表/种子数据文件（如 `sql/init.sql`）是 **UTF-8 无 BOM**。若直接管道给 mysql 客户端而不指定连接字符集，UTF-8 字节会被误按 latin1 解释并双重编码，导致中文存成 mojibake（如「新增用户」变 `æ–°å¢žç”¨æˆ·`），且权限名、角色名、昵称、表/列 COMMENT 全受影响。

**正确导入方式（二选一）：**

方式一：`docker cp` 拷入容器再以 utf8mb4 导入（Windows PowerShell 推荐，绕开 shell 重新编码）
```bash
docker cp sql/init.sql mysql:/tmp/init.sql
docker exec mysql sh -c "mysql --default-character-set=utf8mb4 -uroot -p123456 < /tmp/init.sql"
```

方式二：客户端命令行直接加 `--default-character-set=utf8mb4`
```bash
mysql --default-character-set=utf8mb4 -uroot -p123456 < sql/init.sql
```

> 说明：init.sql 建表时已声明 `DEFAULT CHARSET=utf8mb4`，这里额外加 `--default-character-set` 是让**客户端连接/解析输入字节**也按 utf8mb4，两者结合才能保证中文正确落库。应用侧 JDBC 已用 `characterEncoding=utf8`，运行时读取无碍。

# nacos3.2.3
```bath
# 控制台访问端口
nacos.console.port=18080
# 服务主端口（客户端连接用）
nacos.server.main.port=8848
# 注意：gRPC端口默认为 9848，需确保网络通畅
```



#  Redis（无密码、不自动重启、持久化）
```bash
docker run -d \
  --name redis \
  --restart no \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:7.4.9 \
  redis-server --appendonly yes
```
#  RabbitMQ（带管理面板、自定义账号、不自动重启、持久化）
```bath
docker run -d \
  --name rabbitmq \
  --restart no \
  -p 5672:5672 \
  -p 15672:15672 \
  -v rabbitmq-data:/var/lib/rabbitmq \
  -e RABBITMQ_DEFAULT_USER=admin \
  -e RABBITMQ_DEFAULT_PASS=admin@123 \
  rabbitmq:4.3.2-management
```
# kafka 4.3.1
```bath
docker volume create kafka-data
docker run -d \
  -p 9092:9092 \
  -v kafka-data:/var/lib/kafka/data \
  --name kafka \
  apache/kafka:4.3.1
```
# SeaweedFS（修复参数、S3 网关开启、root 权限、避开 Nacos18080 端口冲突、不自动重启、持久化）
```bath
docker run -d \
  --name seaweedfs \
  --restart no \
  --user root \
  -p 9333:9333 \
  -p 8080:8080 \
  -p 8888:8888 \
  -p 18081:8333 \
  -v seaweedfs-data:/data \
  chrislusf/seaweedfs:4.39 \
  server -dir=/data -master.defaultReplication=001 -s3
```

# 目前全部网页控制台访问链接
RabbitMQ 消息管理后台
地址：http://127.0.0.1:15672
登录账号：admin / admin@123
SeaweedFS Master 集群元数据面板
地址：http://127.0.0.1:9333
SeaweedFS Filer 文件可视化管理面板（文件夹式操作文件）
地址：http://127.0.0.1:8888
SeaweedFS S3 对象存储端点（程序对接使用，无网页控制台）
地址：http://127.0.0.1:18081
# docker 数据集使用情况
```bath
qsx@qiu2024:~$ docker volume ls
DRIVER    VOLUME NAME
local     rabbitmq-data
local     redis-data
local     seaweedfs-data
qsx@qiu2024:~$ docker volume inspect seaweedfs-data
[
    {
        "CreatedAt": "2026-07-19T12:24:29Z",
        "Driver": "local",
        "Labels": null,
        "Mountpoint": "/var/lib/docker/volumes/seaweedfs-data/_data",
        "Name": "seaweedfs-data",
        "Options": null,
        "Scope": "local"
    }
]
qsx@qiu2024:~$ docker volume inspect redis-data
[
    {
        "CreatedAt": "2026-07-18T03:35:12Z",
        "Driver": "local",
        "Labels": null,
        "Mountpoint": "/var/lib/docker/volumes/redis-data/_data",
        "Name": "redis-data",
        "Options": null,
        "Scope": "local"
    }
]
qsx@qiu2024:~$ docker volume inspect rabbitmq-data
[
    {
        "CreatedAt": "2026-07-18T03:35:30Z",
        "Driver": "local",
        "Labels": null,
        "Mountpoint": "/var/lib/docker/volumes/rabbitmq-data/_data",
        "Name": "rabbitmq-data",
        "Options": null,
        "Scope": "local"
    }
]
qsx@DESKTOP-SUFEJ61:~$ docker volume inspect mysql-data
[
    {
        "CreatedAt": "2026-08-16T11:14:00Z",
        "Driver": "local",
        "Labels": null,
        "Mountpoint": "/var/lib/docker/volumes/mysql-data/_data",
        "Name": "mysql-data",
        "Options": null,
        "Scope": "local"
    }
]
qsx@DESKTOP-SUFEJ61:~$
```
# docker ps ->情况说明
好的，您的 Docker 环境中当前运行着 **4 个容器**。按启动时间从新到旧，为您分开整理如下：

---

### 1. RabbitMQ（消息队列）
- **容器 ID**：`320db9f7d0b2`
- **镜像版本**：`rabbitmq:4.3.2-management`（带管理界面）
- **状态**：运行中（已启动 **3 分钟**）
- **端口映射**：
  - `5672` → 消息通信端口（供应用连接）
  - `15672` → Web 管理界面端口（浏览器访问）
- **容器名**：`rabbitmq`

---

### 2. MySQL（关系型数据库）
- **容器 ID**：`71c579aaa0d3`
- **镜像版本**：`mysql:8.4`
- **状态**：运行中（已启动 **4 分钟**）
- **端口映射**：
  - `3306` → 标准数据库连接端口
- **容器名**：`mysql`

---

### 3. SeaweedFS（分布式文件存储）
- **容器 ID**：`d1eb31a968e0`
- **镜像版本**：`chrislusf/seaweedfs:4.39`
- **状态**：运行中（已启动 **11 秒**，刚重启/启动）
- **端口映射**：
  - `8080` → 文件服务/卷服务器接口
  - `8888` → Filer 或 S3 网关接口
  - `9333` → Master 服务接口
  - `18081`（宿主机）→ `8333`（容器内）→ 额外通信端口
- **容器名**：`seaweedfs`

---

### 4. Redis（内存缓存/键值数据库）
- **容器 ID**：`fa534a1e984a`
- **镜像版本**：`redis:7.4.9`
- **状态**：运行中（已启动 **14 秒**，刚重启/启动）
- **端口映射**：
  - `6379` → 标准 Redis 连接端口
- **容器名**：`redis`

---

### 快速访问摘要（供您本地调试）
| 服务 | 连接地址/端口 | 管理入口 |
|------|---------------|----------|
| MySQL | `localhost:3306` | 命令行或客户端 |
| Redis | `localhost:6379` | 命令行 `redis-cli` |
| RabbitMQ | `localhost:5672` | Web UI：`http://localhost:15672`（默认 guest/guest） |
| SeaweedFS | Master `localhost:9333`，Filer/S3 `localhost:8888`，Volume `localhost:8080` | 可视需查看 API |