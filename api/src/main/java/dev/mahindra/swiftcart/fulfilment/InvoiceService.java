package dev.mahindra.swiftcart.fulfilment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.mahindra.swiftcart.order.Order;

/**
 * Invoices with gapless numbers per Indian financial year (April-March), e.g. {@code SC/2026-27/000042}.
 * The counter row is locked and incremented in the same transaction as the invoice insert, so a rolled-back
 * invoice gives its number back. One invoice per order, however many times the event is delivered.
 */
@Service
public class InvoiceService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;

    public InvoiceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(Order order, Instant when) {
        Optional<String> existing = jdbc.sql("select number from invoices where order_id = ?").param(order.id())
                .query(String.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String fy = financialYear(LocalDate.ofInstant(when, INDIA));
        jdbc.sql("insert into invoice_counters (financial_year, next_number) values (?, 1) on conflict do nothing").param(fy).update();
        int n = jdbc.sql("update invoice_counters set next_number = next_number + 1 where financial_year = ? returning next_number - 1")
                .param(fy).query(Integer.class).single();
        String number = "SC/" + fy + "/" + String.format("%06d", n);
        jdbc.sql("insert into invoices (id, order_id, number, amount_minor, currency) values (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), order.id(), number, order.amountMinor(), order.currency()).update();
        return number;
    }

    static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
