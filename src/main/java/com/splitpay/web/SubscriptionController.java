package com.splitpay.web;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.domain.Membership;
import com.splitpay.domain.PaymentStatus;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.MembershipRepository;
import com.splitpay.repo.PaymentRepository;
import com.splitpay.repo.PersonRepository;
import com.splitpay.repo.SubscriptionRepository;
import com.splitpay.service.LedgerService;
import com.splitpay.service.Periods;
import com.splitpay.web.Api.DashboardOut;
import com.splitpay.web.Api.MemberIn;
import com.splitpay.web.Api.OwnerIn;
import com.splitpay.web.Api.ShareIn;
import com.splitpay.web.Api.SubscriptionIn;
import com.splitpay.web.Api.SubscriptionOut;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionRepository subscriptions;
    private final PersonRepository people;
    private final MembershipRepository memberships;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final ViewMapper views;

    public SubscriptionController(SubscriptionRepository subscriptions, PersonRepository people,
            MembershipRepository memberships, PaymentRepository payments, LedgerService ledger, ViewMapper views) {
        this.subscriptions = subscriptions;
        this.people = people;
        this.memberships = memberships;
        this.payments = payments;
        this.ledger = ledger;
        this.views = views;
    }

    @GetMapping("/dashboard")
    DashboardOut dashboard() {
        return new DashboardOut(
                subscriptions.findAllByOrderByNameAsc().stream().map(s -> views.subscription(s, false)).toList(),
                payments.countByStatus(PaymentStatus.UNMATCHED));
    }

    @PostMapping("/subscriptions")
    SubscriptionOut create(@Valid @RequestBody SubscriptionIn in) {
        Subscription sub = new Subscription();
        apply(sub, in);
        return views.subscription(subscriptions.save(sub), false);
    }

    @GetMapping("/subscriptions/{id}")
    SubscriptionOut get(@PathVariable Long id) {
        return views.subscription(find(id), true);
    }

    @PatchMapping("/subscriptions/{id}")
    SubscriptionOut update(@PathVariable Long id, @Valid @RequestBody SubscriptionIn in) {
        Subscription sub = find(id);
        apply(sub, in);
        return views.subscription(subscriptions.save(sub), true);
    }

    @DeleteMapping("/subscriptions/{id}")
    Map<String, Boolean> delete(@PathVariable Long id) {
        ledger.deleteSubscription(find(id));
        return Map.of("ok", true);
    }

    @PostMapping("/subscriptions/{id}/members")
    SubscriptionOut addMember(@PathVariable Long id, @Valid @RequestBody MemberIn in) {
        Subscription sub = find(id);
        Person person = in.personId() != null ? findPerson(in.personId()) : personByName(in.name());
        if (person.getId() != null && memberships.existsBySubscriptionIdAndPersonId(sub.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " is already on " + sub.getName());
        }
        ledger.addMembership(sub, person, in.shareAmount(), Boolean.TRUE.equals(in.owner()));
        return views.subscription(sub, true);
    }

    @PostMapping("/members/{id}/owner")
    Map<String, Boolean> setOwner(@PathVariable Long id, @RequestBody OwnerIn in) {
        ledger.setOwner(findMembership(id), in.owner());
        return Map.of("ok", true);
    }

    @PatchMapping("/members/{id}")
    Map<String, Boolean> updateShare(@PathVariable Long id, @Valid @RequestBody ShareIn in) {
        Membership membership = findMembership(id);
        membership.setShareAmount(LedgerService.money(in.shareAmount()));
        memberships.save(membership);
        return Map.of("ok", true);
    }

    @DeleteMapping("/members/{id}")
    Map<String, Boolean> removeMember(@PathVariable Long id) {
        ledger.removeMembership(findMembership(id));
        return Map.of("ok", true);
    }

    private void apply(Subscription sub, SubscriptionIn in) {
        sub.setName(in.name().strip());
        sub.setTotalAmount(LedgerService.money(in.totalAmount()));
        String currency = in.currency() == null ? "" : in.currency().strip().toUpperCase(Locale.ROOT);
        sub.setCurrency(currency.isEmpty() ? "EUR" : currency);
        sub.setBillingDay(Periods.clampDay(in.billingDay() == null ? 1 : in.billingDay()));
        sub.setNotes(in.notes() == null ? "" : in.notes().strip());
    }

    private Person personByName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a person or type a new name");
        }
        return people.findFirstByNameIgnoreCase(clean).orElseGet(() -> {
            Person person = new Person();
            person.setName(clean);
            person.setCreatedAt(ledger.now());
            return people.save(person);
        });
    }

    private Subscription find(Long id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
    }

    private Person findPerson(Long id) {
        return people.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    }

    private Membership findMembership(Long id) {
        return memberships.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }
}
