package com.qsx.security.port;

import java.util.List;

/**
 * 读取用户角色码 / 权限码的端口（权限缓存未命中时的回源查询）
 *
 * <p>由业务模块提供实现，直接委托 UserMapper 的同名查询。
 *
 * <p><b>实现必须原样保留 SQL 的过滤条件</b>，尤其是 selectRoleCodes 的
 * {@code r.status = 0}：角色「停用」的语义就是不再授予权限，若实现里去掉了该条件，
 * 停用角色只会清掉缓存、回源结果却不变，管理端的停用将形同虚设。
 */
public interface UserAuthorityRepository {

    /** 用户的全部角色码（仅未删除且未停用的角色）——授权判定专用 */
    List<String> selectRoleCodes(Long userId);

    /** 用户拥有的全部权限码（仅未删除且未停用的角色/权限） */
    List<String> selectPermissionCodes(Long userId);
}