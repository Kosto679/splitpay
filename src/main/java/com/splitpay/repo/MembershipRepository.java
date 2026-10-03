package com.splitpay.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitpay.domain.Membership;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    boolean existsBySubscriptionIdAndPersonId(Long subscriptionId, Long personId);

    List<Membership> findByPersonId(Long personId);
}

