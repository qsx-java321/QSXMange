package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.constant.RoleConstants;
import com.qsx.common.constant.UserConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.framework.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.security.event.PermissionCacheEvictEvent;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.security.session.AuthSessionService;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.UserService;
import com.qsx.web.dto.query.UserQuery;
import com.qsx.web.dto.request.UserCreateRequest;
import com.qsx.web.dto.request.UserUpdateRequest;
import com.qsx.web.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private static final String DELETED_EMAIL_SUFFIX_PREFIX = "#deleted_";

    /**
     * 清理会话；Redis 异常仅告警不阻断主流程（调用方语义优先）
     */
    private void removeSessionQuietly(Long userId) {
        try {
            authSessionService.remove(userId);
        } catch (Exception e) {
            log.warn("清理会话失败, userId={}", userId, e);
        }
    }

    /**
     * 把会话清理推迟到事务提交之后执行（无事务时立即执行）。
     *
     * 与权限缓存失效（AFTER_COMMIT 事件）保持同一时机：事务回滚时不应留下
     * 「用户没被删掉、会话却已被清空」的不一致状态。
     */
    private void removeSessionAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    removeSessionQuietly(userId);
                }
            });
        } else {
            removeSessionQuietly(userId);
        }
    }

    /**
     * 请求是否在禁用账号：<b>非 0 即禁用</b>。
     * 必须与 {@code SecurityUser.isEnabled()}（status==0 才放行）以及
     * {@code AuthServiceImpl.refresh} 的查库兜底用同一谓词，否则会出现
     * 「按 A 谓词禁用、按 B 谓词清理」的错位。
     */
    private static boolean isDisabling(Integer status) {
        return status != null && status != 0;
    }

    /**
     * 目标用户是否持有内置超管角色——<b>身份判定，与授权判定严格区分</b>。
     *
     * 必须走 {@code existsRoleCode}（不过滤 sys_role.status），不能用
     * {@code selectRoleCodes}：后者按 {@code r.status = 0} 过滤，ADMIN 角色一旦被停用
     * 即返回空集，会让 1020/1021/1025 三条保护<b>同时静默失效</b>——
     * 停用角色等于反手拆掉超管保护，操作者随后就能删/禁用/踢掉超管账号。
     *
     * 这与 {@link #isDisabling} 是同一类教训：判定谓词必须与它要回答的问题同源，
     * 复用一条为「授权」设计的查询去做「身份」判定，语义错位就会变成保护缺口。
     */
    private boolean isBuiltInAdmin(Long userId) {
        return userMapper.existsRoleCode(userId, RoleConstants.ADMIN);
    }

    /** 内置超管角色的 ID（按角色码查，不硬编码 id）；角色不存在时返回 null（此时也谈不上授予） */
    private Long builtInAdminRoleId() {
        Role admin = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, RoleConstants.ADMIN));
        return admin == null ? null : admin.getId();
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthSessionService authSessionService;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           ApplicationEventPublisher eventPublisher,
                           AuthSessionService authSessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.eventPublisher = eventPublisher;
        this.authSessionService = authSessionService;
    }

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .like(StringUtils.hasText(query.getEmail()), User::getEmail, query.getEmail())
                .like(StringUtils.hasText(query.getNickname()), User::getNickname, query.getNickname())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .orderByDesc(User::getId);

        Page<User> page = userMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        return PageResult.of(page.convert(UserVO::from));
    }

    @Override
    public UserVO getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return UserVO.from(user);
    }

    @Override
    public UserVO create(UserCreateRequest request) {
        // 邮箱唯一性校验
        if (getByEmail(request.getEmail()) != null) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname() : UserConstants.defaultNickname(request.getEmail()));
        user.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        // 后台建号的口令是管理员设定的，对管理员而言是已知口令 ⇒ 与导入同性质，强制首次改密
        user.setMustChangePassword(Boolean.TRUE);
        userMapper.insert(user);
        return UserVO.from(user);
    }

    @Override
    public UserVO update(Long id, UserUpdateRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 邮箱被修改时校验唯一性（排除自己）
        if (!request.getEmail().equals(user.getEmail())) {
            User existing = getByEmail(request.getEmail());
            if (existing != null && !existing.getId().equals(id)) {
                throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
            }
        }

        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());

        // 禁用保护：请求禁用时——禁止禁用自己、超管不可禁用，防止误操作锁死系统。
        // 判定用「非 0 即禁用」，与 SecurityUser.isEnabled()（status==0 才放行）保持同一谓词：
        // 两套谓词错位时，写非 0/1 的其它值可绕过保护并跳过会话清理（DTO 已限 0/1，此处为纵深防御）
        if (isDisabling(request.getStatus())) {
            if (id.equals(SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
            }
            if (isBuiltInAdmin(id)) {
                throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_DISABLE);
            }
        }
        user.setStatus(request.getStatus());
        userMapper.updateById(user);

        // 禁用即踢下线：清理会话三键，旧 access token 立即失效。
        // 注意谓词是「非 0 即禁用」而非「== 1」：普通资料编辑带 status=0 不会触发，
        // 而任何能真正禁用账号的取值都必须清理会话，否则解冻后旧令牌会复活。
        // 仅告警不阻断：禁用语义优先，且每请求查库 isEnabled() 是第二道防线
        if (isDisabling(request.getStatus())) {
            removeSessionQuietly(id);
        }
        return UserVO.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 删除保护（与 update/kick 同类保护，避免误删导致失去对系统的掌控）：
        // 不允许删除自己（自己的账号由自己处理）；内置超管不可删除（与不可禁用/不可强制登出一致）
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        if (isBuiltInAdmin(id)) {
            throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_DELETE);
        }

        // 释放邮箱：在逻辑删除前，将原邮箱拼接删除时间戳后缀，
        // 避免与原邮箱的唯一索引冲突，从而允许同一个邮箱再次注册
        String suffixedEmail = user.getEmail() + DELETED_EMAIL_SUFFIX_PREFIX + System.currentTimeMillis();
        user.setEmail(suffixedEmail);
        userMapper.updateById(user);

        // 逻辑删除（deleted -> 1）
        userMapper.deleteById(id);

        // 清理会话（at/rt/session 三键）；Redis 异常仅告警，不阻断删除（删除语义优先）。
        // 放在事务提交之后执行：与权限缓存失效（AFTER_COMMIT 事件）保持同一时机——
        // 若在事务内执行，回滚会让「其实没被删除的用户」被登出
        removeSessionAfterCommit(id);

        // 事务提交后失效该用户权限缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUser(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        // 用户存在性校验
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 自锁保护：不允许给自己改角色（与禁用自己/删除自己/踢自己同为 1022）。
        // 管理端「清空角色」是常规操作，一次点击就可能把自己架空；而且非超管给自己加角色
        // 还是一条自我提权路径（闸 2 只拦 ADMIN，拦不住"给自己补一个带 user:delete 的角色"）。
        // 位置与 update/delete/kick 一致：存在性 → 自己 → 内置资产，
        // 故错误码优先级为 1004 → 1022 → 1033 → 1034 → 1009。
        //
        // 无操作者上下文（程序内直调 service，如测试造数）时**跳过**本闸——没有"自己"可言；
        // HTTP 路径必有操作者，真实流量始终受保护。注意这与闸 2 的取向刻意相反：
        // 闸 2 面对"不知道操作者是谁"是 fail-closed（拒绝授予 ADMIN），
        // 本闸面对同样情形是"不成立"（无人可比对），两者都是各自问题的正确答案。
        // 操作者只取一次，下面的闸 2 复用同一个值。
        Long operatorId = SecurityUtils.getCurrentUserIdOrNull();
        if (operatorId != null && userId.equals(operatorId)) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        // 闸：内置超管用户的角色绑定不可被任何操作者修改——与「不可禁用(1020)/不可强制登出(1021)/
        // 不可删除(1025)」对称，补上此前唯一零保护的生命周期入口。
        // 传空列表「清空角色」同样被覆盖：本闸在整表替换之前、与 roleIds 内容无关。
        // 身份判定走 isBuiltInAdmin → existsRoleCode（不过滤 sys_role.status），故 ADMIN 角色被停用后保护依然成立。
        // 注意判定的是「目标是否持有 ADMIN」，不是「目标是否为预置账号」：因此任何自建超管也一视同仁，
        // 想给超管换角色只能改库——这是与 1011/1026 同口径的有意设计。
        // 错误码优先级：1004（用户不存在）→ 1033（目标超管）→ 1009（角色不存在）。本闸刻意排在
        // 「校验目标角色均存在」之前：否则"对超管 + 传错角色 id"会返回 1009，让人误以为改个参数就能成功。
        // 已知残留：existsRoleCode 是快照读，与并发写之间存在 check-then-act 窗口（见 question-list #27），本批不处理。
        if (isBuiltInAdmin(userId)) {
            throw new BusinessException(ResultCode.ADMIN_USER_ROLE_IMMUTABLE);
        }
        // 闸 2：授予内置超管角色（ADMIN）必须由「本身持有 ADMIN」的操作者发起。
        // 闸 1 只看**目标**（防架空既有超管），本闸补上**操作者**侧——否则持有 user:assign-role 的
        // 普通管理员可以给自己或他人绑 ADMIN，等于自助提权（docs/question-list/01 #1 的残留面）。
        // 无操作者上下文（程序内直调 service）一律按"不是超管"处理：该路径不可能来自匿名 HTTP
        //（控制器需要 user:assign-role 鉴权），fail-closed 更安全，且不会把语义搅成 401。
        // roleIds 为 null 表示"清空角色"，不含授予语义，故先判非空。
        if (roleIds != null && !roleIds.isEmpty()) {
            Long adminRoleId = builtInAdminRoleId();
            if (adminRoleId != null && roleIds.contains(adminRoleId)) {
                // 复用上面已取的 operatorId（同一个操作者，不重复取）
                if (operatorId == null || !isBuiltInAdmin(operatorId)) {
                    throw new BusinessException(ResultCode.ADMIN_GRANT_REQUIRES_ADMIN);
                }
            }
        }
        // 校验目标角色均存在
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (roleMapper.selectById(roleId) == null) {
                    throw new BusinessException(ResultCode.ROLE_NOT_FOUND);
                }
            }
        }
        // 整表替换：先物理删旧，再批量插新
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            List<UserRole> list = roleIds.stream()
                    .distinct()
                    .map(roleId -> {
                        UserRole ur = new UserRole();
                        ur.setUserId(userId);
                        ur.setRoleId(roleId);
                        ur.setCreateTime(now);
                        return ur;
                    })
                    .collect(Collectors.toList());
            userRoleMapper.insertBatch(list);
        }

        // 事务提交后失效该用户权限缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void kick(Long id) {
        if (userMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 不允许踢自己（操作者的会话由自己登出）
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        // 内置超管不可被强制登出，避免误操作导致系统失去掌控
        if (isBuiltInAdmin(id)) {
            throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_KICK);
        }
        // 清理目标会话（at/rt/session 三键）：旧 access token 立即失效；
        // Redis 异常不静默（fail-closed），由全局兜底返回 500
        authSessionService.remove(id);
    }

    @Override
    public User getByEmail(String email) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    @Override
    public void save(User user) {
        userMapper.insert(user);
    }

    @Override
    public void updatePassword(Long id, String encodedPassword) {
        User user = new User();
        user.setId(id);
        user.setPassword(encodedPassword);
        // 改密即解除"必须先改密"：这里是**改密与忘记密码重置的唯一写库入口**
        //（AuthServiceImpl 的两个通道都调它），所以一处赋值覆盖双通道。
        // 必须显式传 FALSE 而非留 null：MyBatis-Plus 默认 NOT_NULL 字段策略会把 null
        // 排除在 SET 子句之外，标志将永远清不掉。与 password 同一条 UPDATE，无中间态。
        user.setMustChangePassword(Boolean.FALSE);
        userMapper.updateById(user);
    }
}