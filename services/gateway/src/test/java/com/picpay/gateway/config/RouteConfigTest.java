package com.picpay.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = RouteConfigTest.TestConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "services.payment.url=http://localhost:8081",
    "services.billing.url=http://localhost:8082",
    "services.token.url=http://localhost:8083",
    "spring.main.web-application-type=reactive"
})
class RouteConfigTest {

    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcDataAutoConfiguration.class,
        R2dbcRepositoriesAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    static class TestConfig extends RouteConfig {
    }

    @Autowired
    RouteLocator routeLocator;

    @Test
    void shouldHaveThreeRoutes() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull().hasSize(3);

        // verify route IDs
        List<String> ids = routes.stream().map(r -> r.getId()).collect(java.util.stream.Collectors.toList());
        assertThat(ids).containsExactlyInAnyOrder("payment", "billing", "token");

        // verify URIs
        List<String> uris = routes.stream().map(r -> r.getUri().toString()).collect(java.util.stream.Collectors.toList());
        assertThat(uris).containsExactlyInAnyOrder("http://localhost:8081", "http://localhost:8082", "http://localhost:8083");
    }
}
