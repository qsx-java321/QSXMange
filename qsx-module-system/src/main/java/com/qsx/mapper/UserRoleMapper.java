package com.qsx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qsx.domain.entity.UserRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色关联 Mapper
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 批量插入（整表替换用），create_time 由业务层显式赋值
     */
    @Insert("<script>" +
            "INSERT INTO sys_user_role(user_id, role_id, create_time) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.userId}, #{item.roleId}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<UserRole> list);

    /**
     * 查询持有指定角色的全部用户 ID（权限缓存失效用）
     */
    @Select("SELECT DISTINCT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询持有指定权限（经角色间接持有）的全部用户 ID（权限缓存失效用，单条联表替代两级反查）
     */
    @Select("SELECT DISTINCT ur.user_id FROM sys_user_role ur " +
            "JOIN sys_role_permission rp ON rp.role_id = ur.role_id " +
            "WHERE rp.permission_id = #{permissionId}")
    List<Long> selectUserIdsByPermissionId(@Param("permissionId") Long permissionId);
}