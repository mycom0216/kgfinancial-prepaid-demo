package kr.co.kgfinancial.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

// Repository는 데이터베이스에 직접 SQL을 많이 쓰지 않고도 저장·검색하게 해주는 도우미예요.
// JpaRepository가 기본 저장, 삭제, ID 검색 기능을 미리 제공해요.
interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
}
interface CardRepository extends JpaRepository<DemoCard, Long> {
    // 이름이 findByOwnerId면 Spring이 owner_id와 같은 카드만 찾는 검색을 만들어줘요.
    List<DemoCard> findByOwnerIdOrderByIdDesc(Long ownerId);
}
interface TransactionRepository extends JpaRepository<DemoTransaction, Long> {
    Optional<DemoTransaction> findByUserIdAndIdempotencyKey(Long userId, String key);
    List<DemoTransaction> findByCardIdOrderByCreatedAtDesc(Long cardId);
}
interface AuditRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    // 동시에 같은 결제 결과가 오면 한 요청씩 줄을 세우도록 주문 표의 해당 줄을 잠가요.
    // 이 잠금은 결제 후 잔액을 한 번만 더하도록 돕는 안전장치예요.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrder p where p.orderId = :orderId")
    Optional<PaymentOrder> findByOrderIdForUpdate(String orderId);
    Optional<PaymentOrder> findByOrderId(String orderId);
    // 같은 사람이 같은 요청 키로 만든 결제 주문을 다시 찾아 중복 주문인지 확인해요.
    Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String key);
}
interface MobileAccessTokenRepository extends JpaRepository<MobileAccessToken, Long> {
    Optional<MobileAccessToken> findByTokenHash(String tokenHash);
}

