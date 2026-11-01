package kr.co.kgfinancial.demo;

import org.springframework.data.jpa.repository.JpaRepository;
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
