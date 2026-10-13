package dev.mahindra.swiftcart.payment;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.config.SwiftcartProperties;

/**
 * The configured gateways. New orders use the active one; webhooks, refunds and reconciliation use whichever
 * gateway an order was created with, so switching providers doesn't strand in-flight orders.
 */
@Component
public class Gateways {

    private final Map<String, PaymentGateway> byName = new LinkedHashMap<>();
    private final String active;

    public Gateways(SwiftcartProperties props, ObjectMapper json, Clock clock) {
        SwiftcartProperties.Payments p = props.payments();
        if (StringUtils.hasText(p.razorpay().keyId()) && StringUtils.hasText(p.razorpay().keySecret())) {
            byName.put("razorpay", new RazorpayGateway(p.razorpay(), json));
        }
        if (StringUtils.hasText(p.stripe().secretKey())) {
            byName.put("stripe", new StripeGateway(p.stripe(), json, clock));
        }
        if ("simulator".equals(p.gateway())) {
            byName.put("simulator", new SimulatorGateway(p.simulator(), json, clock));
        }
        this.active = p.gateway();
    }

    public PaymentGateway active() {
        return get(active);
    }

    public PaymentGateway get(String name) {
        PaymentGateway g = byName.get(name);
        if (g == null) {
            throw ApiException.notFound("unknown_gateway", "Payment gateway " + name + " is not configured");
        }
        return g;
    }

    public SimulatorGateway simulator() {
        return (SimulatorGateway) get("simulator");
    }
}
