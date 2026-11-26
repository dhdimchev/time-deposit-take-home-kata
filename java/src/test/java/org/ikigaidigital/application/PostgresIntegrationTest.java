package org.ikigaidigital.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Base class for integration tests: full application on a random port, backed by a Testcontainers Postgres.
 * Every test starts from a freshly migrated database with the Flyway seed data.
 */
@SpringBootTest(
        classes = TimeDepositApplication.class,
        webEnvironment = RANDOM_PORT,
        properties = "spring.flyway.clean-disabled=false")
@Import(TestcontainersConfiguration.class)
public abstract class PostgresIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
    }
}
