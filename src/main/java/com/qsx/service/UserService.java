package com.qsx.service;

import com.qsx.common.result.PageResult;
import com.qsx.domain.entity.User;
import com.qsx.web.dto.query.UserQuery;
import com.qsx.web.dto.request.UserCreateRequest;
import com.qsx.web.dto.request.UserUpdateRequest;
import com.qsx.web.vo.UserVO;

import java.util.List;

/**
 * 用户管理服务
 */
public interface UserService {

    /**
     * 分页查询用户
     */
    PageResult<UserVO> page(UserQuery query);

    /**
     * 查询用户详情
     */
    UserVO getById(Long id);

    /**
     * 新增用户
     */
    UserVO create(UserCreateRequest request);

    /**
     * 修改用户
     */
    UserVO update(Long id, UserUpdateRequest request);

    /**
     * 删除用户（逻辑删除，删除时释放邮箱）
     */
    void delete(Long id);

    /**
     * 为用户分配角色（整表替换）
     */
    void assignRoles(Long userId, List<Long> roleIds);

    /**
     * 管理员强制登出：删除目标用户的刷新会话（不改变账号状态）
     */
    void kick(Long id);

    /**
     * 按邮箱查询用户（未被逻辑删除）
     */
    User getByEmail(String email);

    /**
     * 保存用户（新增）
     */
    void save(User user);

    /**
     * 修改密码（不更新其他字段）
     */
    void updatePassword(Long id, String encodedPassword);
}