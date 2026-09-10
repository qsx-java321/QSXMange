package com.qsx.web.vo;

import com.qsx.domain.entity.Role;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图对象
 */
@Data
public class RoleVO {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 已分配的权限ID（仅详情返回） */
    private List<Long> permissionIds;

    public static RoleVO from(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setCode(role.getCode());
        vo.setName(role.getName());
        vo.setDescription(role.getDescription());
        vo.setStatus(role.getStatus());
        vo.setCreateTime(role.getCreateTime());
        vo.setUpdateTime(role.getUpdateTime());
        return vo;
    }
}