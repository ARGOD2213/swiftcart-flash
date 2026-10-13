package dev.mahindra.swiftcart.dev;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.auth.Customer;
import dev.mahindra.swiftcart.auth.CustomerRepository;
import dev.mahindra.swiftcart.auth.TokenService;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropAdminService;
import dev.mahindra.swiftcart.drop.DropRepository;
import dev.mahindra.swiftcart.order.Order;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.SimulatorGateway;
import dev.mahindra.swiftcart.payment.WebhookService;

/**
 * Local development and load testing only (profile {@code dev}): seed shoppers without paying bcrypt per
 * account, open a demo drop, and "pay" through the simulated gateway, which sends a properly signed webhook
 * through the normal webhook path.
 */
@RestController
@RequestMapping("/dev")
@Profile("dev")
class DevController {

    private final CustomerRepository customers;
    private final TokenService tokens;
    private final DropAdminService admin;
    private final DropRepository drops;
    private final OrderRepository orders;
    private final Gateways gateways;
    private final WebhookService webhooks;
    private final Clock clock;
    private final String sharedHash = new BCryptPasswordEncoder(4).encode("loadtest-password");

    DevController(CustomerRepository customers, TokenService tokens, DropAdminService admin, DropRepository drops,
            OrderRepository orders, Gateways gateways, WebhookService webhooks, Clock clock) {
        this.customers = customers;
        this.tokens = tokens;
        this.admin = admin;
        this.drops = drops;
        this.orders = orders;
        this.gateways = gateways;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    /** Creates (or reuses) {@code count} shoppers named {prefix}-{n}@load.test and returns their access tokens. */
    @PostMapping("/customers/bulk")
    List<String> bulk(@RequestParam(defaultValue = "100") int count, @RequestParam(defaultValue = "shopper") String prefix) {
        if (count < 1 || count > 20_000) {
            throw ApiException.badRequest("invalid_count", "count must be 1..20000");
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String email = prefix + "-" + i + "@load.test";
            Customer c = customers.findByEmail(email).orElse(null);
            if (c == null) {
                c = new Customer(UUID.randomUUID(), email, sharedHash, "Shopper " + i, "CUSTOMER");
                try {
                    customers.insert(c);
                } catch (DuplicateKeyException e) {
                    c = customers.findByEmail(email).orElseThrow();
                }
            }
            out.add(tokens.issue(c).token());
        }
        return out;
    }

    /** A drop that opens now (or after {@code opensInSeconds}), armed and ready. */
    @PostMapping("/drops/demo")
    Map<String, Object> demo(@RequestParam(defaultValue = "demo-drop") String slug,
            @RequestParam(defaultValue = "0") int opensInSeconds, @RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "50") int stock, @RequestParam(defaultValue = "2") int limit,
            @RequestParam(defaultValue = "200") int admitPerSecond, @RequestParam(defaultValue = "600") int ttlSeconds) {
        Instant now = clock.instant();
        Instant opens = now.plusSeconds(opensInSeconds);
        Drop d = drops.findBySlug(slug).orElseGet(() -> admin.create(new DropAdminService.NewDrop(slug, "Midnight Runner",
                "A limited run of the Midnight Runner sneaker. One drop, no restocks.", "/images/midnight-runner.svg",
                opens.minus(Duration.ofMinutes(5)).isBefore(now) ? now : opens.minus(Duration.ofMinutes(5)), opens,
                opens.plus(Duration.ofMinutes(minutes)), limit, admitPerSecond, ttlSeconds, 500,
                List.of(new DropAdminService.NewSku("MR-UK8", "Midnight Runner · UK 8", 1_299_900, "INR", stock),
                        new DropAdminService.NewSku("MR-UK9", "Midnight Runner · UK 9", 1_299_900, "INR", stock)))));
        Drop armed = admin.arm(d.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", armed.id());
        out.put("slug", armed.slug());
        out.put("opensAt", armed.opensAt());
        out.put("status", armed.status());
        return out;
    }

    /**
     * Simulates the shopper finishing (or failing) payment for an order's gateway order. {@code deliver=false}
     * records the payment without sending the webhook (a lost webhook, for the reconciler to find);
     * {@code duplicate=true} delivers it twice.
     */
    @PostMapping("/simulator/pay")
    Map<String, Object> pay(@RequestBody Pay body) {
        SimulatorGateway sim = gateways.simulator();
        Order order = orders.byGatewayOrder("simulator", body.gatewayOrderId())
                .orElseThrow(() -> ApiException.notFound("order_not_found", "No order for " + body.gatewayOrderId()));
        long amount = body.amountMinor() != null ? body.amountMinor() : order.amountMinor();
        SimulatorGateway.SignedWebhook hook = sim.pay(body.gatewayOrderId(), body.status() == null ? "captured" : body.status(),
                amount, order.currency(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", hook.payment().paymentId());
        out.put("signature", sim.callbackSignature(body.gatewayOrderId(), hook.payment().paymentId()));
        if (body.deliver() == null || body.deliver()) {
            out.put("webhook", webhooks.handle("simulator", hook.body(), hook.headers()).status());
            if (Boolean.TRUE.equals(body.duplicate())) {
                out.put("duplicate", webhooks.handle("simulator", hook.body(), hook.headers()).status());
            }
        }
        return out;
    }

    record Pay(String gatewayOrderId, String status, Long amountMinor, Boolean deliver, Boolean duplicate) {
    }
}
