package dev.mahindra.swiftcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SwiftcartApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwiftcartApplication.class, args);
    }
}
