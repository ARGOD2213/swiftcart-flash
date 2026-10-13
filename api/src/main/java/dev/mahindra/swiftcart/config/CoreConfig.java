package dev.mahindra.swiftcart.config;

import java.net.URI;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(SwiftcartProperties props) {
        var aws = props.aws();
        var builder = SqsClient.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    SesV2Client sesClient(SwiftcartProperties props) {
        var aws = props.aws();
        var builder = SesV2Client.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
    }
}
