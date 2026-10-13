package dev.mahindra.swiftcart.ops;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import dev.mahindra.swiftcart.drop.Drop;
import dev.mahindra.swiftcart.drop.DropRepository;

/**
 * Drop-aware pre-scaling. KEDA's metrics-api scaler polls this and scales checkout pods to
 * {@code checkoutReplicas} from {@code lead} before a drop opens until it closes, sized from the drop's expected
 * peak. Pods are warm before the crowd arrives instead of scaling after the spike hits; CPU-based scaling still
 * applies on top. Internal only: the ingress doesn't route {@code /internal}.
 */
@RestController
class ScalingController {

    private final DropRepository drops;
    private final SwiftcartProperties.Scaling scaling;
    private final Clock clock;

    ScalingController(DropRepository drops, SwiftcartProperties props, Clock clock) {
        this.drops = drops;
        this.scaling = props.scaling();
        this.clock = clock;
    }

    @GetMapping("/internal/scaling")
    Map<String, Object> scaling() {
        Instant now = clock.instant();
        Optional<Drop> busiest = drops.findActive(now).stream()
                .filter(d -> !now.isBefore(d.opensAt().minus(scaling.lead())) && now.isBefore(d.closesAt()))
                .max(Comparator.comparingInt(Drop::expectedPeakRps));
        int replicas = busiest
                .map(d -> (int) Math.ceil((double) d.expectedPeakRps() / Math.max(1, scaling.rpsPerPod())))
                .map(r -> Math.max(scaling.minReplicas(), Math.min(scaling.maxReplicas(), r)))
                .orElse(scaling.minReplicas());
        return Map.of(
                "checkoutReplicas", replicas,
                "reason", busiest.map(d -> "drop " + d.slug() + " expects " + d.expectedPeakRps() + " rps").orElse("no drop soon"));
    }
}
