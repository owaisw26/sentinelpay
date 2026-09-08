package com.sentinelpay.payments.repository;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.sentinelpay.payments.service.RateLimitOperation;

@Repository
public class PostgresFixedWindowRateLimitRepository {
    private static final String INCREMENT_WINDOW = """
        WITH database_clock AS MATERIALIZED (
            SELECT clock_timestamp() AS database_now
        ), timing AS MATERIALIZED (
            SELECT
                database_now,
                to_timestamp(
                    floor(extract(epoch FROM database_now) / :windowSeconds)
                    * :windowSeconds
                ) AS window_start
            FROM database_clock
        ), upserted AS (
            INSERT INTO api_rate_limit_windows (
                subject_id, operation, window_started_at, request_count
            )
            SELECT :subjectId, :operation, window_start, 1
            FROM timing
            ON CONFLICT (subject_id, operation) DO UPDATE SET
                window_started_at = EXCLUDED.window_started_at,
                request_count = CASE
                    WHEN api_rate_limit_windows.window_started_at
                        = EXCLUDED.window_started_at
                    THEN LEAST(
                        api_rate_limit_windows.request_count + 1,
                        :maximumRequests + 1
                    )
                    ELSE 1
                END
            RETURNING window_started_at, request_count
        )
        SELECT
            upserted.request_count,
            GREATEST(
                1,
                ceil(extract(epoch FROM (
                    upserted.window_started_at
                    + make_interval(secs => :windowSeconds)
                    - timing.database_now
                )))::bigint
            ) AS retry_after_seconds
        FROM upserted CROSS JOIN timing
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresFixedWindowRateLimitRepository(
        NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RateLimitResult increment(UUID subjectId,
        RateLimitOperation operation, int maximumRequests,
        long windowSeconds) {
        Map<String, ?> parameters = Map.of(
            "subjectId", subjectId,
            "operation", operation.name(),
            "maximumRequests", maximumRequests,
            "windowSeconds", windowSeconds
        );
        return jdbcTemplate.queryForObject(
            INCREMENT_WINDOW,
            parameters,
            (resultSet, rowNumber) -> new RateLimitResult(
                resultSet.getInt("request_count") <= maximumRequests,
                resultSet.getLong("retry_after_seconds")
            )
        );
    }

    public record RateLimitResult(boolean allowed, long retryAfterSeconds) {}
}
