package com.qsx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qsx.domain.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 统计该角色编码的占用数，<b>含逻辑删除行</b>。
     *
     * 必须绕开 {@code @TableLogic} 的自动过滤：{@code uk_role_code} 是**物理**唯一索引
     * （不含 deleted 列），逻辑删除行仍然占用编码。若只在未删除行里判重，
     * 「删除角色 → 用同一编码重建」会绕过校验直撞唯一索引，返回 500 而非 1010。
     */
    @Select("SELECT COUNT(*) FROM sys_role WHERE code = #{code}")
    long countByCodeIncludeDeleted(@Param("code") String code);
}