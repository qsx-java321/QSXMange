package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.OperationLog;
import com.qsx.mapper.OperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志测试：成功/业务失败/未登录401/越权403 记录，分页查询，删除与清空。
 *
 * 说明：日志为异步落库，等待逻辑基于“匹配条件的日志出现”而非计数，
 * 以规避全量测试下其他用例迟到异步日志的干扰。
 */
class LogTest extends BaseIntegrationTest {

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanLogs() {
        jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    private long logCount() {
        Long c = operationLogMapper.selectCount(null);
        return c == null ? 0 : c;
    }

    /** 拉取全部日志（按 id 倒序） */
    private List<OperationLog> allLogs() {
        return operationLogMapper.selectList(
                        new LambdaQueryWrapper<OperationLog>().orderByDesc(OperationLog::getId))
                .stream().collect(Collectors.toList());
    }

    /** 轮询等待：在超时内出现首条满足条件的日志并返回，否则抛错 */
    private OperationLog awaitAnyMatch(Predicate<OperationLog> predicate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            List<OperationLog> logs = allLogs();
            if (logs.stream().anyMatch(predicate)) {
                return logs.stream().filter(predicate).findFirst().orElseThrow();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("未在 8s 内等到匹配的操作日志");
    }

    @Test
    @DisplayName("业务接口成功调用被记录为成功日志")
    void success_request_recorded() throws Exception {
        String token = adminToken();
        cleanLogs();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        OperationLog log = awaitAnyMatch(l ->
                l.getUrl() != null && l.getUrl().contains("/api/users")
                        && l.getMethod().equals("GET") && l.getSuccess() == 1);
        assertThat(log.getUserId()).isNotNull();
        assertThat(log.getHttpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("超长 URL 仍被记录（截断到列宽），不会整条审计丢失")
    void longUrl_truncatedButRecorded() throws Exception {
        String token = adminToken();
        cleanLogs();
        // 在 URL 后面挂一长串查询参数：不截断的话 MySQL 会以 1406 拒绝整行写入，
        // 异常被 record() 的 catch 吞掉 ⇒ 这次请求在审计里彻底消失，
        // 等于给了"靠长参数让自己不留痕"的规避手段（docs/question-list #23）
        //
        // 注意必须把查询串**写进 URL**（而不是用 .param()）：MockMvc 的 param() 只填参数表，
        // request.getQueryString() 仍为 null，而切面记录的正是 uri + "?" + queryString
        String longKeyword = "x".repeat(400);

        mockMvc.perform(get("/api/users?email=" + longKeyword)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        OperationLog log = awaitAnyMatch(l ->
                l.getUrl() != null && l.getUrl().startsWith("/api/users?email=x"));
        assertThat(log.getUrl())
                .as("截断到 url 列宽（VARCHAR(255)），保住「这次请求发生过」这一底线")
                .hasSize(255);
        assertThat(log.getUrl()).startsWith("/api/users?email=");
        assertThat(log.getSuccess()).isEqualTo(1);
    }

    @Test
    @DisplayName("业务失败（登录密码错误1002）被记录为失败日志")
    void business_failure_recorded() throws Exception {
        cleanLogs();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("log") + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk());

        OperationLog log = awaitAnyMatch(l ->
                l.getUrl() != null && l.getUrl().contains("/auth/login")
                        && l.getSuccess() == 0 && l.getErrorMsg() != null);
        assertThat(log.getSuccess()).isZero();
    }

    @Test
    @DisplayName("未登录访问受保护接口：401 由 Security 层补记失败日志")
    void unauth_401_recorded() throws Exception {
        cleanLogs();

        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());

        OperationLog log = awaitAnyMatch(l ->
                l.getHttpStatus() == 401 && l.getSuccess() == 0
                        && l.getUrl() != null && l.getUrl().contains("/api/users"));
        assertThat(log.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("普通用户越权访问返回 403，切面记录失败日志")
    void forbidden_403_recorded() throws Exception {
        cleanLogs();
        String normalToken = registerAndLoginGetToken(uniqueEmail("logforbid"), "abc123");
        cleanLogs(); // 清掉注册/登录日志，使 403 请求独立可断言

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(normalToken)))
                .andExpect(status().isForbidden());

        OperationLog log = awaitAnyMatch(l ->
                l.getHttpStatus() == 403 && l.getSuccess() == 0
                        && l.getUrl() != null && l.getUrl().contains("/api/users"));
        assertThat(log.getHttpStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("日志自带分页查询接口可用")
    void log_pagination_query() throws Exception {
        String token = adminToken();
        cleanLogs();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token))).andExpect(status().isOk());
        mockMvc.perform(get("/api/roles/all").header("Authorization", bearerHeader(token))).andExpect(status().isOk());
        awaitAnyMatch(l -> l.getUrl() != null && l.getUrl().contains("/api/roles/all"));

        String result = mockMvc.perform(get("/api/logs")
                        .header("Authorization", bearerHeader(token))
                        .param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(result);
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("records").size()).isGreaterThanOrEqualTo(1);
        assertThat(json.path("data").path("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("支持按 id 删除与批量清空日志")
    void delete_and_clear_log() throws Exception {
        String token = adminToken();
        cleanLogs();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token))).andExpect(status().isOk());
        OperationLog target = awaitAnyMatch(l ->
                l.getUrl() != null && l.getUrl().contains("/api/users"));

        // 按 id 删除
        mockMvc.perform(delete("/api/logs/" + target.getId()).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        assertThat(operationLogMapper.selectById(target.getId())).isNull();

        // 清空（该请求本身被 AOP 排除不记录，等待异步队列排空后归零）
        mockMvc.perform(delete("/api/logs").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && logCount() > 0) {
            Thread.sleep(100);
        }
        assertThat(logCount()).isZero();
    }

    @Test
    @DisplayName("日志分页筛选：url / success / method / username / 时间段")
    void log_filters() throws Exception {
        String token = adminToken();
        cleanLogs();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/99999999").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        OperationLog ok = awaitAnyMatch(l ->
                l.getUrl() != null && l.getUrl().equals("/api/users") && l.getSuccess() == 1);
        awaitAnyMatch(l -> l.getUrl() != null && l.getUrl().contains("/api/users/99999999"));

        // 失败记录的 url 只被本用例触发，可精确断言 url + success + method 三个条件同时生效
        JsonNode failed = queryLogs(token, "url", "/api/users/99999999", "success", "0", "method", "GET");
        assertThat(failed.path("code").asInt()).isEqualTo(200);
        assertThat(failed.path("data").path("total").asLong()).isEqualTo(1);
        JsonNode failedRow = failed.path("data").path("records").get(0);
        assertThat(failedRow.path("success").asInt()).isZero();
        assertThat(failedRow.path("method").asText()).isEqualTo("GET");
        assertThat(failedRow.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(failedRow.path("errorMsg").asText()).isNotEmpty();
        assertThat(failedRow.path("costMs").asInt()).isGreaterThanOrEqualTo(0);

        // 成功筛选不应把失败记录带出来
        JsonNode succeeded = queryLogs(token, "url", "/api/users", "success", "1");
        assertThat(succeeded.path("data").path("total").asLong()).isGreaterThanOrEqualTo(1);
        succeeded.path("data").path("records")
                .forEach(node -> assertThat(node.path("success").asInt()).isEqualTo(1));

        // 操作人模糊筛选
        JsonNode byUser = queryLogs(token, "username", ok.getUsername(), "pageSize", "50");
        assertThat(byUser.path("data").path("total").asLong()).isGreaterThanOrEqualTo(1);
        byUser.path("data").path("records")
                .forEach(node -> assertThat(node.path("username").asText()).isEqualTo(ok.getUsername()));

        // 时间段：起点置于未来无匹配，起点置于过去有匹配
        String future = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().plusDays(1));
        String past = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().minusDays(1));
        assertThat(queryLogs(token, "beginTime", future).path("data").path("total").asLong()).isZero();
        assertThat(queryLogs(token, "beginTime", past).path("data").path("total").asLong())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("日志接口：未登录 401、普通用户 403，且成功调用不写自身审计日志")
    void log_endpoints_authn_authz() throws Exception {
        cleanLogs();

        mockMvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/logs/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/logs")).andExpect(status().isUnauthorized());

        String userToken = registerAndLoginGetToken(uniqueEmail("logplain"), "abc123");
        mockMvc.perform(get("/api/logs").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/logs/1").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/logs").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());

        // 管理员正常调用日志接口：切面显式排除 /api/logs，成功调用不应产生「日志的日志」
        String admin = adminToken();
        mockMvc.perform(get("/api/logs").header("Authorization", bearerHeader(admin)))
                .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(allLogs().stream().noneMatch(l ->
                l.getUrl() != null && l.getUrl().startsWith("/api/logs") && l.getSuccess() == 1)).isTrue();
    }

    /** 以键值对形式调用日志分页接口（params 为 key,value,key,value...） */
    private JsonNode queryLogs(String token, String... params) throws Exception {
        var request = get("/api/logs").header("Authorization", bearerHeader(token));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}