package com.qsx.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.common.constant.RoleConstants;
import com.qsx.common.constant.UserConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.service.UserImportExportService;
import com.qsx.web.dto.excel.ImportResult;
import com.qsx.web.dto.excel.UserExportRow;
import com.qsx.web.dto.excel.UserImportRow;
import com.qsx.web.dto.query.UserQuery;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Excel 批量导入导出服务实现
 *
 * 设计要点：
 * - 文件不落盘：导入用 MultipartFile 输入流流式解析，导出/模板直接写 HttpServletResponse 响应流
 * - 导入采用"整批校验 + 整体拒绝"：全部数据行合法才单事务落库，否则返回全部错误行明细
 * - 默认密码统一（import.default-password），BCrypt 加密后复用同一 hash（大批量导入性能考虑）
 */
@Service
public class UserImportExportServiceImpl implements UserImportExportService {

    /** 单次导入最大行数（防内存与超时） */
    private static final int MAX_IMPORT_ROWS = 1000;

    /** 单次导出最大行数 */
    private static final int MAX_EXPORT_ROWS = 50000;

    /** 邮箱格式（与 DTO @Email 校验对齐） */
    private static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${import.default-password}")
    private String defaultPassword;

    public UserImportExportServiceImpl(UserMapper userMapper,
                                       UserRoleMapper userRoleMapper,
                                       RoleMapper roleMapper,
                                       PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType(EXCEL_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=user_import_template.xlsx");
        EasyExcel.write(response.getOutputStream())
                .head(UserImportRow.class)
                .sheet("用户导入模板")
                .doWrite(Collections.emptyList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importUsers(MultipartFile file) throws IOException {
        // 1. 文件校验：非空 + 扩展名白名单
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "上传文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.endsWith(".xlsx") || filename.endsWith(".xls"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "仅支持 .xlsx / .xls 格式的 Excel 文件");
        }

        // 2. 流式解析（SAX 模式，不落盘）
        List<UserImportRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in)
                    .head(UserImportRow.class)
                    .registerReadListener(new UserImportListener(rows))
                    .sheet()
                    .doRead();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.IMPORT_VALIDATE_FAILED.getCode(),
                    "Excel 文件解析失败，请检查文件内容：" + e.getMessage());
        }

        // 3. 数据量校验
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.IMPORT_VALIDATE_FAILED.getCode(), "Excel 中没有可导入的数据行");
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new BusinessException(ResultCode.IMPORT_DATA_TOO_LARGE.getCode(),
                    "单次最多导入 " + MAX_IMPORT_ROWS + " 行，当前 " + rows.size() + " 行");
        }

        // 4. 一次查库：已占用邮箱（未逻辑删除） + 角色编码 → ID 映射
        List<String> emails = rows.stream()
                .map(UserImportRow::getEmail)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        Set<String> existingEmails = emails.isEmpty() ? Set.of()
                : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getEmail, emails))
                        .stream().map(User::getEmail).collect(Collectors.toSet());

        Set<String> roleCodes = collectRoleCodes(rows);
        Map<String, Role> roleByCode = new HashMap<>();
        if (!roleCodes.isEmpty()) {
            roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getCode, roleCodes))
                    .forEach(role -> roleByCode.put(role.getCode(), role));
        }

        // 5. 逐行校验，收集全部错误（不中断）
        List<String> errors = new ArrayList<>();
        Set<String> fileEmails = new HashSet<>();
        for (UserImportRow row : rows) {
            validateRow(row, existingEmails, fileEmails, roleByCode, errors);
        }

        // 6. 整批拒绝：任一数据行不合法则不落任何数据
        if (!errors.isEmpty()) {
            ImportResult failed = new ImportResult();
            failed.setSuccessCount(0);
            failed.setErrors(errors);
            return failed;
        }

        // 7. 单事务批量落库：先批量插用户，再按邮箱查回 ID 组装角色关联
        String encodedPassword = passwordEncoder.encode(defaultPassword);
        LocalDateTime now = LocalDateTime.now();
        List<User> users = new ArrayList<>(rows.size());
        for (UserImportRow row : rows) {
            User user = new User();
            user.setEmail(row.getEmail().trim());
            user.setPassword(encodedPassword);
            user.setNickname(StringUtils.hasText(row.getNickname())
                    ? row.getNickname().trim() : UserConstants.defaultNickname(row.getEmail().trim()));
            user.setStatus(resolveStatus(row.getStatus()));
            users.add(user);
        }
        userMapper.insertBatch(users);

        Map<String, User> userByEmail = userMapper.selectList(
                        new LambdaQueryWrapper<User>().in(User::getEmail, emails))
                .stream().collect(Collectors.toMap(User::getEmail, u -> u));

        List<UserRole> userRoles = new ArrayList<>();
        for (UserImportRow row : rows) {
            String email = row.getEmail().trim();
            User saved = userByEmail.get(email);
            if (saved == null) {
                throw new BusinessException(ResultCode.IMPORT_VALIDATE_FAILED.getCode(),
                        "导入回查用户失败：" + email);
            }
            if (StringUtils.hasText(row.getRoleCodes())) {
                Set<Long> roleIds = new HashSet<>();
                for (String code : row.getRoleCodes().split(",")) {
                    if (StringUtils.hasText(code.trim())) {
                        roleIds.add(roleByCode.get(code.trim()).getId());
                    }
                }
                for (Long roleId : roleIds) {
                    UserRole ur = new UserRole();
                    ur.setUserId(saved.getId());
                    ur.setRoleId(roleId);
                    ur.setCreateTime(now);
                    userRoles.add(ur);
                }
            }
        }
        if (!userRoles.isEmpty()) {
            userRoleMapper.insertBatch(userRoles);
        }

        ImportResult result = new ImportResult();
        result.setSuccessCount(users.size());
        result.setErrors(Collections.emptyList());
        return result;
    }

    @Override
    public void exportUsers(UserQuery query, HttpServletResponse response) throws IOException {
        // 与用户分页查询一致的筛选条件，不分页全量导出
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .like(StringUtils.hasText(query.getEmail()), User::getEmail, query.getEmail())
                .like(StringUtils.hasText(query.getNickname()), User::getNickname, query.getNickname())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .orderByDesc(User::getId);
        List<User> users = userMapper.selectList(wrapper);
        if (users.size() > MAX_EXPORT_ROWS) {
            throw new BusinessException(ResultCode.IMPORT_DATA_TOO_LARGE.getCode(),
                    "导出数据量超过 " + MAX_EXPORT_ROWS + " 条限制，请缩小筛选范围");
        }

        // 批量组装角色编码（两次查询避免 N+1）
        Map<Long, List<String>> roleCodesByUser = loadRoleCodesByUser(users);

        List<UserExportRow> rows = users.stream().map(user -> {
            UserExportRow row = new UserExportRow();
            row.setEmail(user.getEmail());
            row.setNickname(user.getNickname());
            row.setStatus(user.getStatus() != null && user.getStatus() == 1 ? "禁用" : "正常");
            row.setRoleCodes(String.join(",", roleCodesByUser.getOrDefault(user.getId(), Collections.emptyList())));
            row.setCreateTime(user.getCreateTime() == null ? "" : user.getCreateTime().format(DATE_TIME_FORMATTER));
            return row;
        }).collect(Collectors.toList());

        response.setContentType(EXCEL_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        String filename = "users_" + LocalDateTime.now().format(FILE_NAME_FORMATTER) + ".xlsx";
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
        EasyExcel.write(response.getOutputStream())
                .head(UserExportRow.class)
                .sheet("用户列表")
                .doWrite(rows);
    }

    // ---------- 私有辅助 ----------

    private Set<String> collectRoleCodes(List<UserImportRow> rows) {
        Set<String> roleCodes = new HashSet<>();
        for (UserImportRow row : rows) {
            if (StringUtils.hasText(row.getRoleCodes())) {
                for (String code : row.getRoleCodes().split(",")) {
                    if (StringUtils.hasText(code.trim())) {
                        roleCodes.add(code.trim());
                    }
                }
            }
        }
        return roleCodes;
    }

    private void validateRow(UserImportRow row,
                             Set<String> existingEmails,
                             Set<String> fileEmails,
                             Map<String, Role> roleByCode,
                             List<String> errors) {
        String email = row.getEmail() == null ? "" : row.getEmail().trim();
        if (!StringUtils.hasText(email)) {
            errors.add("第 " + row.getRowNum() + " 行：邮箱不能为空");
        } else if (!email.matches(EMAIL_REGEX)) {
            errors.add("第 " + row.getRowNum() + " 行：邮箱格式不正确");
        } else if (email.length() > UserConstants.EMAIL_MAX) {
            // 与三个写路径 DTO 的 @Size 同一上限：导入是绕过 DTO 的第二条写路径。
            // 少了这道校验，超长邮箱会拖到 INSERT 阶段才撞列宽（1406 → 整批 500），
            // 而不是给出可定位到行的错误；EMAIL_REGEX 本身不限制总长度
            errors.add("第 " + row.getRowNum() + " 行：邮箱长度不能超过" + UserConstants.EMAIL_MAX);
        } else if (existingEmails.contains(email)) {
            errors.add("第 " + row.getRowNum() + " 行：邮箱已存在：" + email);
        } else if (!fileEmails.add(email)) {
            errors.add("第 " + row.getRowNum() + " 行：邮箱在文件中重复：" + email);
        }

        String statusStr = row.getStatus() == null ? "" : row.getStatus().trim();
        if (StringUtils.hasText(statusStr) && !"0".equals(statusStr) && !"1".equals(statusStr)) {
            errors.add("第 " + row.getRowNum() + " 行：状态只能为 0(正常) 或 1(禁用)");
        }

        if (StringUtils.hasText(row.getRoleCodes())) {
            for (String code : row.getRoleCodes().split(",")) {
                // 闸：内置超管角色禁止出现在导入行。导入的初始口令是配置里的公开口令
                // （import.default-password），放进 ADMIN 等同于批量签发已知口令的超管账号。
                // 放在「编码不存在」判断之前：本判断不依赖库中数据，ADMIN 角色即使被停用/删除也照样拦得住。
                // 大小写变体（如 "admin"）不从此处拦截，而是落到下面的「编码不存在」——因为 roleByCode 的键
                // 取自库中原样（ADMIN），而 HashMap 大小写敏感。后果同样是 1017 整批拒绝，见测试注释：
                // 不要"顺手"把下面的校验改成不区分大小写，那会让取用时的 roleByCode.get(code) 返回 null 而 NPE。
                if (RoleConstants.ADMIN.equals(code.trim())) {
                    errors.add("第 " + row.getRowNum() + " 行：禁止将内置超管角色分配给导入用户");
                    break; // 该行只报一次，沿用既有「角色码一行一错」语义
                }
                if (StringUtils.hasText(code.trim()) && !roleByCode.containsKey(code.trim())) {
                    errors.add("第 " + row.getRowNum() + " 行：角色编码不存在：" + code.trim());
                    break;
                }
            }
        }
    }

    private Integer resolveStatus(String status) {
        return StringUtils.hasText(status) && "1".equals(status.trim()) ? 1 : 0;
    }

    private Map<Long, List<String>> loadRoleCodesByUser(List<User> users) {
        Map<Long, List<String>> roleCodesByUser = new HashMap<>();
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return roleCodesByUser;
        }
        List<UserRole> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds));
        List<Long> roleIds = relations.stream().map(UserRole::getRoleId).distinct().collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return roleCodesByUser;
        }
        Map<Long, String> roleCodeById = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode));
        for (UserRole relation : relations) {
            String code = roleCodeById.get(relation.getRoleId());
            if (code != null) {
                roleCodesByUser.computeIfAbsent(relation.getUserId(), k -> new ArrayList<>()).add(code);
            }
        }
        return roleCodesByUser;
    }

    /**
     * EasyExcel 读取监听器：收集数据行并记录 Excel 行号，跳过全空行
     */
    private static class UserImportListener implements ReadListener<UserImportRow> {

        private final List<UserImportRow> rows;

        UserImportListener(List<UserImportRow> rows) {
            this.rows = rows;
        }

        @Override
        public void invoke(UserImportRow data, AnalysisContext context) {
            if (data == null
                    || (!StringUtils.hasText(data.getEmail())
                    && !StringUtils.hasText(data.getNickname())
                    && !StringUtils.hasText(data.getStatus())
                    && !StringUtils.hasText(data.getRoleCodes()))) {
                return; // 跳过全空行
            }
            // rowIndex 为 0-based 物理行索引（表头为 0，数据行从 1 起），Excel 行号 = rowIndex + 1
            data.setRowNum(context.readRowHolder().getRowIndex() + 1);
            rows.add(data);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 无需处理
        }
    }
}
