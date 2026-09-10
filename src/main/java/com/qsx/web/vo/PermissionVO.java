package com.qsx.web.vo;

import com.qsx.domain.entity.Permission;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限视图对象
 */
@Data
public class PermissionVO {

    private Long id;
    private String code;
    private String name;
    private String type;
    private Integer sort;
    private LocalDateTime createTime;

    public static PermissionVO from(Permission permission) {
        PermissionVO vo = new PermissionVO();
        vo.setId(permission.getId());
        vo.setCode(permission.getCode());
        vo.setName(permission.getName());
        vo.setType(permission.getType());
        vo.setSort(permission.getSort());
        vo.setCreateTime(permission.getCreateTime());
        return vo;
    }
}