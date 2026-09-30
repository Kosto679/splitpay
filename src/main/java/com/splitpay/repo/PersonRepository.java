package com.splitpay.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitpay.domain.Person;

public interface PersonRepository extends JpaRepository<Person, Long> {

    List<Person> findAllByOrderByNameAsc();

    List<Person> findByPinHashIsNotNullOrderByNameAsc();

    Optional<Person> findFirstByNameIgnoreCase(String name);
}
