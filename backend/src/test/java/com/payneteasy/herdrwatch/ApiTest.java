package com.payneteasy.herdrwatch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Интеграционные тесты HTTP-слоя. Профиль %test отключает Quinoa и все bootstrap-хосты,
 * поэтому приложение стартует без сборки фронта и без реальных ssh/bash-источников.
 */
@QuarkusTest
class ApiTest {

    @Test
    void listServersReturnsJsonArray() {
        given()
                .when().get("/api/servers")
                .then().statusCode(200)
                .contentType("application/json");
    }

    @Test
    void createWithoutHostFailsValidation() {
        given()
                .contentType("application/json")
                .body("{\"id\":\"unit-test-host\"}")
                .when().post("/api/servers")
                .then().statusCode(400)
                .body("errors.host", notNullValue());
    }

    @Test
    void healthIsUp() {
        given()
                .when().get("/q/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }
}
