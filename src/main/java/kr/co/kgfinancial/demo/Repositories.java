package kr.co.kgfinancial.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
}
interface CardRepository extends JpaRepository<DemoCard, Long> {
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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOrder p where p.orderId = :orderId")
    Optional<PaymentOrder> findByOrderIdForUpdate(String orderId);
    Optional<PaymentOrder> findByOrderId(String orderId);
    Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String key);
}

