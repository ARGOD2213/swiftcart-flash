package dev.mahindra.swiftcart.fulfilment;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.order.Order;

/**
 * Hands a paid order to the warehouse. The order id is the idempotency key, so a redelivered event never ships
 * twice. Without a configured URL (local development), orders stay PAID.
 */
@Component
public class WarehouseClient {

    private final RestClient http;
    private final boolean enabled;

    public WarehouseClient(SwiftcartProperties props) {
        SwiftcartProperties.Warehouse w = props.warehouse();
        this.enabled = StringUtils.hasText(w.url());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .baseUrl(enabled ? w.url() : "http://localhost")
                .requestFactory(factory)
                .defaultHeaders(h -> {
                    if (StringUtils.hasText(w.apiKey())) {
                        h.setBearerAuth(w.apiKey());
                    }
                })
                .build();
    }

    /** The warehouse's shipment reference, or empty when no warehouse is configured. */
    public Optional<String> ship(Order order, String sku) {
        if (!enabled) {
            return Optional.empty();
        }
        JsonNode r = http.post().uri("/shipments")
                .header("Idempotency-Key", "order-" + order.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", order.id().toString(), "sku", sku, "quantity", order.quantity(),
                        "phone", order.phone(), "address", order.shippingAddressJson()))
                .retrieve().body(JsonNode.class);
        return Optional.of(r == null ? "unknown" : r.path("id").asText("unknown"));
    }
}
