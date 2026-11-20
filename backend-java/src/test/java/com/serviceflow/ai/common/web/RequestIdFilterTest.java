package com.serviceflow.ai.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 请求追踪过滤器：ID 生成、透传、非法值兜底与 MDC 清理。 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String runWithHeader(String incoming, AtomicReference<String> mdcSeen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tickets");
        if (incoming != null) {
            request.addHeader(RequestIdFilter.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> mdcSeen.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID)));
        return response.getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void generatesIdWhenHeaderMissing() throws Exception {
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        String id = runWithHeader(null, mdcSeen);

        assertNotNull(id);
        assertEquals(id, mdcSeen.get());
        assertEquals(36, id.length());
        assertNull(MDC.get(RequestIdFilter.MDC_REQUEST_ID));
    }

    @Test
    void reusesUpstreamId() throws Exception {
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        String id = runWithHeader("upstream-1234", mdcSeen);

        assertEquals("upstream-1234", id);
        assertEquals("upstream-1234", mdcSeen.get());
    }

    @Test
    void replacesIllegalHeaderValue() throws Exception {
        AtomicReference<String> mdcSeen = new AtomicReference<>();
        String id = runWithHeader("bad id with spaces and <script>", mdcSeen);

        assertNotEquals("bad id with spaces and <script>", id);
        assertEquals(id, mdcSeen.get());
        assertEquals(36, id.length());
    }
}
