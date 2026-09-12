package com.qsx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qsx.domain.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 批量插入（Excel 导入用），create_time/update_time 由数据库 NOW() 填充，deleted 固定 0
     */
    @Insert("<script>" +
            "INSERT INTO sys_user(email, password, nickname, status, create_time, update_time, deleted) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.email}, #{item.password}, #{item.nickname}, #{item.status}, NOW(), NOW(), 0)" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<User> list);

    /**
     * 查询用户的全部角色码（仅未删除角色）
     */
    @Select("SELECT r.code FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /**
     * 查询用户拥有的全部权限码（经角色-权限关联，仅未删除角色/权限）
     */
    @Select("SELECT DISTINCT p.code FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.id AND r.deleted = 0 " +
            "JOIN sys_role_permission rp ON rp.role_id = r.id " +
            "JOIN sys_permission p ON rp.permission_id = p.id AND p.deleted = 0 " +
            "WHERE ur.user_id = #{userId}")
    List<String> selectPermissionCodes(@Param("userId") Long userId);
}