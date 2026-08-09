package com.moyuan.modelport.store;

import com.moyuan.modelport.web.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BudgetServiceTest {
    @Autowired BudgetService budgets;

    @Test
    void reservesRejectsReleasesAndSettlesAgainstTheLockedMonthlyAccount() {
        String suffix = UUID.randomUUID().toString();
        String tenant = "tenant-" + suffix;
        String client = "client-" + suffix;

        var first = budgets.reserve("request-a-" + suffix, tenant, client, 100, 60);
        assertThat(account(tenant, client).reservedTokens()).isEqualTo(60);
        assertThat(account(tenant, client).availableTokens()).isEqualTo(40);

        assertThatThrownBy(() -> budgets.reserve(
                "request-b-" + suffix, tenant, client, 100, 50))
                .isInstanceOfSatisfying(GatewayException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(account(tenant, client).reservedTokens()).isEqualTo(60);

        budgets.release(first);
        assertThat(account(tenant, client).reservedTokens()).isZero();
        assertThat(account(tenant, client).consumedTokens()).isZero();

        var second = budgets.reserve("request-c-" + suffix, tenant, client, 100, 80);
        budgets.settle(second, 37);
        assertThat(account(tenant, client).reservedTokens()).isZero();
        assertThat(account(tenant, client).consumedTokens()).isEqualTo(37);
        assertThat(account(tenant, client).availableTokens()).isEqualTo(63);

        assertThat(budgets.recentReservations()).anySatisfy(reservation -> {
            assertThat(reservation.reservationId()).isEqualTo(first.reservationId());
            assertThat(reservation.status()).isEqualTo("RELEASED");
        }).anySatisfy(reservation -> {
            assertThat(reservation.reservationId()).isEqualTo(second.reservationId());
            assertThat(reservation.status()).isEqualTo("SETTLED");
            assertThat(reservation.actualTokens()).isEqualTo(37);
        });
    }

    @Test
    void fiftyConcurrentReservationsCannotOversellTheMonthlyBudget() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String tenant = "concurrent-tenant-" + suffix;
        String client = "concurrent-client-" + suffix;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(20);
        List<Callable<BudgetService.Reservation>> tasks = IntStream.range(0, 50)
                .mapToObj(index -> (Callable<BudgetService.Reservation>) () -> {
                    start.await();
                    try {
                        return budgets.reserve(
                                "concurrent-request-" + index + "-" + suffix,
                                tenant,
                                client,
                                1_000,
                                100);
                    } catch (GatewayException error) {
                        if (error.status() != HttpStatus.TOO_MANY_REQUESTS) throw error;
                        return null;
                    }
                })
                .toList();

        var futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        List<BudgetService.Reservation> accepted = new ArrayList<>();
        for (var future : futures) {
            BudgetService.Reservation reservation = future.get(10, TimeUnit.SECONDS);
            if (reservation != null) accepted.add(reservation);
        }
        executor.shutdownNow();

        assertThat(accepted).hasSize(10);
        assertThat(account(tenant, client).reservedTokens()).isEqualTo(1_000);
        assertThat(account(tenant, client).availableTokens()).isZero();
        assertThat(budgets.recentReservations().stream()
                .filter(reservation -> reservation.tenantId().equals(tenant))
                .filter(reservation -> reservation.status().equals("RESERVED"))
                .count()).isEqualTo(10);
    }

    private BudgetService.BudgetAccount account(String tenant, String client) {
        return budgets.accounts().stream()
                .filter(account -> account.tenantId().equals(tenant)
                        && account.clientKeyId().equals(client))
                .findFirst()
                .orElseThrow();
    }
}
