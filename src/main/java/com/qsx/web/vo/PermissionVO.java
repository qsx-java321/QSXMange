package com.qsx.web.vo;

import com.qsx.domain.entity.Permission;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限视图对象（含菜单树形 children）
 */
@Data
public class PermissionVO {

    private Long id;
    private String code;
    private String name;
    private String type;
    private Long parentId;
    private String path;
    private String component;
    private String icon;
    private Integer visible;
    private Integer sort;
    private LocalDateTime createTime;

    /** 子节点（菜单树形结构） */
    private List<PermissionVO> children;

    public static PermissionVO from(Permission permission) {
        PermissionVO vo = new PermissionVO();
        vo.setId(permission.getId());
        vo.setCode(permission.getCode());
        vo.setName(permission.getName());
        vo.setType(permission.getType());
        vo.setParentId(permission.getParentId());
        vo.setPath(permission.getPath());
        vo.setComponent(permission.getComponent());
        vo.setIcon(permission.getIcon());
        vo.setVisible(permission.getVisible());
        vo.setSort(permission.getSort());
        vo.setCreateTime(permission.getCreateTime());
        return vo;
    }
}
