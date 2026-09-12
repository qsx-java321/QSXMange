# RBAC 权限缓存（Redis）实现计划

## Context（背景与目标）

当前 QSXManager 为**纯 JWT 无状态认证**，角色/权限**每请求实时查库**：

- 每次带 Token 的请求 → `JwtAuthenticationFilter` → `SecurityUserDetailsService.loadUserByUsername(email)` → 查 `sys_user` 用户行 + 两条联表 SQL（`selectRoleCodes` / `selectPermissionCodes`）→ 填充 `SecurityUser.roles/permissions` → `getAuthorities()` 返回。
- 权限读取点共 **3 处**：`SecurityUserDetailsService`、`AuthServiceImpl.login`（组装 `LoginVO`）、`PermissionServiceImpl.getUserMenuTree`（菜单树按权限过滤）。

本任务引入 **Redis 缓存「用户 → 角色码 + 权限码」**，将最重的两条联表查询从每请求变为「命中即 0 次 / 未命中回源 1 次」。用户基本信息（密码、禁用状态）**不缓存**，仍实时查库，保证改密/禁用即时生效。

**非目标**（后续阶段再做）：Token 黑名单/登录态缓存、邮箱验证码、权限缓存的多级/分布式一致性增强。

### 已与用户确认的决策

1. **失效策略**：精确失效 —— 在权限/角色/用户变更点，事务提交后（AFTER_COMMIT）删除受影响用户的缓存 key；角色/权限维度先反查持有者再批量删。
2. **缓存范围**：仅缓存权限码（roles + permissions），用户行/密码/状态仍实时查库。
3. **收口范围**：三个权限读取点（UserDetailsService、登录 LoginVO、菜单树）统一收口到同一权限缓存服务。
4. **交付形式**：本计划落盘 `docs/plans/`。

技术底座（已核实）：Spring Boot 3.5.16（Redis 依赖版本由 Boot parent 管理）、本地 Redis 容器 `redis:7.4.9`（`localhost:6379`，无密码，AOF 持久化，见 `docs/dev-env/组件依赖README.md`）、Jackson 已内置。

---

## 一、总体设计

### 1.1 改造后的权限读取链路

```
请求 → JwtAuthenticationFilter
        └→ SecurityUserDetailsService.loadUserByUsername(email)
             ├─ 1. userMapper 按 email 查用户行（实时，保证密码/禁用即时生效）
             └─ 2. PermissionCacheService.load(userId)     ← 统一收口
                  ├─ 命中 Redis → 直接返回 roles/permissions
                  └─ 未命中 → 查库（2 条联表 SQL）→ 回填 Redis（含空集合）→ 返回
        → new SecurityUser(user, roles, permissions)      ← getAuthorities() 只返回已填充集合
```

`SecurityUser` **无需任何改动**：构造时 roles/permissions 已填充，`getAuthorities()` 原样返回（与用户要求的「只返回已填充集合」一致），不引入 Spring Security 懒加载。

### 1.2 Key 设计

```
key = qsx:auth:perm:{userId}    例：qsx:auth:perm:1
```

**采用 userId 而非 email 作为 key**，理由：

- 三个读取点（loadUserByUsername 查完用户行后、login、菜单树）均持有 userId；
- 所有失效点（assignRoles/assignPermissions/delete 等）传入的也都是 userId 或可反查出 userId 集合；
- 用户**改邮箱、禁用、改昵称**均不影响该 key，无需额外失效处理（改邮箱后旧 email 的 key 不存在，天然无残留）。

### 1.3 存储格式与序列化

value 为 JSON 字符串，由 `StringRedisTemplate` + `ObjectMapper` 序列化：

```json
{
  "roles": ["ADMIN"],
  "permissions": ["user:page", "user:create", "menu:tree"]
}
```

**空权限也回填缓存**（`{"roles":[],"permissions":[]}`）：新注册未绑角色的用户不会每请求反复打库，同时防缓存穿透。

### 1.4 TTL 与开关

- **TTL**：默认 `30 分钟`（可配置）。主动失效保证一致性，TTL 仅作兜底（防「失效失败/漏失效」导致永久陈旧）。
- **开关**：`qsx.rbac-cache.enabled`（默认 true）。关闭时 `load` 直接回源 MySQL、`evict*` 为空操作 —— 用于部署降级与特殊环境。

### 1.5 降级策略

`load()` 中捕获 Redis 访问异常（连接失败、序列化异常等），**降级为直接查库**，保证缓存故障不影响业务功能，仅失去加速效果（日志打印 `warn` 告警）。

---

## 二、依赖与配置

### 1. `pom.xml` 新增（版本由 Boot parent 管理，不写版本号）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 2. `application.yml` 新增

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

qsx:
  rbac-cache:
    enabled: true        # 权限缓存总开关
    ttl: 30m             # 缓存兜底 TTL
```

### 3. 新增 `config/properties/RbacCacheProperties.java`

`@ConfigurationProperties(prefix = "qsx.rbac-cache")`，字段 `boolean enabled`、`Duration ttl`（风格对齐现有 `JwtProperties`）。

---

## 三、新增核心服务：`PermissionCacheService`

### 接口 `service/PermissionCacheService.java`

```java
public interface PermissionCacheService {
    /** 读取用户权限码：Redis 优先，未命中回源 MySQL 并回填（含空集合），Redis 异常降级回源 */
    PermissionCacheData load(Long userId);

    /** 失效单个用户（assignRoles / 用户删除时调用） */
    void evictUser(Long userId);

    /** 失效某角色下全部用户（角色改码/删角色/分配权限时调用） */
    void evictUsersByRoleId(Long roleId);

    /** 失效持有某权限的全部用户（删除菜单/权限时调用） */
    void evictUsersByPermissionId(Long permissionId);
}
```

### 数据载体 `security/model/PermissionCacheData.java`

```java
public class PermissionCacheData {
    private List<String> roles;
    private List<String> permissions;
    // 无参/全参构造 + getter/setter（Jackson 序列化用）
}
```

### 实现 `service/impl/PermissionCacheServiceImpl.java`（要点）

```java
@Override
public PermissionCacheData load(Long userId) {
    if (!properties.isEnabled()) {
        return loadFromDb(userId);          // 开关关闭：直接查库
    }
    try {
        String json = stringRedisTemplate.opsForValue().get(key(userId));
        if (json != null) {
            return objectMapper.readValue(json, PermissionCacheData.class);   // 命中
        }
        PermissionCacheData data = loadFromDb(userId);                        // 未命中回源
        stringRedisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(data), properties.getTtl());
        return data;
    } catch (Exception e) {
        log.warn("Redis 读取权限缓存失败，降级查库, userId={}", userId, e);
        return loadFromDb(userId);                                            // 降级
    }
}

private PermissionCacheData loadFromDb(Long userId) {
    PermissionCacheData data = new PermissionCacheData();
    data.setRoles(userMapper.selectRoleCodes(userId));
    data.setPermissions(userMapper.selectPermissionCodes(userId));
    return data;
}
```

`evict*` 实现：`enabled=false` 时直接返回；否则按 key 删除，`evictUsersByRoleId` / `evictUsersByPermissionId` 用下方反查 SQL 取 userId 集合后批量 `delete(keys)`。

### 反查 SQL（追加到现有 Mapper）

`UserRoleMapper`：

```java
@Select("SELECT DISTINCT user_id FROM sys_user_role WHERE role_id = #{roleId}")
List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);
```

`RolePermissionMapper`：

```java
@Select("SELECT DISTINCT role_id FROM sys_role_permission WHERE permission_id = #{permissionId}")
List<Long> selectRoleIdsByPermissionId(@Param("permissionId") Long permissionId);
```

> `evictUsersByPermissionId` 流程：`selectRoleIdsByPermissionId` → 各 roleId 再 `selectUserIdsByRoleId` → 合并去重 → 批量删 key。菜单/权限删除是低频操作，链路可接受。

---

## 四、读取点改造（3 处，改动很小）

### 1. `security/service/SecurityUserDetailsService.java`

`loadUserByUsername` 中，查完用户行后：

```java
PermissionCacheData data = permissionCacheService.load(user.getId());
return new SecurityUser(user, data.getRoles(), data.getPermissions());
```

### 2. `service/impl/AuthServiceImpl.java`（login）

`LoginVO` 组装处，`userMapper.selectRoleCodes/selectPermissionCodes` 替换为：

```java
PermissionCacheData data = permissionCacheService.load(user.getId());
vo.setRoles(data.getRoles());
vo.setPermissions(data.getPermissions());
```

> 登录流程中 `authenticationManager.authenticate()` 已先触发一次 `loadUserByUsername`（已回填缓存），此处 `load` 直接命中，几乎无额外开销。

### 3. `service/impl/PermissionServiceImpl.java`（getUserMenuTree）

```java
PermissionCacheData data = permissionCacheService.load(current.getId());
List<String> ownedCodes = data.getPermissions();
```

---

## 五、失效设计（一致性核心）

### 5.1 现状隐患与本方案补齐

现状 `assignRoles` / `assignPermissions` **没有 `@Transactional`**，「先删旧关联再批量插新」的整表替换**不是原子操作**，中途失败会留下脏数据。本方案在接入缓存失效的同时，为这些写操作**补齐 `@Transactional(rollbackFor = Exception.class)`**，既保证整表替换原子性，又让「事务提交后再失效缓存」成为可能。

### 5.2 事件机制（提交后失效，避免竞态）

新增 `PermissionCacheEvictEvent`（含 `Type { USER, ROLE, PERMISSION }` + `Long id`），在写操作事务内 `publishEvent`，由监听器在 **AFTER_COMMIT** 后执行失效：

```java
@Component
public class PermissionCacheEvictListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvict(PermissionCacheEvictEvent event) {
        switch (event.getType()) {
            case USER -> cacheService.evictUser(event.getId());
            case ROLE -> cacheService.evictUsersByRoleId(event.getId());
            case PERMISSION -> cacheService.evictUsersByPermissionId(event.getId());
        }
    }
}
```

> 为什么必须 AFTER_COMMIT：若在事务内/提交前删除缓存，存在竞态 —— 并发请求在事务提交前回源读到的仍是旧数据并回填，覆盖了刚删的 key，导致失效失败。AFTER_COMMIT 后删除则回源必然读到新数据。

### 5.3 变更点映射表

| 变更操作 | 所在方法 | 失效范围 | 事件类型 |
|------|------|------|------|
| 给用户分配角色 | `UserServiceImpl.assignRoles` | 该用户 | USER |
| 删除用户 | `UserServiceImpl.delete` | 该用户 | USER |
| 给角色分配权限 | `RoleServiceImpl.assignPermissions` | 该角色下所有用户 | ROLE |
| 修改角色（编码变更） | `RoleServiceImpl.update` | 该角色下所有用户 | ROLE |
| 删除角色 | `RoleServiceImpl.delete` | 该角色下所有用户 | ROLE |
| 删除菜单/权限 | `PermissionServiceImpl.delete` | 持有该权限的所有用户 | PERMISSION |

**无需失效的场景及理由**：

- 用户 update（改 email/nickname/status）：key 为 userId，与这些字段无关；`isEnabled()` 每次查用户行实时判断；
- 角色创建、菜单/权限新增、用户注册、Excel 导入：新数据无缓存；
- init.sql 权限变更：发生在运行时之外，不涉及。

---

## 六、测试策略

测试复用本地 Redis 容器（与现有「复用本地 MySQL」策略一致），不引入嵌入式 Redis。

1. **`BaseIntegrationTest` 增强**：注入 `StringRedisTemplate`，`@BeforeEach` / `@AfterEach` 删除 `qsx:auth:perm:*` 前缀 key，避免跨用例污染（userId 自增不重复，本身冲突概率低，清理为保险）。
2. **新增专项测试 `RbacCacheTest`**：
   - 首次登录后 key 存在（回填）；
   - 再次请求命中（通过 `UserMapper` 调用计数或日志断言不重查库，或直接断言 Redis 有值）；
   - `assignRoles` 后权限**即时生效**（失效后回源重建）；
   - `assignPermissions` / 角色删改 / 菜单删除后相关用户权限即时生效；
   - 空权限用户也回填（防穿透）；
   - `enabled=false` 时行为退化为实时查库（可用 `@TestPropertySource` 覆盖）。
3. **全量回归**：现有 `AuthControllerTest` / `RbacTest` / `MenuTest` / `SecurityAccessTest` / `BusinessFlowTest` / `UserControllerTest` 等全部跑通（本地 Redis 常驻，与 MySQL 依赖方式一致）。

---

## 七、分步实施

1. **依赖与配置**：pom 加 redis starter；yml 加连接与 `qsx.rbac-cache` 配置；新增 `RbacCacheProperties`、`PermissionCacheData`；启动验证 Redis 连通（`mvn -DskipTests` 编译 + 启动观察）。
2. **缓存读取能力**：新增 `PermissionCacheService` 接口 + 实现（load/evict 三件套 + 反查 SQL + 降级）。
3. **三处读取点接入**：`SecurityUserDetailsService`、`AuthServiceImpl.login`、`PermissionServiceImpl.getUserMenuTree`。
4. **失效链路**：新增 `PermissionCacheEvictEvent` + 监听器；为 `assignRoles` / `assignPermissions` / 角色 update·delete / 用户 delete / 菜单权限 delete 补齐事务注解并发布事件。
5. **测试与文档**：`BaseIntegrationTest` 清理逻辑、`RbacCacheTest` 专项、全量回归；更新 README（技术栈加 Redis、鉴权说明改为「权限缓存 + 实时回源」）、`docs/session-notes/` 收尾总结。

---

## 八、风险与回滚

| 风险 | 缓解 |
|------|------|
| 缓存陈旧（失效漏点） | TTL 兜底 + 变更点主动失效双保险；失效逻辑集中在 5.3 映射表，评审可见 |
| Redis 故障拖垮业务 | `load()` 捕获异常降级查库，功能不中断 |
| AFTER_COMMIT 事件未执行 | 事件由 Spring 事务同步器保证；极端情况下 TTL 兜底 |
| 并发回源击穿 | 单机规模下可接受偶发重复回源；如后续需要可在 `load` 内加 per-key 本地锁（可选优化，本期不做） |
| **回滚** | 将 `qsx.rbac-cache.enabled` 置 false 即完全退回实时查库；代码层面删依赖即可整体移除 |

---

## 九、文件清单

**新增**

| 文件 | 说明 |
|------|------|
| `pom.xml`（依赖） | `spring-boot-starter-data-redis` |
| `application.yml`（配置） | redis 连接 + `qsx.rbac-cache` |
| `config/properties/RbacCacheProperties.java` | 开关 + TTL |
| `security/model/PermissionCacheData.java` | 缓存数据载体 |
| `service/PermissionCacheService.java` + `service/impl/PermissionCacheServiceImpl.java` | 核心缓存服务 |
| `config/event/PermissionCacheEvictEvent.java` + `config/event/PermissionCacheEvictListener.java` | 失效事件与监听器 |
| `src/test/java/com/qsx/RbacCacheTest.java` | 缓存专项测试 |

**修改**

| 文件 | 改动 |
|------|------|
| `SecurityUserDetailsService.java` | 权限来源改为缓存服务 |
| `AuthServiceImpl.java` | login 组装改用缓存服务 |
| `PermissionServiceImpl.java` | 菜单树 + delete 发事件 + 事务 |
| `UserServiceImpl.java` | assignRoles/delete 加事务 + 发事件 |
| `RoleServiceImpl.java` | assignPermissions/update/delete 加事务 + 发事件 |
| `UserRoleMapper.java` / `RolePermissionMapper.java` | 反查 SQL |
| `BaseIntegrationTest.java` | 清理缓存 key |
| `README.md` | 技术栈与鉴权说明同步 |

---

## 十、验证要点

1. 启动应用，`redis-cli keys 'qsx:auth:perm:*'` 验证登录后 key 生成、TTL 生效；
2. 登录 → 请求 `/api/users` → `/api/menus/current`，日志确认二次请求不再出现联表 SQL；
3. `PUT /api/users/{id}/roles` 分配新角色后**立即**用该用户 token 访问新权限接口，确认即时生效（缓存已失效回源）；
4. 删除某菜单后，持有该权限的普通用户访问对应接口返回 403（PERMISSION 级失效）；
5. 停掉 Redis 容器，接口仍正常（降级回源），重启 Redis 后自动恢复缓存；
6. `mvn test` 全量通过。
