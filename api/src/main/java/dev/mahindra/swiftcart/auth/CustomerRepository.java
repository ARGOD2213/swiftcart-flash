package dev.mahindra.swiftcart.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.mahindra.swiftcart.config.Db;

@Repository
public class CustomerRepository {

    private static final RowMapper<Customer> MAPPER = (rs, i) -> new Customer(Db.uuid(rs, "id"), rs.getString("email"),
            rs.getString("password_hash"), rs.getString("name"), rs.getString("role"));

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findByEmail(String email) {
        return jdbc.sql("select * from customers where lower(email) = lower(?)").param(email.trim()).query(MAPPER).optional();
    }

    public Optional<Customer> findById(UUID id) {
        return jdbc.sql("select * from customers where id = ?").param(id).query(MAPPER).optional();
    }

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the email (any case) is taken. */
    public Customer insert(Customer c) {
        jdbc.sql("insert into customers (id, email, password_hash, name, role) values (?, ?, ?, ?, ?)")
                .params(c.id(), c.email(), c.passwordHash(), c.name(), c.role())
                .update();
        return c;
    }

    public void updateRole(UUID id, String role) {
        jdbc.sql("update customers set role = ? where id = ?").params(role, id).update();
    }
}
