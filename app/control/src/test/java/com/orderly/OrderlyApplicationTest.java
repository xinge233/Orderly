package com.orderly;

import com.orderly.api.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "orderly.contracts-root=../contracts",
                "orderly.web-root=../web"
        }
)
class OrderlyApplicationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReportsZeroModules() {
        ResponseEntity<HealthResponse> response =
                restTemplate.getForEntity("http://localhost:" + port + "/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status().value()).isEqualTo("ok");
        assertThat(response.getBody().modules()).isZero();
        assertThat(response.getBody().version()).isNotBlank();
    }
}
