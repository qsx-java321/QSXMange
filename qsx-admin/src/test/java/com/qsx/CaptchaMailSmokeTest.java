package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 端到端冒烟：验证码邮件**真的**经 SMTP 投递到了本地 Mailpit，且正文里带验证码。
 *
 * <p>这是 P0 的验收标准之一，覆盖 `@Async` 投递 + 真实 SMTP + 邮件正文组装这条链路——
 * 其余用例都只读 Redis 里的码，不会碰到发信这一段。
 *
 * <p>Mailpit 未启动时整类跳过（{@code assumeTrue}）而不是失败：它属于本地开发设施，
 * 缺席不应把测试基线染红；但**默认全绿不代表这条链路被验证过**，见到 SKIPPED 请起容器。
 */
class CaptchaMailSmokeTest extends BaseIntegrationTest {

    private static final String MAILPIT_BASE = "http://127.0.0.1:8025";
    /** 列表与清空（复数） */
    private static final String MESSAGES_API = MAILPIT_BASE + "/api/v1/messages";
    /** 单封详情（**单数**，复数路径会 404 并返回纯文本 "File not found"） */
    private static final String MESSAGE_API = MAILPIT_BASE + "/api/v1/message/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    @BeforeEach
    void requireMailpit() {
        Assumptions.assumeTrue(mailpitReachable(), "Mailpit 未启动，跳过端到端投递冒烟");
        // 清掉历史邮件：否则断言可能命中上一轮遗留的信
        httpClient.sendAsync(request("DELETE", MESSAGES_API), HttpResponse.BodyHandlers.ofString()).join();
    }

    @Test
    @DisplayName("验证码邮件经 SMTP 投递到 Mailpit，且正文包含 6 位验证码")
    void send_deliversMailToMailpit() throws Exception {
        String email = uniqueEmail("cap-mail");

        mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"REGISTER\",\"email\":\"" + email + "\"}"))
                .andReturn();

        String code = stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email));
        assertThat(code).as("发码接口应已把验证码写入 Redis").isNotNull();

        String body = awaitMessageBody(email);

        assertThat(body)
                .as("邮件正文应包含验证码")
                .contains(code);
        assertThat(body).contains("验证码");
    }

    // ---------- 辅助 ----------

    /** 轮询等待邮件到达（投递是异步的，接口返回不代表已送达） */
    private String awaitMessageBody(String recipient) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            String listJson = httpClient.send(request("GET", MESSAGES_API),
                    HttpResponse.BodyHandlers.ofString()).body();
            JsonNode messages = listJson.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(listJson);
            for (JsonNode message : messages.path("messages")) {
                if (!recipient.equals(message.path("To").path(0).path("Address").asText())) {
                    continue;
                }
                String detailJson = httpClient.send(
                        request("GET", MESSAGE_API + message.path("ID").asText()),
                        HttpResponse.BodyHandlers.ofString()).body();
                return objectMapper.readTree(detailJson).path("Text").asText("");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("5 秒内未在 Mailpit 收到发给 " + recipient + " 的邮件（检查容器是否在跑、"
                + "spring.mail.host/port 是否指向 localhost:1025）");
    }

    private boolean mailpitReachable() {
        try {
            HttpResponse<String> response = httpClient.send(request("GET", MESSAGES_API),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpRequest request(String method, String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
