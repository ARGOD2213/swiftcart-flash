package dev.mahindra.swiftcart.ops;

import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cluster-wide mutual exclusion with Postgres advisory locks: no extra infrastructure, and a lock dies with the
 * transaction that holds it, so a crashed pod can't leave one behind.
 */
@Component
public class Locks {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public Locks(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Must be called inside a transaction; the lock is released when it ends. */
    public boolean tryTransactionLock(String name) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtext(?))").param(name).query(Boolean.class).single());
    }

    /**
     * Runs {@code job} only on the instance that wins the lock for this round; the others skip it. Used for the
     * once-a-second jobs (reaper, admissions, outbox relay) that should run on exactly one pod at a time.
     */
    public <T> T asLeader(String name, Supplier<T> job, T skipped) {
        T result = tx.execute(status -> tryTransactionLock("leader:" + name) ? job.get() : skipped);
        return result == null ? skipped : result;
    }
}
