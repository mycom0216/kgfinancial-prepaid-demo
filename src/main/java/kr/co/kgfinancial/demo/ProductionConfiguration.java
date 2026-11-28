package kr.co.kgfinancial.demo;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("production")
class ProductionConfiguration {
    ProductionConfiguration(Environment environment,
                            @Value("${spring.datasource.url}") String databaseUrl,
                            @Value("${inicis.mode}") String mode,
                            @Value("${inicis.mid}") String mid,
                            @Value("${inicis.hash-key}") String hashKey,
                            @Value("${app.public-url}") String publicUrl) {
        if (environment.matchesProfiles("sandbox") || !databaseUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("운영 프로필은 PostgreSQL을 사용해야 합니다.");
        }
        if (!"production".equals(mode) || mid.isBlank() || hashKey.isBlank()) {
            throw new IllegalStateException("운영 결제에는 INICIS_MODE=production, INICIS_MID, INICIS_HASH_KEY가 필요합니다.");
        }
        if (!validPublicUrl(publicUrl)) {
            throw new IllegalStateException("APP_PUBLIC_URL에는 외부 HTTPS 서비스 주소를 입력하세요.");
        }
    }

    private static boolean validPublicUrl(String value) {
        if (value.length() > 400) return false;
        try {
            URI origin = URI.create(value);
            return "https".equals(origin.getScheme()) && origin.getHost() != null
                    && !"localhost".equalsIgnoreCase(origin.getHost())
                    && origin.getUserInfo() == null && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && (origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
