package com.splitpay.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitpay.domain.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findAllByOrderByNameAsc();
}
