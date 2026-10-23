package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SD-1: verifies per-customer authorization. A principal (Cognito {@code sub})
 * bound to customer A must not be able to transact for customer B.
 */
@WebMvcTest(TransferController.class)
@Import({ PrincipalCustomerResolver.class, LimitEnforcementProperties.class })
class TransferControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransferService transferService;

    @Autowired
    private LimitEnforcementProperties properties;

    private void mapSubAToCustA() {
        properties.getPrincipalCustomerMap().clear();
        properties.getPrincipalCustomerMap().put("sub-A", "cust-A");
    }

    @Test
    void principalForCustomerACannotTransactForCustomerB() throws Exception {
        mapSubAToCustA();

        mockMvc.perform(post("/api/transfers")
                        .header("X-Auth-Sub", "sub-A")
                        .contentType("application/json")
                        .content("{\"transferId\":\"t1\",\"customerId\":\"cust-B\",\"amount\":100.00,\"currency\":\"USD\"}"))
                .andExpect(status().isForbidden());

        // The transfer must never reach the service when authorization fails.
        verify(transferService, never()).transfer(any());
    }

    @Test
    void principalForCustomerACanTransactForCustomerA() throws Exception {
        mapSubAToCustA();
        when(transferService.transfer(any()))
                .thenReturn(new TransferResponse("t1", "r1", TransferOutcome.COMMITTED,
                        new BigDecimal("100.00"), "USD"));

        mockMvc.perform(post("/api/transfers")
                        .header("X-Auth-Sub", "sub-A")
                        .contentType("application/json")
                        .content("{\"transferId\":\"t1\",\"customerId\":\"cust-A\",\"amount\":100.00,\"currency\":\"USD\"}"))
                .andExpect(status().isOk());

        verify(transferService).transfer(any());
    }

    @Test
    void bindingFromMappingsStringAuthorizesOnlyItsOwnCustomer() throws Exception {
        properties.getPrincipalCustomerMap().clear();
        properties.setPrincipalCustomerMappings("sub-X=cust-X, sub-Y=cust-Y");
        try {
            when(transferService.transfer(any()))
                    .thenReturn(new TransferResponse("t1", "r1", TransferOutcome.COMMITTED,
                            new BigDecimal("100.00"), "USD"));
            mockMvc.perform(post("/api/transfers")
                            .header("X-Auth-Sub", "sub-Y")
                            .contentType("application/json")
                            .content("{\"transferId\":\"t1\",\"customerId\":\"cust-Y\",\"amount\":100.00,\"currency\":\"USD\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/transfers")
                            .header("X-Auth-Sub", "sub-Y")
                            .contentType("application/json")
                            .content("{\"transferId\":\"t2\",\"customerId\":\"cust-X\",\"amount\":100.00,\"currency\":\"USD\"}"))
                    .andExpect(status().isForbidden());
        } finally {
            properties.setPrincipalCustomerMappings("");
        }
    }

    @Test
    void missingPrincipalHeaderIsForbidden() throws Exception {
        mapSubAToCustA();

        mockMvc.perform(post("/api/transfers")
                        .contentType("application/json")
                        .content("{\"transferId\":\"t1\",\"customerId\":\"cust-A\",\"amount\":100.00,\"currency\":\"USD\"}"))
                .andExpect(status().isForbidden());

        verify(transferService, never()).transfer(any());
    }
}
