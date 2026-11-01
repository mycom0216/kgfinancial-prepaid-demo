package kr.co.kgfinancial.demo;

import jakarta.persistence.*;
import java.time.Instant;

// 로그인 계정을 저장해요. 비밀번호 원문은 저장하지 않고 암호화된 글자만 보관해요.
// @Entity는 이 Java 클래스 하나가 데이터베이스 표 하나와 연결된다는 표시예요.
@Entity
@Table(name = "demo_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
class UserAccount {
    // id는 사람마다 다른 번호예요. DB가 새 계정이 생길 때 자동으로 하나 만들어요.
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String username;
    // passwordHash는 비밀번호를 BCrypt로 바꾼 결과예요. 원래 비밀번호가 아니에요.
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
// 선불카드 실물이나 실제 카드번호를 나타내는 표가 아니라, 연습 잔액만 저장하는 표예요.
@Entity
@Table(name = "demo_cards")
class DemoCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // ownerId는 이 카드의 주인이 누구인지 연결해 주는 계정 번호예요.
    @Column(nullable = false)
    private Long ownerId;
    // balance는 잔액을 원 단위 숫자로 적어요. 예를 들어 1500은 1,500원이에요.
    @Column(nullable = false)
    private long balance;
    @Column(nullable = false)
    private Instant createdAt;

    protected DemoCard() { }
    DemoCard(Long ownerId) { this.ownerId = ownerId; this.balance = 0; this.createdAt = Instant.now(); }
    Long getId() { return id; }
    Long getOwnerId() { return ownerId; }
    long getBalance() { return balance; }
    // 이 함수는 결제 승인이 확인된 뒤에만 호출해야 해요.
    void addMoney(long amount) { balance += amount; }
    // 연습용 물건 결제가 성공했을 때 잔액을 줄여요.
    void spendMoney(long amount) { balance -= amount; }
}

// 누가 언제 충전하거나 결제했는지 남겨요. 이름, 비밀번호, 카드번호는 기록하지 않아요.
// Idempotency-Key를 사용자별로 한 번만 저장해서 같은 요청이 잔액을 두 번 바꾸지 않게 해요.
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
// 기록 대상은 카드 번호나 개인정보 대신 내부 번호처럼 꼭 필요한 정보만 담아요.
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

// PG 결제 한 건의 상태만 저장해요. 카드번호나 계좌번호는 저장하지 않아요.
// 상태 예: READY(준비됨), APPROVING(승인 확인 중), PAID(승인 완료), APPROVAL_UNKNOWN(결과 확인 필요).
// 주문 상태를 저장해야 페이지를 닫거나 서버가 다시 켜져도 진행 중인 결제를 추적할 수 있어요.
@Entity
@Table(name = "payment_orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_idempotency", columnNames = {"user_id", "idempotency_key"})})
class PaymentOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false, length = 40) private String orderId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "card_id", nullable = false) private Long cardId;
    @Column(nullable = false) private long amount;
    @Column(name = "pay_type", nullable = false, length = 10) private String payType;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "idempotency_key", nullable = false, length = 64) private String idempotencyKey;
    @Column(name = "provider_tid", length = 100) private String providerTid;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected PaymentOrder() { }
    PaymentOrder(String orderId, Long userId, Long cardId, long amount, String payType, String idempotencyKey) {
        this.orderId=orderId; this.userId=userId; this.cardId=cardId; this.amount=amount;
        this.payType=payType; this.idempotencyKey=idempotencyKey; this.status="READY";
        this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    String getOrderId() { return orderId; }
    Long getUserId() { return userId; }
    Long getCardId() { return cardId; }
    long getAmount() { return amount; }
    String getPayType() { return payType; }
    String getStatus() { return status; }
    String getIdempotencyKey() { return idempotencyKey; }
    String getProviderTid() { return providerTid; }
    void markApproving() { this.status="APPROVING"; this.updatedAt=Instant.now(); }
    void markUnknown() { this.status="APPROVAL_UNKNOWN"; this.updatedAt=Instant.now(); }
    void markPaid(String tid) { this.status="PAID"; this.providerTid=tid; this.updatedAt=Instant.now(); }
    void markFailed() { this.status="FAILED"; this.updatedAt=Instant.now(); }
}

