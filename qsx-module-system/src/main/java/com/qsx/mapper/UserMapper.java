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
     * 批量插入（Excel 导入用），create_time/update_time 由数据库 NOW() 填充，deleted 固定 0。
     *
     * <p><b>列清单是显式枚举的，不是 {@code BaseMapper} 生成的</b>：给 {@code User} 加字段时
     * 必须同步加进这里，否则该字段会被**静默丢弃**（走 DB 默认值，不报错）。导入用户的
     * {@code must_change_password} 就依赖本行——漏改的后果是"导入账号不被强制改密"，
     * 一个不会有任何报错的安全缺口。
     *
     * <p>取值走 {@code #{item.mustChangePassword}} 而不是写死 1：让实体始终是唯一真源。
     * 两种漏法的后果**不同**，别把前者当成后者：
     * <ul>
     *   <li>列清单里**没有这一列** → 取 DB 默认值 0，**静默通过**（实测：12 个导入用例里
     *       只有那条专门断言标志的会红）；</li>
     *   <li>列在、实体字段为 null → {@code Field 'must_change_password' doesn't have a default value}（1048），
     *       当场失败。</li>
     * </ul>
     */
    @Insert("<script>" +
            "INSERT INTO sys_user(email, password, nickname, status, must_change_password, create_time, update_time, deleted) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.email}, #{item.password}, #{item.nickname}, #{item.status}, #{item.mustChangePassword}, NOW(), NOW(), 0)" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<User> list);

    /**
     * 查询用户的全部角色码（仅未删除**且未停用**的角色）——<b>授权判定专用</b>。
     *
     * 必须过滤 status：角色「停用」的语义就是不再授予权限，
     * 若此处不过滤，停用只会清缓存、回源结果不变，管理端的停用形同虚设。
     *
     * <b>不要用它做内置身份判定</b>（如「是不是超管」），那是 {@link #existsRoleCode} 的职责。
     */
    @Select("SELECT r.code FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 0")
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /**
     * 判断用户是否持有指定角色编码——<b>身份判定专用，刻意不过滤 r.status</b>。
     *
     * 与 {@link #selectRoleCodes} 的分工必须严格区分，两者回答的是不同问题：
     * <ul>
     *   <li>selectRoleCodes：「该用户<b>实际拥有</b>哪些权限」——按 r.status 过滤是正确语义；</li>
     *   <li>本方法：「该用户<b>是不是</b>某个内置身份」——身份不因角色被停用而改变，
     *       因此<b>不能</b>过滤 r.status。</li>
     * </ul>
     *
     * 若身份判定误用 selectRoleCodes：一旦 ADMIN 角色被停用，该查询返回空集，
     * 「超管不可禁用(1020)/不可强制登出(1021)/不可删除(1025)」三条保护会<b>同时静默失效</b>——
     * 等于给了操作者一条「先停用超管角色、再处置超管账号」的绕过路径。
     * 新增 ADMIN 角色停用拦截（1026）是第一道闸，本方法是即使角色已被停用也仍然生效的第二道闸。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.code = #{code} AND r.deleted = 0)")
    boolean existsRoleCode(@Param("userId") Long userId, @Param("code") String code);

    /**
     * 查询用户拥有的全部权限码（经角色-权限关联，仅未删除**且未停用**的角色/未删除权限）
     */
    @Select("SELECT DISTINCT p.code FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.id AND r.deleted = 0 AND r.status = 0 " +
            "JOIN sys_role_permission rp ON rp.role_id = r.id " +
            "JOIN sys_permission p ON rp.permission_id = p.id AND p.deleted = 0 " +
            "WHERE ur.user_id = #{userId}")
    List<String> selectPermissionCodes(@Param("userId") Long userId);
}