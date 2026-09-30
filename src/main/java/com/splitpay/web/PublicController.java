package com.splitpay.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.domain.Person;
import com.splitpay.repo.PersonRepository;
import com.splitpay.service.AttemptLimiter;
import com.splitpay.service.LedgerService;
import com.splitpay.service.PinService;
import com.splitpay.web.Api.CheckIn;
import com.splitpay.web.Api.PublicPersonOut;
import com.splitpay.web.Api.PublicStatusOut;

import jakarta.servlet.http.HttpServletRequest;

/** The check-in page people use to see whether they are paid up. No admin session needed. */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private static final int MAX_FAILURES_PER_PERSON = 5;
    private static final int MAX_FAILURES_PER_ADDRESS = 30;

    private final PersonRepository people;
    private final PinService pins;
    private final AttemptLimiter limiter;
    private final LedgerService ledger;
    private final ViewMapper views;

    public PublicController(PersonRepository people, PinService pins, AttemptLimiter limiter, LedgerService ledger,
            ViewMapper views) {
        this.people = people;
        this.pins = pins;
        this.limiter = limiter;
        this.ledger = ledger;
        this.views = views;
    }

    @GetMapping("/people")
    List<PublicPersonOut> people() {
        return people.findByPinHashIsNotNullOrderByNameAsc().stream()
                .map(p -> new PublicPersonOut(p.getId(), p.getName()))
                .toList();
    }

    @PostMapping("/status")
    PublicStatusOut status(@RequestBody CheckIn in, HttpServletRequest request) {
        String addressKey = "check-ip:" + request.getRemoteAddr();
        String personKey = "check-person:" + in.personId();
        limiter.check(addressKey, MAX_FAILURES_PER_ADDRESS);
        limiter.check(personKey, MAX_FAILURES_PER_PERSON);

        Person person = in.personId() == null ? null : people.findById(in.personId()).orElse(null);
        if (person == null || !pins.matches(in.password(), person.getPinHash())) {
            limiter.fail(addressKey);
            limiter.fail(personKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That name and password don't match");
        }
        limiter.reset(personKey);
        return new PublicStatusOut(person.getName(), ledger.today(), views.membershipStatuses(person));
    }
}
