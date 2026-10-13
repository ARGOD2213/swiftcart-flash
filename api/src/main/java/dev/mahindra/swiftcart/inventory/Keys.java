package dev.mahindra.swiftcart.inventory;

import java.util.UUID;

/**
 * Redis keys of one drop. The {@code {dropId}} hash tag keeps all of a drop's keys in one cluster slot, so the
 * Lua scripts that touch several of them stay valid on Redis Cluster.
 */
public final class Keys {

    private Keys() {
    }

    static String base(UUID drop) {
        return "sc:{" + drop + "}:";
    }

    public static String meta(UUID drop) {
        return base(drop) + "meta";
    }

    public static String stock(UUID drop) {
        return base(drop) + "stock";
    }

    public static String heldByCustomer(UUID drop) {
        return base(drop) + "held:cust";
    }

    public static String heldByPhone(UUID drop) {
        return base(drop) + "held:phone";
    }

    public static String reservation(UUID drop, UUID reservation) {
        return base(drop) + "res:" + reservation;
    }

    public static String reservationPattern(UUID drop) {
        return base(drop) + "res:*";
    }

    public static String expiry(UUID drop) {
        return base(drop) + "expiry";
    }

    public static String queueTickets(UUID drop) {
        return base(drop) + "q:tickets";
    }

    public static String queueSeq(UUID drop) {
        return base(drop) + "q:seq";
    }

    public static String queueAdmitted(UUID drop) {
        return base(drop) + "q:admitted";
    }

    public static String queuePaused(UUID drop) {
        return base(drop) + "q:paused";
    }
}
