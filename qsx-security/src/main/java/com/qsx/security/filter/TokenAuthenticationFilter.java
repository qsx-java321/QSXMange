package com.qsx.security.filter;

import com.qsx.security.constant.SecurityConstants;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.service.SecurityUserDetailsService;
import com.qsx.security.session.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 令牌认证过滤器：以 Redis 为 access token 有效性的唯一真相源。
 *
 * 每请求一次 Redis GET 反查 userId，再实时查库加载用户，因此
 * 登出 / 踢人 / 禁用 / 删除 / 改密之后，旧 access token **立即**失效
 * （改造前 JWT 是无状态的，只能等 30 分钟自然过期）。
 *
 * fail-closed：Redis 异常一律拒绝认证（绝不降级放行）。
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private final AuthSessionService authSessionService;
    private final SecurityUserDetailsService userDetailsService;

    public TokenAuthenticationFilter(AuthSessionService authSessionService,
                                     SecurityUserDetailsService userDetailsService) {
        this.authSessionService = authSessionService;
        this.userDetailsService = userDetailsService;
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
