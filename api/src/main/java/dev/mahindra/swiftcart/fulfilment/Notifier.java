package dev.mahindra.swiftcart.fulfilment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.mahindra.swiftcart.config.SwiftcartProperties;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** Customer emails: SES when a sender address is configured, otherwise logged (local and CI). */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final SesV2Client ses;
    private final String from;

    public Notifier(SesV2Client ses, SwiftcartProperties props) {
        this.ses = ses;
        this.from = props.aws().sesFromAddress();
    }

    /** Returns the provider's message id. */
    public String send(String to, String subject, String text) {
        if (!StringUtils.hasText(from)) {
            log.info("EMAIL to={} subject=\"{}\"\n{}", to, subject, text);
            return "logged";
        }
        return ses.sendEmail(r -> r
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(to))
                .content(c -> c.simple(m -> m
                        .subject(s -> s.data(subject).charset("UTF-8"))
                        .body(b -> b.text(t -> t.data(text).charset("UTF-8"))))))
                .messageId();
    }
}
