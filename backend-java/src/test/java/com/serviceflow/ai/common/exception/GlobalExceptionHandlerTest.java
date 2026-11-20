package com.serviceflow.ai.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void malformedJsonReturns400() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));
        var response = handler.handleUnreadable(e);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void unknownPathReturns404() {
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "/api/not-exists");
        var response = handler.handleNoResource(e);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
    }
}
