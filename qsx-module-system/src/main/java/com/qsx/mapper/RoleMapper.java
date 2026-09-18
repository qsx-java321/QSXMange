package com.qsx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qsx.domain.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}