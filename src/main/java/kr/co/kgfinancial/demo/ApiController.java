package kr.co.kgfinancial.demo;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 웹에서 들어오는 글을 담는 상자예요. 사용자는 가입할 때 관리자 역할을 정할 수 없어요.
record RegisterRequest(@NotBlank @Size(min = 3, max = 40) @Pattern(regexp = "[a-zA-Z0-9_.-]+") String username,
                       @NotBlank @Size(min = 12, max = 100) String password) { }
record AmountRequest(@jakarta.validation.constraints.Positive @jakarta.validation.constraints.Max(1000000000L) long amount) { }
record CardView(Long id, long balance) { }
record CardCreated(Long id, long balance) { }
record CsrfView(String token) { }

@RestController
@Validated
@RequestMapping("/api")
class ApiController {
    private final UserRepository users;
    private final CardRepository cards;
    private final TransactionRepository transactions;
    private final AuditRepository audits;
    private final PasswordEncoder passwords;
    private final boolean demoTopupEnabled;

    ApiController(UserRepository users, CardRepository cards, TransactionRepository transactions,
                  AuditRepository audits, PasswordEncoder passwords,
                  @Value("${demo.topup-enabled:false}") boolean demoTopupEnabled) {
        this.users = users; this.cards = cards; this.transactions = transactions;
        this.audits = audits; this.passwords = passwords; this.demoTopupEnabled = demoTopupEnabled;
    }

    // 가입 정보는 아이디와 비밀번호만 받아요. 실제 이름이나 전화번호는 묻지 않아요.
    @PostMapping("/auth/register")
    @Transactional
    Map<String, String> register(@Valid @RequestBody RegisterRequest request) {
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 해요.");
        }
        String username = request.username().toLowerCase();
        if (users.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디예요.");
        }
        users.save(new UserAccount(username, passwords.encode(request.password()), "USER"));
        audits.save(new AuditLog(username, "가입", "account"));
        return Map.of("message", "가입했어요. 이제 아이디와 비밀번호로 로그인할 수 있어요.");
    }

    // 로그인한 계정의 아이디와 역할을 확인해요. 비밀번호는 돌려주지 않아요.
    @GetMapping("/auth/me")
    Map<String, String> me(Authentication auth) {
        return Map.of("username", auth.getName(), "role", auth.getAuthorities().iterator().next().getAuthority());
    }

    @PostMapping("/cards")
    @Transactional
    CardCreated createCard(Authentication auth) {
        UserAccount user = findUser(auth.getName());
        DemoCard card = cards.save(new DemoCard(user.getId()));
        audits.save(new AuditLog(auth.getName(), "카드 만들기", "card:" + card.getId()));
        return new CardCreated(card.getId(), card.getBalance());
    }

    @GetMapping("/cards")
    List<CardView> myCards(Authentication auth) {
        UserAccount user = findUser(auth.getName());
        return cards.findByOwnerIdOrderByIdDesc(user.getId()).stream()
                .map(card -> new CardView(card.getId(), card.getBalance())).toList();
    }

    // 매번 다른 Idempotency-Key를 보내세요. 같은 키를 다시 보내면 잔액은 한 번만 바뀌어요.
    @PostMapping("/cards/{id}/charge")
    @Transactional
    CardView charge(@PathVariable Long id, @RequestHeader("Idempotency-Key") @Size(min=10, max=100) String key,
                    @Valid @RequestBody AmountRequest request, Authentication auth) {
        if (!demoTopupEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "가짜 충전은 꺼져 있어요. 이니시스 결제를 사용해 주세요.");
        return changeBalance(id, key, request.amount(), "충전", auth);
    }

    @PostMapping("/cards/{id}/pay")
    @Transactional
    CardView pay(@PathVariable Long id, @RequestHeader("Idempotency-Key") @Size(min=10, max=100) String key,
                 @Valid @RequestBody AmountRequest request, Authentication auth) {
        return changeBalance(id, key, request.amount(), "연습 결제", auth);
    }

    @GetMapping("/cards/{id}/transactions")
    List<DemoTransaction> history(@PathVariable Long id, Authentication auth) {
        DemoCard card = findOwnedCard(id, auth.getName());
        return transactions.findByCardIdOrderByCreatedAtDesc(card.getId());
    }

    // 이 주소는 관리자 역할이 있는 계정만 열 수 있어요.
    @GetMapping("/admin/cards")
    @Transactional
    List<CardView> allCards(Authentication auth) {
        audits.save(new AuditLog(auth.getName(), "관리자 카드 목록 조회", "all-cards"));
        return cards.findAll().stream().map(card -> new CardView(card.getId(), card.getBalance())).toList();
    }

    @GetMapping("/admin/audit-logs")
    List<AuditLog> auditLogs() { return audits.findTop100ByOrderByCreatedAtDesc(); }

    private CardView changeBalance(Long id, String key, long amount, String kind, Authentication auth) {
        UserAccount user = findUser(auth.getName());
        DemoCard card = findOwnedCard(id, auth.getName());
        String safeKey = hashKey(key);
        DemoTransaction previous = transactions.findByUserIdAndIdempotencyKey(user.getId(), safeKey).orElse(null);
        if (previous != null) {
            // 같은 키로 다른 행동이나 금액을 보내면 헷갈리지 않게 거절해요.
            if (!previous.getCardId().equals(id) || !previous.getKind().equals(kind) || previous.getAmount() != amount) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이 요청 키는 다른 요청에서 이미 사용됐어요.");
            }
            return new CardView(card.getId(), card.getBalance());
        }
        if ("연습 결제".equals(kind) && card.getBalance() < amount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카드에 연습용 잔액이 부족해요.");
        }
        if ("충전".equals(kind)) card.addMoney(amount);
        else card.spendMoney(amount);
        transactions.save(new DemoTransaction(user.getId(), id, kind, amount, safeKey));
        audits.save(new AuditLog(auth.getName(), kind, "card:" + id));
        return new CardView(card.getId(), card.getBalance());
    }

    private DemoCard findOwnedCard(Long id, String username) {
        UserAccount user = findUser(username);
        DemoCard card = cards.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카드를 찾지 못했어요."));
        if (!card.getOwnerId().equals(user.getId())) {
            // 다른 사람 카드가 있는지 여부도 숨기도록 같은 안내를 보여 줘요.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카드를 찾지 못했어요.");
        }
        return card;
    }

    private String hashKey(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없어요.", impossible);
        }
    }

    private UserAccount findUser(String username) {
        return users.findByUsername(username).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."));
    }
}







