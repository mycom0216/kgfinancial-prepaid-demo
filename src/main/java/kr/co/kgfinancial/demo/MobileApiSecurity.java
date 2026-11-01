package kr.co.kgfinancial.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

// 휴대폰에서 보낸 Bearer 토큰을 확인해요. 맞는 토큰이면 기존 API의 로그인으로 인정해요.
class MobileBearerTokenFilter extends OncePerRequestFilter {
    private final MobileAccessTokenRepository tokens;
    private final UserDetailsService users;
    private final UserRepository accounts;

    MobileBearerTokenFilter(MobileAccessTokenRepository tokens, UserDetailsService users, UserRepository accounts) {
        this.tokens=tokens;
        this.users=users;
        this.accounts=accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header=request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header==null || !header.startsWith("Bearer ")) {
            chain.doFilter(request,response); // 일반 브라우저 세션은 원래 방식대로 이어가요.
            return;
        }

        String rawToken=header.substring("Bearer ".length()).trim();
        MobileAccessToken token=tokens.findByTokenHash(sha256(rawToken)).orElse(null);
        // 토큰이 없거나, 이미 로그아웃했거나, 시간이 지난 토큰이면 문을 열어주지 않아요.
        if (rawToken.isBlank() || token==null || token.getRevokedAt()!=null || !token.getExpiresAt().isAfter(Instant.now())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"모바일 로그인 토큰이 만료됐거나 올바르지 않아요.");
            return;
        }

        UserDetails account=users.loadUserByUsername(findUsername(token.getUserId()));
        // 확인된 계정의 역할(USER/ADMIN)도 그대로 넣어 기존 관리자 접근 제한이 계속 작동해요.
        var authentication=new UsernamePasswordAuthenticationToken(account,null,account.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request,response);
    }

    private String findUsername(Long userId) {
        // 앱 토큰에는 개인정보인 아이디를 넣지 않고 내부 번호만 넣어 두었어요.
        // API 권한 확인을 위해 내부 번호로 아이디를 찾아요.
        return accounts.findById(userId).orElseThrow().getUsername();
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("토큰 지문을 만들 수 없어요.",impossible); }
    }
}

// Bearer 토큰은 앱이 매 요청마다 직접 붙이는 값이라, 브라우저가 몰래 보내는 쿠키와 달라요.
// 그래서 토큰이 붙은 요청에 한해 CSRF 확인을 건너뛰어요.
class BearerOnlyRequestMatcher implements org.springframework.security.web.util.matcher.RequestMatcher {
    @Override public boolean matches(HttpServletRequest request) {
        String header=request.getHeader(HttpHeaders.AUTHORIZATION);
        return header!=null && header.startsWith("Bearer ");
    }
}

record MobileLoginRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=40) String username,
                          @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100) String password) { }
record MobileLoginResponse(String accessToken,String tokenType,Instant expiresAt) { }

// 휴대폰 앱의 회원가입, 로그인, 로그아웃을 맡아요.
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/mobile/auth")
class MobileAuthController {
    private final ApiController accountsApi;
    private final UserRepository accounts;
    private final MobileAccessTokenRepository tokens;
    private final org.springframework.security.authentication.AuthenticationManager authenticationManager;
    private final AuditRepository audits;
    private final long tokenTtlMillis;
    private final java.security.SecureRandom random=new java.security.SecureRandom();

    MobileAuthController(ApiController accountsApi, UserRepository accounts, MobileAccessTokenRepository tokens,
                         org.springframework.security.authentication.AuthenticationManager authenticationManager,
                         AuditRepository audits,
                         @org.springframework.beans.factory.annotation.Value("${app.mobile-token-ttl-ms:43200000}") long tokenTtlMillis) {
        this.accountsApi=accountsApi; this.accounts=accounts; this.tokens=tokens;
        this.authenticationManager=authenticationManager; this.audits=audits; this.tokenTtlMillis=tokenTtlMillis;
    }

    // 앱도 웹사이트와 같은 가입 규칙을 사용해요. 일반 사용자 계정으로만 가입할 수 있어요.
    @org.springframework.web.bind.annotation.PostMapping("/register")
    java.util.Map<String,String> register(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody RegisterRequest request) {
        return accountsApi.register(request);
    }

    // 로그인이 되면 긴 무작위 열쇠를 한 번 보여주고, 그 열쇠를 앱이 안전하게 보관해요.
    @org.springframework.web.bind.annotation.PostMapping("/login")
    @org.springframework.transaction.annotation.Transactional
    MobileLoginResponse login(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody MobileLoginRequest request) {
        String username=request.username()==null?"":request.username().trim().toLowerCase();
        try {
            // 아이디와 비밀번호 검사는 웹 로그인과 똑같은 Spring 보안 기능이 맡아요.
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username,request.password()));
        } catch (org.springframework.security.core.AuthenticationException badLogin) {
            if (!username.isBlank()) audits.save(new AuditLog(username.substring(0,Math.min(40,username.length())),"모바일 로그인 실패","mobile-token"));
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"아이디나 비밀번호를 확인해 주세요.");
        }

        UserAccount account=accounts.findByUsername(username).orElseThrow();
        byte[] randomBytes=new byte[32];
        random.nextBytes(randomBytes);
        String token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expires=Instant.now().plusMillis(tokenTtlMillis);
        // 원래 열쇠는 휴대폰에 한 번만 돌려주고, DB에는 SHA-256 지문만 보관해요.
        tokens.save(new MobileAccessToken(account.getId(),sha256(token),expires));
        audits.save(new AuditLog(username,"모바일 로그인 성공","mobile-token"));
        return new MobileLoginResponse(token,"Bearer",expires);
    }

    // 로그아웃하면 DB의 토큰을 폐기해요. 휴대폰이 열쇠를 다시 보내도 더는 쓸 수 없어요.
    @org.springframework.web.bind.annotation.PostMapping("/logout")
    @org.springframework.transaction.annotation.Transactional
    org.springframework.http.ResponseEntity<Void> logout(
            @org.springframework.web.bind.annotation.RequestHeader(org.springframework.http.HttpHeaders.AUTHORIZATION) String authorization,
            org.springframework.security.core.Authentication auth) {
        String raw=authorization.substring("Bearer ".length()).trim();
        tokens.findByTokenHash(sha256(raw)).ifPresent(token->token.revoke());
        audits.save(new AuditLog(auth.getName(),"모바일 로그아웃","mobile-token"));
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("토큰 지문을 만들 수 없어요.",impossible); }
    }
}

