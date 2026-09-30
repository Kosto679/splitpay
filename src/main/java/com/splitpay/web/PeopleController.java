package com.splitpay.web;

import java.util.Arrays;
import java.util.List;
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

import com.splitpay.domain.Person;
import com.splitpay.repo.PersonRepository;
import com.splitpay.service.LedgerService;
import com.splitpay.service.PinService;
import com.splitpay.web.Api.PersonIn;
import com.splitpay.web.Api.PersonOut;
import com.splitpay.web.Api.PinIn;
import com.splitpay.web.Api.PinOut;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

    private static final int MIN_PIN_LENGTH = 4;

    private final PersonRepository people;
    private final LedgerService ledger;
    private final PinService pins;
    private final ViewMapper views;

    public PeopleController(PersonRepository people, LedgerService ledger, PinService pins, ViewMapper views) {
        this.people = people;
        this.ledger = ledger;
        this.pins = pins;
        this.views = views;
    }

    @GetMapping
    List<PersonOut> list() {
        return people.findAllByOrderByNameAsc().stream().map(views::person).toList();
    }

    @PostMapping
    PersonOut create(@Valid @RequestBody PersonIn in) {
        String name = in.name().strip();
        people.findFirstByNameIgnoreCase(name).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already someone called " + existing.getName());
        });
        Person person = new Person();
        person.setName(name);
        person.setAliases(cleanAliases(in.aliases()));
        person.setCreatedAt(ledger.now());
        return views.person(people.save(person));
    }

    @PatchMapping("/{id}")
    PersonOut update(@PathVariable Long id, @Valid @RequestBody PersonIn in) {
        Person person = find(id);
        person.setName(in.name().strip());
        person.setAliases(cleanAliases(in.aliases()));
        return views.person(people.save(person));
    }

    @DeleteMapping("/{id}")
    Map<String, Boolean> delete(@PathVariable Long id) {
        ledger.deletePerson(find(id));
        return Map.of("ok", true);
    }

    /** Sets the person's check-in password, or generates an easy one when none is given. */
    @PostMapping("/{id}/pin")
    PinOut setPin(@PathVariable Long id, @RequestBody(required = false) PinIn in) {
        Person person = find(id);
        String requested = pins.normalize(in == null ? null : in.pin());
        String pin;
        if (requested.isEmpty()) {
            pin = generateUnique(person);
        } else {
            if (requested.length() < MIN_PIN_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Use at least " + MIN_PIN_LENGTH + " characters");
            }
            if (usedBySomeoneElse(person, requested)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone else already uses that password");
            }
            pin = requested;
        }
        person.setPinHash(pins.hash(pin));
        people.save(person);
        return new PinOut(pin);
    }

    @DeleteMapping("/{id}/pin")
    Map<String, Boolean> clearPin(@PathVariable Long id) {
        Person person = find(id);
        person.setPinHash(null);
        people.save(person);
        return Map.of("ok", true);
    }

    private String generateUnique(Person person) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = pins.generate();
            if (!usedBySomeoneElse(person, candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not generate a unique password, try again");
    }

    private boolean usedBySomeoneElse(Person person, String pin) {
        return people.findByPinHashIsNotNullOrderByNameAsc().stream()
                .filter(other -> !other.getId().equals(person.getId()))
                .anyMatch(other -> pins.matches(pin, other.getPinHash()));
    }

    private static String cleanAliases(String aliases) {
        if (aliases == null) {
            return "";
        }
        return String.join(", ", Arrays.stream(aliases.split(","))
                .map(String::strip)
                .filter(a -> !a.isEmpty())
                .toList());
    }

    private Person find(Long id) {
        return people.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    }
}
