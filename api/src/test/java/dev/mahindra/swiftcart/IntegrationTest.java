package dev.mahindra.swiftcart;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import dev.mahindra.swiftcart.auth.Customer;
import dev.mahindra.swiftcart.auth.CustomerRepository;
import dev.mahindra.swiftcart.auth.TokenService;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropAdminService;
import dev.mahindra.swiftcart.inventory.InventoryService;
import dev.mahindra.swiftcart.order.OrderRepository;
import dev.mahindra.swiftcart.payment.Gateways;
import dev.mahindra.swiftcart.payment.SimulatorGateway;
import dev.mahindra.swiftcart.payment.WebhookService;
import dev.mahindra.swiftcart.queue.EntryTokenService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Real Postgres 16, Redis 7 and LocalStack (SQS FIFO) in containers, plus WireMock as the warehouse API. Background
 * job timers are off; tests drive the jobs (reaper, admissions, relay, reconciler) step by step.
 *
 * <p>Every test creates its own drop and shoppers, so tests don't share state and can run in any order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("swiftcart").withUsername("swiftcart").withPassword("swiftcart");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    protected static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.SQS);
    protected static final WireMockServer WAREHOUSE = new WireMockServer(options().dynamicPort());
    protected static final String QUEUE_URL;
    protected static final String SIM_SECRET = "test-simulator-webhook-secret";

    static {
        POSTGRES.start();
        REDIS.start();
        LOCALSTACK.start();
        WAREHOUSE.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        try (SqsClient sqs = SqsClient.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            QUEUE_URL = sqs.createQueue(r -> r.queueName("fulfilment.fifo").attributes(Map.of(
                    QueueAttributeName.FIFO_QUEUE, "true",
                    QueueAttributeName.VISIBILITY_TIMEOUT, "5"))).queueUrl();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("swiftcart.security.jwt-secret", () -> "test-jwt-secret-0123456789abcdef0123456789");
        r.add("swiftcart.security.entry-token-secret", () -> "test-entry-secret-0123456789abcdef012345678");
        r.add("swiftcart.security.admin-emails", () -> "admin@test.local");
        r.add("swiftcart.payments.gateway", () -> "simulator");
        r.add("swiftcart.payments.simulator.webhook-secret", () -> SIM_SECRET);
        r.add("swiftcart.aws.region", LOCALSTACK::getRegion);
        r.add("swiftcart.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        r.add("swiftcart.aws.fulfilment-queue-url", () -> QUEUE_URL);
        r.add("swiftcart.warehouse.url", WAREHOUSE::baseUrl);
        r.add("swiftcart.scheduling.enabled", () -> "false");
        r.add("swiftcart.worker.enabled", () -> "true");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected DropAdminService dropAdmin;
    @Autowired
    protected InventoryService inventory;
    @Autowired
    protected CustomerRepository customers;
    @Autowired
    protected TokenService tokens;
    @Autowired
    protected EntryTokenService entryTokens;
    @Autowired
    protected OrderRepository orders;
    @Autowired
    protected Gateways gateways;
    @Autowired
    protected WebhookService webhooks;

    protected record Shopper(UUID id, String email, String token) {
    }

    protected Shopper shopper() {
        String email = "shopper-" + UUID.randomUUID() + "@test.local";
        Customer c = customers.insert(new Customer(UUID.randomUUID(), email, "$2a$04$unused", "Test Shopper", "CUSTOMER"));
        return new Shopper(c.id(), email, tokens.issue(c).token());
    }

    protected Shopper admin() {
        Customer c = customers.findByEmail("admin@test.local").orElseGet(() ->
                customers.insert(new Customer(UUID.randomUUID(), "admin@test.local", "$2a$04$unused", "Admin", "ADMIN")));
        return new Shopper(c.id(), c.email(), tokens.issue(c).token());
    }

    /** An armed drop that is open right now. */
    protected Drop liveDrop(int stockPerSku, int limit) {
        return drop(Instant.now().minusSeconds(5), stockPerSku, limit, 600, 1000);
    }

    protected Drop drop(Instant opensAt, int stockPerSku, int limit, int ttlSeconds, int admitPerSecond) {
        String slug = "drop-" + UUID.randomUUID().toString().substring(0, 8);
        Drop d = dropAdmin.create(new DropAdminService.NewDrop(slug, "Test drop", "", null,
                opensAt.minus(Duration.ofMinutes(10)), opensAt, opensAt.plus(Duration.ofHours(1)), limit, admitPerSecond,
                ttlSeconds, 300, List.of(
                        new DropAdminService.NewSku("TEE-M", "Tee M", 149_900, "INR", stockPerSku),
                        new DropAdminService.NewSku("TEE-L", "Tee L", 149_900, "INR", stockPerSku))));
        return dropAdmin.arm(d.id());
    }

    protected String entryToken(Shopper s, Drop d) {
        return entryTokens.issue(s.id(), d.id(), 1).token();
    }

    protected static String phone(int n) {
        return String.format("98%08d", n);
    }

    protected static String uniquePhone() {
        return String.format("9%09d", (long) (Math.random() * 1_000_000_000L));
    }

    protected String body(String sku, int qty, String phone) {
        return """
                {"sku":"%s","quantity":%d,"phone":"%s",
                 "address":{"name":"Asha Rao","line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","pincode":"560001"}}
                """.formatted(sku, qty, phone);
    }

    protected MvcResult checkout(Shopper s, Drop d, String key, String body) throws Exception {
        return mvc.perform(post("/api/drops/" + d.slug() + "/checkout")
                        .header("Authorization", "Bearer " + s.token())
                        .header("Idempotency-Key", key)
                        .header("X-Entry-Token", entryToken(s, d))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Checks out one unit and returns the order JSON (asserting 201). */
    protected JsonNode order(Shopper s, Drop d, String sku, int qty) throws Exception {
        MvcResult r = checkout(s, d, UUID.randomUUID().toString(), body(sku, qty, uniquePhone()));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("checkout failed: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected SimulatorGateway sim() {
        return gateways.simulator();
    }

    /** The simulated shopper pays; the signed webhook goes through the real webhook path. */
    protected WebhookService.Result pay(JsonNode order, String status, long amountMinor) {
        String gatewayOrderId = order.path("payment").path("gatewayOrderId").asText();
        SimulatorGateway.SignedWebhook hook = sim().pay(gatewayOrderId, status, amountMinor, "INR", null);
        return webhooks.handle("simulator", hook.body(), hook.headers());
    }

    protected WebhookService.Result pay(JsonNode order) {
        return pay(order, "captured", order.path("amountMinor").asLong());
    }

    protected String orderStatus(String orderId) {
        return jdbc.sql("select status from orders where id = ?").param(UUID.fromString(orderId)).query(String.class).single();
    }

    protected long stock(Drop d, String sku) {
        return inventory.available(d.id()).get(sku);
    }

    /** Makes a held reservation's time run out without waiting: moves its expiry into the past. */
    protected void expireNow(Drop d, String orderId) {
        UUID reservation = orders.find(UUID.fromString(orderId)).orElseThrow().reservationId();
        long past = System.currentTimeMillis() - 1000;
        redis.opsForHash().put(dev.mahindra.swiftcart.inventory.Keys.reservation(d.id(), reservation), "exp", String.valueOf(past));
        redis.opsForZSet().add(dev.mahindra.swiftcart.inventory.Keys.expiry(d.id()), reservation.toString(), past);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?").param(UUID.fromString(orderId)).update();
    }
}
