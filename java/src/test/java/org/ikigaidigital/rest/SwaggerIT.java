package org.ikigaidigital.rest;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerIT extends PostgresIntegrationTest {

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }) // assert on statuses ourselves
                .build();
    }

    @Test
    void servesTheHandWrittenContract() {
        ResponseEntity<String> response = client.get().uri("/openapi.yaml").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("operationId: getAllTimeDeposits")
                .contains("operationId: updateAllBalances");
    }

    @Test
    void swaggerUiIsAvailable() {
        assertThat(client.get().uri("/swagger-ui/index.html").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void swaggerUiLoadsTheHandWrittenContract() {
        String config = client.get().uri("/v3/api-docs/swagger-config").retrieve().body(String.class);

        assertThat(config).contains("\"url\":\"/openapi.yaml\"");
    }
}
