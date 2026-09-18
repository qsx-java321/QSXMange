package com.qsx.security.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RBAC 权限缓存数据载体：用户拥有的角色码与权限码（仅缓存权限，不含用户行）
 * 作为 Redis value 以 JSON 序列化存储，key 为 qsx:auth:perm:{userId}
 */
@Data
public class PermissionCacheData {

    /** 角色码（如 ADMIN），authority 中统一加 ROLE_ 前缀 */
    private List<String> roles = new ArrayList<>();

    /** 权限码（如 user:page），authority 中原样返回 */
    private List<String> permissions = new ArrayList<>();
}
