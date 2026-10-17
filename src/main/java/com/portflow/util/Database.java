package com.portflow.util;

import com.portflow.config.AppConfig;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded JDBC connection pool + transaction helper.
 * OS link: the pool is a counting-semaphore style resource (bounded, blocking with timeout, never unbounded).
 * DBMS link: inTransaction() gives every critical state change an explicit ACID boundary with rollback.
 */
public final class Database implements AutoCloseable {
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws SQLException; }

    private final AppConfig cfg;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger total = new AtomicInteger();
    private volatile boolean closed;

    public Database(AppConfig cfg) {
        this.cfg = cfg;
        this.idle = new ArrayBlockingQueue<>(Math.max(1, cfg.poolSize()));
    }

    public AppConfig config() { return cfg; }

    public Connection connection() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cfg.poolWaitMs());
        while (true) {
            Connection raw = idle.poll();
            if (raw != null) {
                if (raw.isValid(1)) return wrap(raw);
                total.decrementAndGet();
                try { raw.close(); } catch (SQLException ignored) { }
                continue;
            }
            if (total.incrementAndGet() <= cfg.poolSize()) {
                try { return wrap(java.sql.DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password())); }
                catch (SQLException e) { total.decrementAndGet(); throw e; }
            }
            total.decrementAndGet();
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SQLException("Connection pool exhausted (waited " + cfg.poolWaitMs() + " ms)", "08S01");
            try {
                Connection c = idle.poll(left, TimeUnit.NANOSECONDS);   // blocks until another thread returns a connection
                if (c != null) {
                    if (c.isValid(1)) return wrap(c);
                    total.decrementAndGet();
                    try { c.close(); } catch (SQLException ignored) { }
                }
            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new SQLException("Interrupted while waiting for a connection", ie); }
        }
    }

    private Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(Database.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, m, args) -> {
            if (m.getName().equals("close")) {
                try {
                    if (!raw.getAutoCommit()) { raw.rollback(); raw.setAutoCommit(true); }
                    raw.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    if (!closed && idle.offer(raw)) return null;
                } catch (SQLException ignored) { }
                total.decrementAndGet();
                try { raw.close(); } catch (SQLException ignored) { }
                return null;
            }
            try { return m.invoke(raw, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    }

    /** Runs work in one transaction. Retries (max 3) only on deadlock/serialization failures (SQLSTATE 40001). */
    public <T> T inTransaction(int isolation, Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Connection c = connection();
            try {
                c.setAutoCommit(false);
                c.setTransactionIsolation(isolation);
                T r = work.run(c);
                c.commit();
                return r;
            } catch (SQLException e) {
                rollbackQuietly(c);
                if ("40001".equals(e.getSQLState()) && attempt < 3) { last = e; backoff(attempt); continue; }
                throw e;
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                c.close();
            }
        }
        throw last;
    }

    public <T> T inTransaction(Work<T> work) throws SQLException { return inTransaction(Connection.TRANSACTION_READ_COMMITTED, work); }

    public <T> T read(Work<T> work) throws SQLException {
        try (Connection c = connection()) { return work.run(c); }
    }

    private static void rollbackQuietly(Connection c) { try { c.rollback(); } catch (SQLException ignored) { } }
    private static void backoff(int n) { try { Thread.sleep(20L * n); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** Returns null when reachable, otherwise a user-friendly message. */
    public String checkConnection() {
        try (Connection c = connection()) { return c.isValid(2) ? null : "Database did not respond."; }
        catch (SQLException e) { return friendly(e); }
    }

    public static String friendly(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String s = String.valueOf(e.getMessage()).toLowerCase() + " " + String.valueOf(t.getMessage()).toLowerCase();
        if (s.contains("communications link") || s.contains("connection refused") || s.contains("could not connect") || s.contains("08001") || s.contains("unable to connect"))
            return "Cannot reach the MySQL server. Check that MySQL is running and that db.url in portflow.properties is correct.";
        if (s.contains("access denied")) return "MySQL rejected the user name or password in portflow.properties.";
        if (s.contains("unknown database")) return "The PortFlow database does not exist yet. Run database/01_schema.sql first (see README).";
        if (s.contains("pool exhausted")) return "The system is busy (no free database connection). Try again in a moment.";
        if (s.contains("doesn't exist")) return "A PortFlow table is missing. Re-run the database setup scripts.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public void close() {
        closed = true;
        Connection c;
        while ((c = idle.poll()) != null) { try { c.close(); } catch (SQLException ignored) { } }
    }
}
