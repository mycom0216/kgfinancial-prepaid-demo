package kr.co.kgfinancial.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.function.Supplier;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username.toLowerCase())
                .map(account -> User.withUsername(account.getUsername())
                        .password(account.getPasswordHash()).roles(account.getRole()).build())
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("계정을 찾지 못했어요."));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AuditRepository audits) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http
            .csrf(config -> config.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new BrowserCsrfHandler())
                    // 결제사는 브라우저에서 서버로 결과를 보내므로 이 주소만 CSRF 검사를 건너뛰어요.
                    .ignoringRequestMatchers("/api/payments/inicis/next"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/csrf", "/api/payments/inicis/next", "/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> {
                    audits.save(new AuditLog(authentication.getName(), "로그인 성공", "session"));
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"로그인했어요.\"}");
                })
                .failureHandler((request, response, exception) -> {
                    String attemptedName = request.getParameter("username");
                    if (attemptedName != null && !attemptedName.isBlank()) {
                        audits.save(new AuditLog(attemptedName.substring(0, Math.min(40, attemptedName.length())), "로그인 실패", "session"));
                    }
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"아이디나 비밀번호를 확인해 주세요.\"}");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (authentication != null) audits.save(new AuditLog(authentication.getName(), "로그아웃", "session"));
                    response.setStatus(204);
                }))
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                (request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"먼저 로그인해 주세요.\"}");
                }, new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON)))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // 관리자 계정은 환경 변수로 이름과 긴 비밀번호를 준 경우에만 만들어요.
    @Bean CommandLineRunner bootstrapAdmin(UserRepository users, PasswordEncoder passwords,
            @Value("${app.admin.username:}") String username,
            @Value("${app.admin.password:}") String password) {
        return args -> {
            if (!username.isBlank() && password.length() >= 16 && !users.existsByUsername(username.toLowerCase())) {
                users.save(new UserAccount(username.toLowerCase(), passwords.encode(password), "ADMIN"));
            }
        };
    }
}

// 브라우저가 쿠키에서 받은 토큰을 헤더에 담아 보냈는지 확인해요.
class BrowserCsrfHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
    private final XorCsrfTokenRequestAttributeHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> token) {
        masked.handle(request, response, token);
        token.get(); // 브라우저가 쓸 토큰 쿠키가 만들어지도록 해요.
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        if (StringUtils.hasText(request.getHeader(token.getHeaderName()))) {
            return plain.resolveCsrfTokenValue(request, token);
        }
        return masked.resolveCsrfTokenValue(request, token);
    }
}

@RestController
class CsrfController {
    // 글을 바꾸기 전에 이 토큰을 받아 X-XSRF-TOKEN 헤더에 담아 보내요.
    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
}

