package com.booking.movie;

import com.booking.movie.service.OutboxClaims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

// No explicit profile: verify that a plain startup defaults to H2.
@SpringBootTest(properties = "payment.outbox.enabled=false")
class MovieApplicationTests {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private OutboxClaims claims;

    @Test
    void defaultDatabaseIsH2AndOutboxQueryWorks() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        }
        assertTrue(claims.claimBatch().isEmpty());
    }
}
