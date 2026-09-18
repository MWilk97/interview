package com.mwilk.ledger.app.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void keepsTheCallersRequestIdAndEchoesItBack() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "trace-1");
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInsideTheChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seenInsideTheChain).hasValue("trace-1");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("trace-1");
    }

    @Test
    void generatesAnIdWhenTheCallerSuppliesNone() throws Exception {
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInsideTheChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seenInsideTheChain.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInsideTheChain.get());
    }

    @Test
    void clearsTheRequestIdEvenWhenTheChainThrows() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
