package com.qsx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qsx.domain.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权限 Mapper
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 统计该菜单/权限标识的占用数，<b>含逻辑删除行</b>。
     *
     * 同 {@link RoleMapper#countByCodeIncludeDeleted}：{@code uk_perm_code} 是物理唯一索引，
     * 逻辑删除的菜单仍占用标识，判重必须绕开 {@code @TableLogic}，
     * 否则「删除菜单 → 用同一标识重建」会返回 500 而非 1016。
     */
    @Select("SELECT COUNT(*) FROM sys_permission WHERE code = #{code}")
    long countByCodeIncludeDeleted(@Param("code") String code);
}