package com.splitpay.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitpay.domain.Membership;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    boolean existsBySubscriptionIdAndPersonId(Long subscriptionId, Long personId);
}
