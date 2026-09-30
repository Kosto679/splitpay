package com.splitpay.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.splitpay.FixedClockConfig;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.PersonRepository;
import com.splitpay.repo.SubscriptionRepository;
import com.splitpay.service.LedgerService;
import com.splitpay.service.PinService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@Transactional
class PublicCheckInTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PersonRepository people;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    LedgerService ledger;

    @Autowired
    PinService pins;

    @Test
    void correctPasswordShowsStatusForEveryPlan() throws Exception {
        Person maria = personWithPin("Maria", "tiger42");
        ledger.addMembership(plan("Netflix"), maria, new BigDecimal("3.00"));
        ledger.addMembership(plan("Spotify"), maria, new BigDecimal("4.00"));

        mvc.perform(post("/api/public/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"person_id\": " + maria.getId() + ", \"password\": \"Tiger42 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Maria"))
                .andExpect(jsonPath("$.subscriptions.length()").value(2))
                .andExpect(jsonPath("$.subscriptions[0].subscription_name").value("Netflix"))
                .andExpect(jsonPath("$.subscriptions[0].paid").value(false))
                .andExpect(jsonPath("$.subscriptions[0].next_billing_date").value("2026-09-01"));
    }

    @Test
    void wrongPasswordIsRejectedAndThenRateLimited() throws Exception {
        Person nikos = personWithPin("Nikos", "river17");
        String body = "{\"person_id\": " + nikos.getId() + ", \"password\": \"guess\"}";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/public/status").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/public/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"person_id\": " + nikos.getId() + ", \"password\": \"river17\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void publicListOnlyShowsPeopleWithCheckIn() throws Exception {
        personWithPin("Alex", "maple55");
        Person hidden = new Person();
        hidden.setName("Hidden");
        people.save(hidden);

        mvc.perform(get("/api/public/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Alex')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'Hidden')]").doesNotExist());
    }

    @Test
    void adminApiNeedsSignIn() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    private Person personWithPin(String name, String pin) {
        Person person = new Person();
        person.setName(name);
        person.setCreatedAt(ledger.now());
        person.setPinHash(pins.hash(pin));
        return people.save(person);
    }

    private Subscription plan(String name) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setTotalAmount(new BigDecimal("12.00"));
        sub.setBillingDay(1);
        return subscriptions.save(sub);
    }
}
