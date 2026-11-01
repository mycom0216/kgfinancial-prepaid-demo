package kr.co.kgfinancial.demo;

import jakarta.persistence.*;
import java.time.Instant;

// 로그인 계정을 저장해요. 비밀번호 원문은 저장하지 않고 암호화된 글자만 보관해요.
@Entity
@Table(name = "demo_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String username;
    @Column(nullable = false)
    private String passwordHash;
    @Column(nullable = false, length = 10)
    private String role;
    @Column(nullable = false)
    private Instant createdAt;

    protected UserAccount() { }
    UserAccount(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }
    Long getId() { return id; }
    String getUsername() { return username; }
    String getPasswordHash() { return passwordHash; }
    String getRole() { return role; }
}

// 카드에는 주인의 이름 대신 계정 번호만 연결해 개인정보를 적게 모아요.
@Entity
@Table(name = "demo_cards")
class DemoCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long ownerId;
    @Column(nullable = false)
    private long balance;
    @Column(nullable = false)
    private Instant createdAt;

    protected DemoCard() { }
    DemoCard(Long ownerId) { this.ownerId = ownerId; this.balance = 0; this.createdAt = Instant.now(); }
    Long getId() { return id; }
    Long getOwnerId() { return ownerId; }
    long getBalance() { return balance; }
    void addMoney(long amount) { balance += amount; }
    void spendMoney(long amount) { balance -= amount; }
}

// 누가 언제 충전하거나 결제했는지 남겨요. 이름, 비밀번호, 카드번호는 기록하지 않아요.
@Entity
@Table(name = "demo_transactions", uniqueConstraints =
        @UniqueConstraint(name = "uk_transaction_request", columnNames = {"user_id", "idempotency_key"}))
class DemoTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "card_id", nullable = false)
    private Long cardId;
    @Column(nullable = false, length = 20)
    private String kind;
    @Column(nullable = false)
    private long amount;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(nullable = false)
    private Instant createdAt;

    protected DemoTransaction() { }
    DemoTransaction(Long userId, Long cardId, String kind, long amount, String key) {
        this.userId = userId; this.cardId = cardId; this.kind = kind;
        this.amount = amount; this.idempotencyKey = key; this.createdAt = Instant.now();
    }
    Long getId() { return id; }
    Long getUserId() { return userId; }
    Long getCardId() { return cardId; }
    String getKind() { return kind; }
    long getAmount() { return amount; }
    String getIdempotencyKey() { return idempotencyKey; }
    Instant getCreatedAt() { return createdAt; }
}

// 중요한 동작을 기록하는 감사 일지예요. 요청 본문이나 비밀번호는 적지 않아요.
@Entity
@Table(name = "audit_logs")
class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String actor;
    @Column(nullable = false, length = 60)
    private String action;
    @Column(nullable = false, length = 80)
    private String target;
    @Column(nullable = false)
    private Instant createdAt;

    protected AuditLog() { }
    AuditLog(String actor, String action, String target) {
        this.actor = actor; this.action = action; this.target = target; this.createdAt = Instant.now();
    }
    Long getId() { return id; }
    String getActor() { return actor; }
    String getAction() { return action; }
    String getTarget() { return target; }
    Instant getCreatedAt() { return createdAt; }
}
