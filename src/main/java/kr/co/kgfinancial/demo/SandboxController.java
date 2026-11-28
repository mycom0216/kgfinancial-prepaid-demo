package kr.co.kgfinancial.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Profile("sandbox")
@RestController
@RequestMapping("/api/sandbox")
class SandboxController {
    private final ApiController cards;

    SandboxController(ApiController cards, DataSource dataSource, Environment environment) throws SQLException {
        if (environment.matchesProfiles("postgres")) {
            throw new IllegalStateException("sandbox와 postgres 프로필을 동시에 실행할 수 없습니다.");
        }
        try (var connection = dataSource.getConnection()) {
            if (!"jdbc:h2:mem:kgdemo_sandbox".equals(connection.getMetaData().getURL())) {
                throw new IllegalStateException("sandbox는 전용 H2 메모리 DB가 필요합니다.");
            }
        }
        this.cards = cards;
    }

    @GetMapping
    Map<String, Boolean> status() {
        return Map.of("enabled", true);
    }

    @PostMapping("/cards/{id}/top-up")
    CardView topUp(@PathVariable Long id,
                   @RequestHeader("Idempotency-Key") @Size(min = 10, max = 100) String key,
                   @Valid @RequestBody AmountRequest request, Authentication auth) {
        return cards.addPracticeFunds(id, key, request.amount(), auth);
    }
}
