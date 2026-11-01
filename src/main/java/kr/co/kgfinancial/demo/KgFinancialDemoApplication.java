package kr.co.kgfinancial.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 이 표시가 붙은 곳에서 서버가 출발해요.
@SpringBootApplication
public class KgFinancialDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(KgFinancialDemoApplication.class, args);
    }
}

// 카드 한 장을 데이터베이스에 저장하는 표예요.
@Entity
@Table(name = "demo_cards")
class DemoCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String ownerName;
    private long balance;

    protected DemoCard() { } // 데이터베이스가 객체를 만들 때 써요.
    DemoCard(String ownerName) { this.ownerName = ownerName; this.balance = 0; }
    Long getId() { return id; }
    String getOwnerName() { return ownerName; }
    long getBalance() { return balance; }
    void addMoney(long amount) { balance += amount; }
    void spendMoney(long amount) { balance -= amount; }
}

// 충전과 결제 기록을 한 줄씩 보관하는 표예요.
@Entity
@Table(name = "demo_transactions")
class DemoTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long cardId;
    private String kind;
    private long amount;
    private String description;
    private LocalDateTime createdAt;

    protected DemoTransaction() { }
    DemoTransaction(Long cardId, String kind, long amount, String description) {
        this.cardId = cardId;
        this.kind = kind;
        this.amount = amount;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }
    public Long getId() { return id; }
    public Long getCardId() { return cardId; }
    public String getKind() { return kind; }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

interface CardRepository extends JpaRepository<DemoCard, Long> { }
interface TransactionRepository extends JpaRepository<DemoTransaction, Long> {
    List<DemoTransaction> findByCardIdOrderByCreatedAtDesc(Long cardId);
}

// 웹에서 보내는 값을 옮겨 담는 작은 상자들이에요.
record NewCardRequest(String ownerName) { }
record AmountRequest(long amount) { }
record PayRequest(long amount, String description) { }

// 주소(API)를 만들어요. 브라우저나 앱이 여기로 부탁을 보내요.
@RestController
@RequestMapping("/api")
class CardController {
    private final CardRepository cards;
    private final TransactionRepository transactions;

    CardController(CardRepository cards, TransactionRepository transactions) {
        this.cards = cards;
        this.transactions = transactions;
    }

    // POST /api/cards : 연습 카드를 만들어요.
    @PostMapping("/cards")
    DemoCard createCard(@RequestBody NewCardRequest request) {
        if (request.ownerName() == null || request.ownerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름을 적어 주세요.");
        }
        return cards.save(new DemoCard(request.ownerName()));
    }

    // POST /api/cards/1/charge : 카드에 연습용 돈을 넣어요.
    @PostMapping("/cards/{id}/charge")
    @Transactional // 카드 잔액과 기록을 함께 저장해요.
    DemoCard charge(@PathVariable Long id, @RequestBody AmountRequest request) {
        DemoCard card = findCard(id);
        checkPositive(request.amount());
        card.addMoney(request.amount());
        transactions.save(new DemoTransaction(id, "충전", request.amount(), "연습 충전"));
        return card;
    }

    // POST /api/cards/1/pay : 카드 잔액에서 연습 결제 금액을 빼요.
    @PostMapping("/cards/{id}/pay")
    @Transactional // 잔액 변경과 결제 기록이 둘 다 성공하도록 묶어요.
    DemoCard pay(@PathVariable Long id, @RequestBody PayRequest request) {
        DemoCard card = findCard(id);
        checkPositive(request.amount());
        if (card.getBalance() < request.amount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카드에 돈이 부족해요.");
        }
        card.spendMoney(request.amount());
        transactions.save(new DemoTransaction(id, "결제", request.amount(), request.description()));
        return card;
    }

    // GET /api/cards/1/transactions : 카드의 거래 내역을 보여 줘요.
    @GetMapping("/cards/{id}/transactions")
    List<DemoTransaction> history(@PathVariable Long id) {
        findCard(id); // 없는 카드 번호인지 먼저 확인해요.
        return transactions.findByCardIdOrderByCreatedAtDesc(id);
    }

    // GET /api/admin/cards : 관리자가 보는 카드 목록 연습 주소예요.
    @GetMapping("/admin/cards")
    List<DemoCard> allCards() { return cards.findAll(); }

    private DemoCard findCard(Long id) {
        return cards.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그 카드 번호를 찾지 못했어요."));
    }

    private void checkPositive(long amount) {
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 해요.");
        }
    }
}
