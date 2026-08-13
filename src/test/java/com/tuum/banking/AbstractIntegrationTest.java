package com.tuum.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuum.banking.config.RabbitMqConfig;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.enums.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared Testcontainers stack for the integration suite.
 *
 * <p>Containers are {@code static} and started once per JVM rather than per class:
 * booting Postgres and RabbitMQ for every test class would dominate the run, and both
 * are cheap to isolate between tests by truncating tables and draining queues.
 *
 * <p>Deliberately no Ryuk opt-out or fixed ports — the suite must run unchanged on a
 * reviewer's machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("banking")
                    .withUsername("banking")
                    .withPassword("banking");

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        // RESTART IDENTITY keeps generated ids predictable per test.
        jdbcTemplate.execute("TRUNCATE TABLE transaction, balance, account RESTART IDENTITY CASCADE");
        rabbitAdmin.purgeQueue(RabbitMqConfig.ACCOUNT_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfig.TRANSACTION_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqConfig.BALANCE_QUEUE, false);
    }

    protected AccountResponse createAccount(Currency... currencies) {
        AccountResponse response = restTemplate.postForObject("/accounts",
                new CreateAccountRequest(1L, "EE", List.of(currencies)), AccountResponse.class);
        assertThat(response).isNotNull();
        return response;
    }

    /**
     * Drains a queue, waiting up to 5s for the first message, and parses bodies as raw
     * JSON so assertions run against the exact wire contract documented in the README
     * rather than a re-serialized object.
     */
    protected List<JsonNode> drainQueue(String queueName) {
        return drainQueue(queueName, Duration.ofSeconds(5));
    }

    /**
     * Drains whatever is already on the queue without blocking.
     *
     * <p>Use this to assert <em>absence</em>: a blocking read would burn the caller's
     * whole timeout budget waiting for a message that is never going to arrive.
     */
    protected List<JsonNode> drainQueueNow(String queueName) {
        return drainQueue(queueName, Duration.ZERO);
    }

    private List<JsonNode> drainQueue(String queueName, Duration firstReadTimeout) {
        List<JsonNode> messages = new ArrayList<>();
        rabbitTemplate.setReceiveTimeout(firstReadTimeout.toMillis());
        Message message;
        while ((message = rabbitTemplate.receive(queueName)) != null) {
            try {
                messages.add(objectMapper.readTree(message.getBody()));
            } catch (Exception e) {
                throw new IllegalStateException("Message on %s was not valid JSON".formatted(queueName), e);
            }
            // Only the first read waits; the rest drain whatever is already there.
            rabbitTemplate.setReceiveTimeout(0);
        }
        return messages;
    }
}
