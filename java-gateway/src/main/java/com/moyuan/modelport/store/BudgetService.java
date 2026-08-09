package com.moyuan.modelport.store;

import com.moyuan.modelport.web.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class BudgetService {
    private final JdbcTemplate jdbc;
    private final DatabaseDialect dialect;

    public BudgetService(JdbcTemplate jdbc, DatabaseDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Transactional
    public synchronized Reservation reserve(
            String requestId,
            String tenantId,
            String clientKeyId,
            long tokenLimit,
            long requestedTokens
    ) {
        long requested = Math.max(1, requestedTokens);
        String period = YearMonth.now(ZoneOffset.UTC).toString();
        Instant now = Instant.now();
        ensureAccount(tenantId, clientKeyId, period, tokenLimit, now);
        BudgetAccount account = lockAccount(tenantId, clientKeyId, period);
        long available = tokenLimit - account.reservedTokens() - account.consumedTokens();
        if (requested > available) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                    "monthly token budget exceeded");
        }
        jdbc.update("""
                        update budget_accounts
                        set token_limit = ?, reserved_tokens = reserved_tokens + ?, updated_at = ?
                        where tenant_id = ? and client_key_id = ? and period_key = ?
                        """,
                tokenLimit, requested, Timestamp.from(now), tenantId, clientKeyId, period);
        String reservationId = UUID.randomUUID().toString();
        jdbc.update("""
                        insert into budget_reservations (
                            reservation_id, request_id, tenant_id, client_key_id, period_key,
                            reserved_tokens, actual_tokens, reservation_status, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, null, 'RESERVED', ?, ?)
                        """,
                reservationId, requestId, tenantId, clientKeyId, period, requested,
                Timestamp.from(now), Timestamp.from(now));
        return new Reservation(reservationId, requestId, tenantId, clientKeyId,
                period, requested, null, "RESERVED", now);
    }

    private void ensureAccount(
            String tenantId,
            String clientKeyId,
            String period,
            long tokenLimit,
            Instant now
    ) {
        if (dialect.isH2()) {
            Integer count = jdbc.queryForObject("""
                            select count(*) from budget_accounts
                            where tenant_id = ? and client_key_id = ? and period_key = ?
                            """,
                    Integer.class, tenantId, clientKeyId, period);
            if (count != null && count > 0) return;
            try {
                jdbc.update("""
                                insert into budget_accounts (
                                    tenant_id, client_key_id, period_key, token_limit,
                                    reserved_tokens, consumed_tokens, updated_at
                                ) values (?, ?, ?, ?, 0, 0, ?)
                                """,
                        tenantId, clientKeyId, period, tokenLimit, Timestamp.from(now));
            } catch (DuplicateKeyException concurrentCreate) {
                // Another transaction created the monthly account after our existence check.
            }
            return;
        }
        jdbc.update("""
                        insert into budget_accounts (
                            tenant_id, client_key_id, period_key, token_limit,
                            reserved_tokens, consumed_tokens, updated_at
                        ) values (?, ?, ?, ?, 0, 0, ?)
                        on conflict (tenant_id, client_key_id, period_key) do nothing
                        """,
                tenantId, clientKeyId, period, tokenLimit, Timestamp.from(now));
    }

    @Transactional
    public void settle(Reservation reservation, long actualTokens) {
        Reservation current = lockReservation(reservation.reservationId());
        if (!current.status().equals("RESERVED")) return;
        lockAccount(current.tenantId(), current.clientKeyId(), current.period());
        long actual = Math.max(0, actualTokens);
        Instant now = Instant.now();
        jdbc.update("""
                        update budget_accounts
                        set reserved_tokens = reserved_tokens - ?,
                            consumed_tokens = consumed_tokens + ?,
                            updated_at = ?
                        where tenant_id = ? and client_key_id = ? and period_key = ?
                        """,
                current.reservedTokens(), actual, Timestamp.from(now),
                current.tenantId(), current.clientKeyId(), current.period());
        jdbc.update("""
                        update budget_reservations
                        set actual_tokens = ?, reservation_status = 'SETTLED', updated_at = ?
                        where reservation_id = ?
                        """,
                actual, Timestamp.from(now), current.reservationId());
    }

    @Transactional
    public void release(Reservation reservation) {
        Reservation current = lockReservation(reservation.reservationId());
        if (!current.status().equals("RESERVED")) return;
        lockAccount(current.tenantId(), current.clientKeyId(), current.period());
        Instant now = Instant.now();
        jdbc.update("""
                        update budget_accounts
                        set reserved_tokens = reserved_tokens - ?, updated_at = ?
                        where tenant_id = ? and client_key_id = ? and period_key = ?
                        """,
                current.reservedTokens(), Timestamp.from(now),
                current.tenantId(), current.clientKeyId(), current.period());
        jdbc.update("""
                        update budget_reservations
                        set actual_tokens = 0, reservation_status = 'RELEASED', updated_at = ?
                        where reservation_id = ?
                        """,
                Timestamp.from(now), current.reservationId());
    }

    public List<BudgetAccount> accounts() {
        return jdbc.query("""
                        select tenant_id, client_key_id, period_key, token_limit,
                               reserved_tokens, consumed_tokens, updated_at
                        from budget_accounts
                        order by period_key desc, tenant_id, client_key_id
                        """,
                (rs, rowNum) -> new BudgetAccount(
                        rs.getString("tenant_id"),
                        rs.getString("client_key_id"),
                        rs.getString("period_key"),
                        rs.getLong("token_limit"),
                        rs.getLong("reserved_tokens"),
                        rs.getLong("consumed_tokens"),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    public List<Reservation> recentReservations() {
        return jdbc.query("""
                        select reservation_id, request_id, tenant_id, client_key_id, period_key,
                               reserved_tokens, actual_tokens, reservation_status, updated_at
                        from budget_reservations
                        order by updated_at desc
                        limit 200
                        """,
                (rs, rowNum) -> new Reservation(
                        rs.getString("reservation_id"),
                        rs.getString("request_id"),
                        rs.getString("tenant_id"),
                        rs.getString("client_key_id"),
                        rs.getString("period_key"),
                        rs.getLong("reserved_tokens"),
                        rs.getObject("actual_tokens") == null ? null : rs.getLong("actual_tokens"),
                        rs.getString("reservation_status"),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    private BudgetAccount lockAccount(String tenantId, String clientKeyId, String period) {
        return jdbc.query("""
                        select tenant_id, client_key_id, period_key, token_limit,
                               reserved_tokens, consumed_tokens, updated_at
                        from budget_accounts
                        where tenant_id = ? and client_key_id = ? and period_key = ?
                        for update
                        """,
                (rs, rowNum) -> new BudgetAccount(
                        rs.getString("tenant_id"),
                        rs.getString("client_key_id"),
                        rs.getString("period_key"),
                        rs.getLong("token_limit"),
                        rs.getLong("reserved_tokens"),
                        rs.getLong("consumed_tokens"),
                        rs.getTimestamp("updated_at").toInstant()),
                tenantId, clientKeyId, period).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("budget account was not created"));
    }

    private Reservation lockReservation(String reservationId) {
        return jdbc.query("""
                        select reservation_id, request_id, tenant_id, client_key_id, period_key,
                               reserved_tokens, actual_tokens, reservation_status, updated_at
                        from budget_reservations
                        where reservation_id = ?
                        for update
                        """,
                (rs, rowNum) -> new Reservation(
                        rs.getString("reservation_id"),
                        rs.getString("request_id"),
                        rs.getString("tenant_id"),
                        rs.getString("client_key_id"),
                        rs.getString("period_key"),
                        rs.getLong("reserved_tokens"),
                        rs.getObject("actual_tokens") == null ? null : rs.getLong("actual_tokens"),
                        rs.getString("reservation_status"),
                        rs.getTimestamp("updated_at").toInstant()),
                reservationId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("budget reservation not found"));
    }

    public record BudgetAccount(
            String tenantId,
            String clientKeyId,
            String period,
            long tokenLimit,
            long reservedTokens,
            long consumedTokens,
            Instant updatedAt
    ) {
        public long availableTokens() {
            return Math.max(0, tokenLimit - reservedTokens - consumedTokens);
        }
    }

    public record Reservation(
            String reservationId,
            String requestId,
            String tenantId,
            String clientKeyId,
            String period,
            long reservedTokens,
            Long actualTokens,
            String status,
            Instant updatedAt
    ) {
    }
}
