package com.moyuan.modelport.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.modelport.web.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Repository
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DatabaseDialect dialect;

    public IdempotencyStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DatabaseDialect dialect
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dialect = dialect;
    }

    public CachedResponse find(String clientKeyId, String key, JsonNode request) {
        if (key == null || key.isBlank()) return null;
        validateKey(key);
        List<CachedResponse> matches = jdbc.query("""
                        select request_hash, response_status, response_body, created_at
                        from idempotency_responses
                        where client_key_id = ? and idempotency_key = ?
                        """,
                (rs, rowNum) -> new CachedResponse(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        readBody(rs.getString("response_body")),
                        rs.getTimestamp("created_at").toInstant()),
                clientKeyId, key);
        CachedResponse cached = matches.stream().findFirst().orElse(null);
        if (cached == null) return null;
        if (!cached.requestHash().equals(hash(request))) {
            throw new GatewayException(HttpStatus.CONFLICT, "idempotency key was already used with a different request");
        }
        return cached;
    }

    @Transactional
    public synchronized void save(String clientKeyId, String key, JsonNode request, int status, JsonNode body) {
        if (key == null || key.isBlank()) return;
        validateKey(key);
        if (dialect.isH2()) {
            Integer count = jdbc.queryForObject("""
                            select count(*) from idempotency_responses
                            where client_key_id = ? and idempotency_key = ?
                            """,
                    Integer.class, clientKeyId, key);
            if (count != null && count > 0) return;
            jdbc.update("""
                            insert into idempotency_responses (
                                client_key_id, idempotency_key, request_hash,
                                response_status, response_body, created_at
                            ) values (?, ?, ?, ?, ?, ?)
                            """,
                    clientKeyId, key, hash(request), status, body.toString(), Timestamp.from(Instant.now()));
            return;
        }
        jdbc.update("""
                        insert into idempotency_responses (
                            client_key_id, idempotency_key, request_hash,
                            response_status, response_body, created_at
                        ) values (?, ?, ?, ?, ?, ?)
                        on conflict (client_key_id, idempotency_key) do nothing
                        """,
                clientKeyId, key, hash(request), status, body.toString(), Timestamp.from(Instant.now()));
    }

    private void validateKey(String key) {
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key");
        }
    }

    private String hash(JsonNode request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private JsonNode readBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("unable to deserialize idempotent response", error);
        }
    }

    public record CachedResponse(String requestHash, int status, JsonNode body, Instant createdAt) {
    }
}
