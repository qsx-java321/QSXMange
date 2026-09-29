package com.qsx.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.common.log.AccessLogCommand;
import com.qsx.common.log.AccessLogRecorder;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.security.constant.SecurityConstants;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.service.SecurityUserDetailsService;
import com.qsx.security.session.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 令牌认证过滤器：以 Redis 为 access token 有效性的唯一真相源。
 *
 * 每请求一次 Redis GET 反查 userId，再实时查库加载用户，因此
 * 登出 / 踢人 / 禁用 / 删除 / 改密之后，旧 access token **立即**失效
 * （改造前 JWT 是无状态的，只能等 30 分钟自然过期）。
 *
 * fail-closed：Redis 异常一律拒绝认证（绝不降级放行）。
 *
 * <p>本过滤器同时承载两道「认证之后的准入判定」，理由都是「用户行本来就每请求实时查库，
 * 判定因此天然即时、且零额外开销」：
 * <ol>
 *   <li>账号是否被禁用（{@code isEnabled()}）→ 判未认证，交给 401 处理链；</li>
 *   <li><b>是否仍在使用初始口令</b>（1037）→ 只放行 {@code /auth/} 前缀，其余直接拒绝。
 *       详见 {@link #requiresPasswordChange}。</li>
 * </ol>
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private final AuthSessionService authSessionService;
    private final SecurityUserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final AccessLogRecorder accessLogRecorder;

    public TokenAuthenticationFilter(AuthSessionService authSessionService,
                                     SecurityUserDetailsService userDetailsService,
                                     ObjectMapper objectMapper,
                                     AccessLogRecorder accessLogRecorder) {
        this.authSessionService = authSessionService;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
        this.accessLogRecorder = accessLogRecorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        // 守卫：SecurityContext 已有认证时不再处理。
        // 本类是 @Component，Boot 会把它同时注册为普通 Servlet 过滤器，
        // 该守卫是阻止同一请求被重复查 Redis + 重复查库的那道闸
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Long userId = authSessionService.findUserIdByAccessToken(token);
                if (userId == null) {
                    // 令牌不存在 = 从未签发 / 已过期 / 已被吊销（踢人、登出、禁用、删除、改密、单端覆盖）
                    log.debug("access token 无效或已吊销，按未认证处理");
                } else {
                    SecurityUser userDetails = userDetailsService.loadUserById(userId);
                    if (userDetails.isEnabled()) {
                        // 强制首次改密闸：仍在使用初始口令的账号，除 /auth/** 外一律拒绝。
                        // 位置刻意放在 isEnabled() 为真的**分支内**——放到外层会让已禁用/已删除
                        // 用户拿到 1037 而不是 401，破坏"禁用即未认证"的既有语义。
                        // 每请求零额外开销：用户行本来就是每请求实时查库的（见 loadUserById）。
                        if (requiresPasswordChange(userDetails, request)) {
                            rejectForPasswordChange(request, response, userDetails.getAccount());
                            return;
                        }
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        log.debug("账号已被禁用，按未认证处理, userId={}", userId);
                    }
                }
            } catch (UsernameNotFoundException e) {
                // 令牌有效但用户已被删除，同样按未认证处理
                log.debug("令牌对应的用户不存在，按未认证处理");
            } catch (Exception e) {
                // 基础设施异常（典型是 Redis 不可达）：fail-closed 拒绝认证。
                // 必须 ERROR 留痕——否则线上 Redis 故障与「令牌集体过期」在日志里完全无法区分。
                // 同时打标记，让 401 处理器跳过操作日志落库（故障期每条请求都 401，逐条写库会
                // 把 Redis 的局部故障放大成数据库写风暴）
                log.error("认证链路异常（Redis 不可达？），本次请求按未认证处理", e);
                request.setAttribute(SecurityConstants.AUTH_INFRA_ERROR_ATTR, Boolean.TRUE);
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 是否因「仍在使用初始口令」而拒绝本次请求。
     *
     * <p><b>豁免面是整个 {@code /auth/} 前缀</b>，这不是图省事，而是唯一不会锁死用户的选择：
     * 本过滤器对 {@code permitAll} 端点同样执行（它们也在安全链里过一遍），所以若只豁免
     * 改密 / 登出 / 当前用户三个接口，那么标志为 1 的用户会连「刷新令牌」「发本人验证码
     * 走通道 B 改密」「忘记密码重置」都被挡住——**AT 一过期就再也拿不到新令牌，密码永远
     * 改不了**。用前缀而非枚举清单还有一层好处：将来新增 {@code /auth} 端点自动获得豁免，
     * 不会再制造同类死锁。
     *
     * <p>豁免只决定"拦不拦"，不决定"认不认证"：{@code /auth/me}、{@code /auth/logout}、
     * {@code /auth/change-password}、{@code /auth/captcha}(CHANGE_PASSWORD 场景) 仍然依赖
     * 本过滤器填充 SecurityContext。因此**绝不能**改用 {@code shouldNotFilter()} 实现豁免
     * ——那会让过滤器对整个 {@code /auth/**} 失效，这些接口会集体退化成 401。
     *
     * <p>OPTIONS 一并放行：跨域预检不带业务语义，拦下它只会让前端在改密页上连请求都发不出。
     */
    private boolean requiresPasswordChange(SecurityUser userDetails, HttpServletRequest request) {
        AuthUserAccount account = userDetails.getAccount();
        if (account == null || !account.mustChangePassword()) {
            return false;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return false;
        }
        // 与 OperationLogAspect 的 /api/logs 排除用同一写法（getRequestURI 含 context-path，
        // 本应用未配置 context-path，故路径即 /auth/xxx）
        return !request.getRequestURI().startsWith(SecurityConstants.AUTH_MODULE_PREFIX);
    }

    /**
     * 以「HTTP 200 + body 业务码 1037」拒绝请求，并补记一条审计。
     *
     * <p>HTTP 状态取 200 是刻意的：这是业务状态（还没改密），不是鉴权失败，与
     * 「业务失败一律 HTTP 200 + body 业务码」的主约定一致；403 继续只表示「已登录但无权限」。
     * 响应写法照抄 {@code RestAuthenticationEntryPoint}——这里是过滤器，抛异常不会经过
     * {@code @RestControllerAdvice}，必须自己写响应；而 {@code setCharacterEncoding} 不能省，
     * 缺了它 {@code getWriter()} 会用 ISO-8859-1，中文 message 变乱码、断言全部失败。
     *
     * <p>审计：本请求连 {@code filterChain.doFilter} 都不进，切面（包级切点）覆盖不到，
     * 因此在这里补记——与 401/403/400 三处补记同族。**不需要**像 401 那样加降噪开关：
     * 能命中本闸的前提是「持合法令牌且标志为 1」，量级等于待改密账号数，不存在 Redis 故障
     * 那种 100% 请求都命中的放大条件。
     */
    private void rejectForPasswordChange(HttpServletRequest request,
                                         HttpServletResponse response,
                                         AuthUserAccount account) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ResultCode.PASSWORD_CHANGE_REQUIRED)));

        AccessLogCommand command = new AccessLogCommand();
        // 身份只能从 account 取：本方法刻意**没有**写 SecurityContext（不写才不会让下游把它
        // 当成一次已认证的请求），所以照抄两个 handler 里那句 SecurityContextHolder 取到的会是 null
        if (account != null) {
            command.setUserId(account.id());
            command.setUsername(account.email());
        }
        command.setHttpStatus(200);
        command.setSuccess(0);
        command.setErrorMsg(ResultCode.PASSWORD_CHANGE_REQUIRED.getMessage());
        command.setMethod(request.getMethod());
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        command.setUrl(queryString == null ? uri : uri + "?" + queryString);
        command.setCostMs(0);
        accessLogRecorder.record(command);
    }

    /**
     * 从请求头提取原始令牌
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(SecurityConstants.HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return bearerToken.substring(SecurityConstants.TOKEN_PREFIX.length());
        }
        return null;
    }
}
