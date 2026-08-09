package com.moyuan.modelport.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
public class UsageLedger {
    private final JdbcTemplate jdbc;

    public UsageLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(
            String requestId,
            String clientKeyId,
            String tenantId,
            String provider,
            String requestedModel,
            String upstreamModel,
            String clientProtocol,
            int status,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            boolean idempotentReplay
    ) {
        jdbc.update("""
                        insert into usage_records (
                            id, request_id, client_key_id, tenant_id, provider,
                            requested_model, upstream_model, client_protocol, response_status,
                            latency_ms, input_tokens, output_tokens, idempotent_replay, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), requestId, clientKeyId, tenantId, provider,
                requestedModel, upstreamModel, clientProtocol, status, latencyMs,
                inputTokens, outputTokens, idempotentReplay, Timestamp.from(Instant.now()));
    }

    public List<UsageRecord> recent() {
        List<UsageRecord> records = new ArrayList<>(jdbc.query("""
                        select id, request_id, client_key_id, tenant_id, provider,
                               requested_model, upstream_model, client_protocol, response_status,
                               latency_ms, input_tokens, output_tokens, idempotent_replay, created_at
                        from usage_records
                        order by created_at desc
                        limit 200
                        """,
                (rs, rowNum) -> new UsageRecord(
                        rs.getString("id"),
                        rs.getString("request_id"),
                        rs.getString("client_key_id"),
                        rs.getString("tenant_id"),
                        rs.getString("provider"),
                        rs.getString("requested_model"),
                        rs.getString("upstream_model"),
                        rs.getString("client_protocol"),
                        rs.getInt("response_status"),
                        rs.getLong("latency_ms"),
                        rs.getInt("input_tokens"),
                        rs.getInt("output_tokens"),
                        rs.getBoolean("idempotent_replay"),
                        rs.getTimestamp("created_at").toInstant())));
        Collections.reverse(records);
        return List.copyOf(records);
    }

    public record UsageRecord(
            String id,
            String requestId,
            String clientKeyId,
            String tenantId,
            String provider,
            String requestedModel,
            String upstreamModel,
            String clientProtocol,
            int status,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            boolean idempotentReplay,
            Instant createdAt
    ) {
    }
}
