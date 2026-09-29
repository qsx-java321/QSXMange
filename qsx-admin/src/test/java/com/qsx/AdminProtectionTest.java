package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.common.constant.PermissionConstants;
import com.qsx.domain.entity.Permission;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.PermissionMapper;
import com.qsx.security.session.AuthRedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内置超管保护 —— 「ADMIN 角色被停用」场景的回归测试。
 *
 * <h3>被修复的缺陷</h3>
 * 超管三条保护（1020 不可禁用 / 1021 不可强制登出 / 1025 不可删除）统一用
 * {@code userMapper.selectRoleCodes(id).contains("ADMIN")} 做身份判定，而该方法带有
 * {@code AND r.status = 0} 过滤——那是「授权判定」的正确语义（停用角色不该再授权）。
 * 两者复用同一条查询导致语义错位：<b>ADMIN 角色一旦被停用，该查询返回空集，
 * 三条保护同时静默失效</b>，操作者随即可以禁用/删除/踢掉超管账号。
 *
 * <p>修复分两道闸：
 * <ol>
 *   <li>{@code RoleServiceImpl.update} 拦截停用内置超管角色（1026）——防止系统被锁死；</li>
 *   <li>身份判定改走 {@code UserMapper.existsRoleCode}（不过滤 r.status）——即使角色已因
 *       历史数据或直接改库而处于停用态，保护依然成立。</li>
 * </ol>
 *
 * <h3>本类的构造方式</h3>
 * 由于 1026 已封住接口路径，本类用 {@link #disableAdminRoleInDb()} <b>直接改库</b>构造
 * 「ADMIN 角色已被停用」的状态，以单独验证第二道闸——这正是纵深防御要防的场景。
 * 每个用例结束都无条件把角色恢复为启用（见 {@link #restoreAdminRoleStatus()}），
 * 避免污染其它用例与本地开发库。
 *
 * <h3>第三道闸：角色绑定不可被架空（1033）</h3>
 * 2026-09 补齐：{@code assignRoles} 是此前唯一<b>零保护</b>的生命周期入口——持有
 * {@code user:assign-role} 者可把内置超管的 ADMIN 角色整表清空（架空超管），
 * 也能给自己绑 ADMIN。现由 {@code ADMIN_USER_ROLE_IMMUTABLE(1033)} 拦截：
 * 目标一旦持有 ADMIN，其角色绑定对任何操作者都不可修改（与 1020/1021/1025 同源判定）。
 * 相关用例见本类「第三道闸」小节。
 *
 * <p><b>注意本类的构造约束</b>：这条闸同时封死了测试里「先授 ADMIN、再换成普通角色」的
 * 造数路径（{@link #nonAdminOperatorCannotDeleteSuperAdminWhenAdminRoleDisabled()} 因此改为
 * 「操作者从始至终不是超管」的等价构造）。新增用例不要再依赖"能给超管改角色"。
 */
class AdminProtectionTest extends BaseIntegrationTest {

    @Autowired
    private PermissionMapper permissionMapper;

    /** 无条件恢复：即使某个用例断言失败，也不能把停用态留给后续用例 */
    @AfterEach
    void restoreAdminRoleStatus() {
        jdbcTemplate.update("UPDATE sys_role SET status = 0 WHERE code = ?", ADMIN_ROLE_CODE);
    }

    // ================== helpers ==================

    /** 直接改库停用 ADMIN 角色：刻意绕过 1026 的接口拦截，用于验证第二道闸 */
    private void disableAdminRoleInDb() {
        jdbcTemplate.update("UPDATE sys_role SET status = 1 WHERE code = ?", ADMIN_ROLE_CODE);
    }

    private record AdminSession(String token, long userId, String email) {
    }

    /**
     * 注册 → 登录 → 绑定 ADMIN 角色。与基类 adminToken() 同一条真实链路，
     * 额外带出 userId（用例需要把某个超管当作「被操作的目标」）。
     */
    private AdminSession newAdmin(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        LoginSession session = loginGetAuth(email, "abc123");
        userService.assignRoles(session.userId(), List.of((long) adminRoleId()));
        return new AdminSession(session.token(), session.userId(), email);
    }

    /**
     * 预热操作者权限缓存。
     *
     * assignRoles 会发布缓存失效事件，操作者此时在 Redis 中没有权限缓存；
     * 必须先发一次请求让缓存按「角色尚未停用」的状态回填，之后直接改库停用角色
     * （不经过 service，因而不会触发失效）才能保持操作者权限不变。
     *
     * 这样做的目的是<b>隔离被测代码路径</b>：让操作者始终持有权限，
     * 用例断言的就只会是「身份判定是否失效」，而不会被授权链路的 403 掩盖。
     * 真实攻击路径（操作者另有一个非 ADMIN 角色提供权限）见
     * {@link #nonAdminOperatorCannotDeleteSuperAdminWhenAdminRoleDisabled()}。
     */
    private void warmUpAuthorization(String token) throws Exception {
        mockMvc.perform(get("/api/users").param("pageNum", "1").param("pageSize", "1")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
    }

    private int bodyCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    /** 调用 PUT /api/users/{id} 并返回业务码 */
    private int callUpdateUser(String token, long id, String email, int status) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/users/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"nickname\":\"n\",\"status\":" + status + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return bodyCode(result);
    }

    /** 创建角色，返回 roleId */
    private long createRole(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"测试角色-" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    /** 给角色分配权限 */
    private void assignPermissions(String token, long roleId, Long... permissionIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < permissionIds.length; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append(permissionIds[i]);
        }
        mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
    }

    private long permIdByCode(String code) {
        Permission p = permissionMapper.selectOne(
                new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
        if (p == null) {
            throw new IllegalStateException("未找到权限码 " + code + "，请先导入新版 init.sql");
        }
        return p.getId();
    }

    private String uniqueRoleCode() {
        return "test-role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 调用 PUT /api/users/{id}/roles 并返回业务码（roleIdsJson 已是 JSON 数组文本，如 "[1,2]"、"[]"） */
    private int callAssignRoles(String token, long userId, String roleIdsJson) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":" + roleIdsJson + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return bodyCode(result);
    }

    /** 目标用户的角色关联行数（机制层断言用） */
    private long relationCountOf(long userId) {
        return userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
    }

    /** 权限缓存键（与 PermissionCacheServiceImpl.KEY_PREFIX 一致；同 RbacCacheTest 的做法） */
    private String permissionCacheKey(long userId) {
        return "qsx:auth:perm:" + userId;
    }

    // ================== 第一道闸：接口拦截 ==================

    @Test
    @DisplayName("停用内置超管角色被拒绝（1026），且角色状态未被改动")
    void disablingAdminRoleRejected() throws Exception {
        AdminSession actor = newAdmin("admin");
        Role adminRole = roleMapper.selectById(adminRoleId());

        MvcResult result = mockMvc.perform(put("/api/roles/" + adminRoleId())
                        .header("Authorization", bearerHeader(actor.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + ADMIN_ROLE_CODE
                                + "\",\"name\":\"" + adminRole.getName()
                                + "\",\"description\":\"" + adminRole.getDescription()
                                + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(1026);
        assertThat(roleMapper.selectById(adminRoleId()).getStatus())
                .as("拦截必须发生在实体变更之前，角色应保持启用")
                .isZero();
    }

    @Test
    @DisplayName("回归：非内置角色仍可正常停用（1026 只拦 ADMIN，不误伤普通角色）")
    void nonAdminRoleCanStillBeDisabled() throws Exception {
        AdminSession actor = newAdmin("admin");
        long roleId = createRole(actor.token(), uniqueRoleCode());
        Role role = roleMapper.selectById(roleId);

        MvcResult result = mockMvc.perform(put("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(actor.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + role.getCode()
                                + "\",\"name\":\"" + role.getName()
                                + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(200);
        assertThat(roleMapper.selectById(roleId).getStatus())
                .as("1026 只拦内置超管角色，普通角色停用必须照常生效")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("回归：内置超管角色保持启用时仍可正常修改（1026 只拦停用，不拦编辑）")
    void adminRoleCanStillBeUpdatedWhileEnabled() throws Exception {
        AdminSession actor = newAdmin("admin");
        Role adminRole = roleMapper.selectById(adminRoleId());

        MvcResult result = mockMvc.perform(put("/api/roles/" + adminRoleId())
                        .header("Authorization", bearerHeader(actor.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + ADMIN_ROLE_CODE
                                + "\",\"name\":\"" + adminRole.getName()
                                + "\",\"description\":\"" + adminRole.getDescription()
                                + "\",\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(200);
        assertThat(roleMapper.selectById(adminRoleId()).getStatus()).isZero();
    }

    // ================== 第二道闸：身份判定不过滤角色状态 ==================

    @Test
    @DisplayName("机制层：existsRoleCode 不过滤角色状态，selectRoleCodes 过滤——两查询语义必须分离")
    void identityCheckIgnoresRoleStatusWhileAuthorizationDoesNot() throws Exception {
        Long userId = newAdmin("admin-probe").userId();

        assertThat(userMapper.selectRoleCodes(userId))
                .as("授权视图：角色启用时正常授予")
                .contains(ADMIN_ROLE_CODE);
        assertThat(userMapper.existsRoleCode(userId, ADMIN_ROLE_CODE)).isTrue();

        disableAdminRoleInDb();

        assertThat(userMapper.selectRoleCodes(userId))
                .as("授权视图：角色停用后不再授予任何角色（停用语义成立）")
                .isEmpty();
        assertThat(userMapper.existsRoleCode(userId, ADMIN_ROLE_CODE))
                .as("身份视图：角色停用不改变「是不是内置超管」，保护依据必须仍然成立")
                .isTrue();
    }

    @Test
    @DisplayName("ADMIN 角色被停用后，超管用户仍不可被禁用（1020）")
    void superAdminStillProtectedFromDisable() throws Exception {
        AdminSession actor = newAdmin("admin-actor");
        AdminSession target = newAdmin("admin-target");
        warmUpAuthorization(actor.token());

        disableAdminRoleInDb();

        assertThat(callUpdateUser(actor.token(), target.userId(), target.email(), 1))
                .as("停用角色不得成为绕过超管禁用保护的路径")
                .isEqualTo(1020);
    }

    @Test
    @DisplayName("ADMIN 角色被停用后，超管用户仍不可被删除（1025）")
    void superAdminStillProtectedFromDelete() throws Exception {
        AdminSession actor = newAdmin("admin-actor");
        AdminSession target = newAdmin("admin-target");
        warmUpAuthorization(actor.token());

        disableAdminRoleInDb();

        MvcResult result = mockMvc.perform(delete("/api/users/" + target.userId())
                        .header("Authorization", bearerHeader(actor.token())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(1025);
        assertThat(userMapper.selectById(target.userId()))
                .as("拦截必须发生在逻辑删除之前，超管账号仍应存在")
                .isNotNull();
    }

    @Test
    @DisplayName("ADMIN 角色被停用后，超管用户仍不可被强制登出（1021），会话键原样保留")
    void superAdminStillProtectedFromKick() throws Exception {
        AdminSession actor = newAdmin("admin-actor");
        AdminSession target = newAdmin("admin-target");
        warmUpAuthorization(actor.token());

        disableAdminRoleInDb();

        MvcResult result = mockMvc.perform(post("/api/users/" + target.userId() + "/kick")
                        .header("Authorization", bearerHeader(actor.token())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(1021);
        // 打到机制层：只断言业务码的话，一个「拦下了但顺手清了会话」的实现也能骗过测试
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(target.userId())))
                .as("超管会话不应被清理")
                .isTrue();
    }

    // ================== 端到端：真实攻击路径 ==================

    @Test
    @DisplayName("端到端：操作者仅持自定义角色(user:delete)，ADMIN 角色被停用后仍删不掉超管（1025）")
    void nonAdminOperatorCannotDeleteSuperAdminWhenAdminRoleDisabled() throws Exception {
        AdminSession target = newAdmin("admin-target");

        // 操作者从始至终不是超管：其权限只来自「仅带 user:delete」的自定义角色，
        // 因此不会随 ADMIN 角色停用而消失。这正是真实攻击路径——能把 ADMIN 角色
        // 弄停用的人，必定另有权限来源。
        // 注意：这里不能再用「先授 ADMIN、再把角色换成它」的老构造——那条路径已被
        // 「内置超管用户的角色不可修改（1033）」封死，见下面的第三道闸小节。
        String setupToken = adminToken();
        long roleId = createRole(setupToken, uniqueRoleCode());
        assignPermissions(setupToken, roleId, permIdByCode(PermissionConstants.USER_DELETE));

        LoginSession operator = loginGetAuth(uniqueEmail("operator"), "abc123");
        userService.assignRoles(operator.userId(), List.of(roleId));

        disableAdminRoleInDb();

        MvcResult result = mockMvc.perform(delete("/api/users/" + target.userId())
                        .header("Authorization", bearerHeader(operator.token())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyCode(result)).isEqualTo(1025);
        assertThat(userMapper.selectById(target.userId())).isNotNull();
        assertThat(relationCountOf(target.userId()))
                .as("保护发生在删除之前，目标的 ADMIN 关联原样保留")
                .isEqualTo(1);
    }

    // ================== 第三道闸：内置超管用户的角色绑定不可修改（1033） ==================

    @Test
    @DisplayName("给内置超管改角色被拒（1033），原 ADMIN 关联既不被删也不被换")
    void adminUserRolesImmutable() throws Exception {
        String actor = adminToken();
        AdminSession target = newAdmin("admin-target");
        long otherRole = createRole(actor, uniqueRoleCode());

        assertThat(callAssignRoles(actor, target.userId(), "[" + otherRole + "]")).isEqualTo(1033);

        // 机制层：拦截必须发生在整表替换之前——只断言业务码的话，
        // 一个「先删旧关联再报错」的实现也能骗过测试
        assertThat(relationCountOf(target.userId())).isEqualTo(1);
        assertThat(userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, target.userId())))
                .extracting(UserRole::getRoleId)
                .containsExactly(adminRoleId());
    }

    @Test
    @DisplayName("用空列表清空内置超管的角色同样被拒（1033）——架空超管的直接路径")
    void adminUserRolesImmutableWhenCleared() throws Exception {
        String actor = adminToken();
        AdminSession target = newAdmin("admin-target");
        // 预热目标的权限缓存：被拒的请求必须不产生任何副作用（含缓存抖动）
        warmUpAuthorization(target.token());
        assertThat(stringRedisTemplate.hasKey(permissionCacheKey(target.userId())))
                .as("前置：目标权限缓存已按「持有 ADMIN」回填")
                .isTrue();

        assertThat(callAssignRoles(actor, target.userId(), "[]"))
                .as("即便清空超管的角色被放行，超管也就此被架空——这是本闸要封的主路径")
                .isEqualTo(1033);

        assertThat(relationCountOf(target.userId())).isEqualTo(1);
        assertThat(stringRedisTemplate.hasKey(permissionCacheKey(target.userId())))
                .as("被拒的请求不应发布权限缓存失效事件")
                .isTrue();
    }

    @Test
    @DisplayName("优先级：对超管传不存在的角色 id 也返回 1033（而非 1009）")
    void adminUserRolesImmutableTakesPrecedenceOverRoleNotFound() throws Exception {
        String actor = adminToken();
        AdminSession target = newAdmin("admin-target");

        assertThat(callAssignRoles(actor, target.userId(), "[99999999]"))
                .as("闸在「校验目标角色均存在」之前：否则调用方会误以为换个角色 id 就能改超管")
                .isEqualTo(1033);
        assertThat(relationCountOf(target.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("ADMIN 角色被停用后，超管的角色绑定仍不可改（1033）——身份判定不过滤角色状态")
    void adminUserRolesImmutableWhenAdminRoleDisabled() throws Exception {
        String actor = adminToken();
        // 先预热操作者权限缓存，再直接改库停用 ADMIN 角色（隔离被测路径，见 warmUpAuthorization）
        warmUpAuthorization(actor);
        AdminSession target = newAdmin("admin-target");

        disableAdminRoleInDb();

        assertThat(callAssignRoles(actor, target.userId(), "[]"))
                .as("若身份判定误用 selectRoleCodes（带 status=0 过滤），此处会放行并架空超管")
                .isEqualTo(1033);
        assertThat(relationCountOf(target.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("正向：给普通用户授予 ADMIN 仍然放行（本闸只看目标）")
    void assignRolesToNonAdminStillAllowed() throws Exception {
        String actor = adminToken();
        LoginSession target = loginGetAuth(uniqueEmail("plain-target"), "abc123");

        // 本用例同时是一枚 tripwire：闸 2「授予 ADMIN 需操作者为超管」落地后，
        // 这里的期望值必须从 200 改为 1034（见 docs/question-list/03 的错误码预分配）
        assertThat(callAssignRoles(actor, target.userId(), "[" + adminRoleId() + "]")).isEqualTo(200);
        assertThat(relationCountOf(target.userId())).isEqualTo(1);
    }
}
