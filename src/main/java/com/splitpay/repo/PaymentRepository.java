package com.splitpay.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitpay.domain.Payment;
import com.splitpay.domain.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByMembershipIdAndStatus(Long membershipId, PaymentStatus status);

    List<Payment> findByMembershipId(Long membershipId);

    List<Payment> findBySubscriptionId(Long subscriptionId);

    List<Payment> findTop40BySubscriptionIdOrderByPaidAtDesc(Long subscriptionId);

    List<Payment> findByStatusOrderByPaidAtDesc(PaymentStatus status);

    long countByStatus(PaymentStatus status);
}
