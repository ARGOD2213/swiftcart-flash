package dev.mahindra.swiftcart.auth;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import dev.mahindra.swiftcart.IntegrationTest;

class AuthIT extends IntegrationTest {

    @Test
    void registerThenLogin() throws Exception {
        String email = "Asha." + UUID.randomUUID() + "@Example.com";
        register(email, "correct horse battery", "Asha").andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.customer.role").value("CUSTOMER"));
        String token = json.readTree(login(email.toLowerCase(), "correct horse battery").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Asha"));
    }

    @Test
    void anEmailIsOneAccountWhateverItsCase() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        register(email, "correct horse battery", "A").andExpect(status().isCreated());
        register(email.toUpperCase(), "correct horse battery", "B").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    void weakOrOverlongPasswordsAreRefused() throws Exception {
        String email = "pw-" + UUID.randomUUID() + "@example.com";
        register(email, "short", "A").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        register(email, "aaaaaaaaaaaa", "A").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("password_too_weak"));
        // 73 bytes: bcrypt would silently ignore the tail
        register(email, "é".repeat(37), "A").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("password_too_long"));
        register("not-an-email", "correct horse battery", "A").andExpect(status().isBadRequest());
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountForAWhile() throws Exception {
        String email = "lock-" + UUID.randomUUID() + "@example.com";
        register(email, "correct horse battery", "A").andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            login(email, "wrong password!!").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_credentials"));
        }
        login(email, "correct horse battery").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_attempts"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void unknownEmailsLookExactlyLikeWrongPasswords() throws Exception {
        login("nobody-" + UUID.randomUUID() + "@example.com", "whatever password")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void protectedEndpointsNeedAValidToken() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/api/orders").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/api/admin/drops/" + UUID.randomUUID() + "/audit").header("Authorization", "Bearer " + shopper().token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void configuredAdminEmailsGetTheAdminRole() throws Exception {
        String email = "admin@test.local";
        if (customers.findByEmail(email).isEmpty()) {
            register(email, "correct horse battery", "Admin").andExpect(jsonPath("$.customer.role").value("ADMIN"));
        }
    }

    private ResultActions register(String email, String password, String name) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password, "name", name))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))));
    }
}
