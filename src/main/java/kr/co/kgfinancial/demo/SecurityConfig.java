package kr.co.kgfinancial.demo;

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
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username.toLowerCase())
                .map(account -> User.withUsername(account.getUsername())
                        .password(account.getPasswordHash()).roles(account.getRole()).build())
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("계정을 찾지 못했어요."));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http
            .csrf(config -> config.csrfTokenRepository(csrf))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/csrf", "/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"로그인했어요.\"}");
                })
                .failureHandler((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"아이디나 비밀번호를 확인해 주세요.\"}");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout"))
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

    // 개발자가 환경 변수로 이름과 긴 비밀번호를 직접 넣은 경우에만 관리자 계정을 만들어요.
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

@RestController
class CsrfController {
    // 웹에서 글을 바꾸기 전에 이 토큰을 받아 X-XSRF-TOKEN 헤더에 담아 보내요.
    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
}
