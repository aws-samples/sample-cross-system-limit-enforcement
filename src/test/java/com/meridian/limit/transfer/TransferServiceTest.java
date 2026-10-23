package com.meridian.limit.transfer;

import com.meridian.limit.legacy.CoreExecutionResult;
import com.meridian.limit.legacy.CoreSettlementStatus;
import com.meridian.limit.legacy.LegacyCoreClient;
import com.meridian.limit.legacy.LegacyTotalUnavailableException;
import com.meridian.limit.reservation.ReservationStatus;
import com.meridian.limit.reservation.TransferReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TransferServiceTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferReservationRepository repository;

    @MockBean
    private LegacyCoreClient legacyCoreClient;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // Default: no legacy history, core settles successfully.
        when(legacyCoreClient.fetchDailyTotal(anyString(), any(), anyString()))
                .thenReturn(BigDecimal.ZERO);
        when(legacyCoreClient.execute(anyString(), anyString(), any(), anyString()))
                .thenReturn(CoreExecutionResult.SUCCESS);
    }

    @Test
    void committedTransferWithinLimit() {
        TransferResponse resp = transferService.transfer(
                new TransferRequest("t1", "cust-A", new BigDecimal("100.00"), "USD"));

        assertThat(resp.outcome()).isEqualTo(TransferOutcome.COMMITTED);
        assertThat(repository.findByTransferId("t1")).get()
                .extracting(r -> r.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    void rejectsWhenComposedTotalExceedsLimit() {
        // Legacy already used 49,999,999; a 2.00 cloud transfer breaches 50M.
        when(legacyCoreClient.fetchDailyTotal(eq("cust-B"), any(), anyString()))
                .thenReturn(new BigDecimal("49999999.00"));

        TransferResponse resp = transferService.transfer(
                new TransferRequest("t2", "cust-B", new BigDecimal("2.00"), "USD"));

        assertThat(resp.outcome()).isEqualTo(TransferOutcome.LIMIT_EXCEEDED);
        assertThat(repository.findByTransferId("t2")).isEmpty();
    }

    @Test
    void inFlightReservationCountsTowardTotal() {
        // Legacy at 49,999,000. First cloud transfer of 900 reserves+commits.
        when(legacyCoreClient.fetchDailyTotal(eq("cust-C"), any(), anyString()))
                .thenReturn(new BigDecimal("49999000.00"));

        TransferResponse first = transferService.transfer(
                new TransferRequest("t3a", "cust-C", new BigDecimal("900.00"), "USD"));
        assertThat(first.outcome()).isEqualTo(TransferOutcome.COMMITTED);

        // Second transfer of 200 would push 49,999,000 + 900 + 200 = 50,000,100 > 50M.
        TransferResponse second = transferService.transfer(
                new TransferRequest("t3b", "cust-C", new BigDecimal("200.00"), "USD"));
        assertThat(second.outcome()).isEqualTo(TransferOutcome.LIMIT_EXCEEDED);
    }

    @Test
    void coreRejectionReleasesReservation() {
        when(legacyCoreClient.execute(eq("t4"), anyString(), any(), anyString()))
                .thenReturn(CoreExecutionResult.FAILURE);

        TransferResponse resp = transferService.transfer(
                new TransferRequest("t4", "cust-D", new BigDecimal("100.00"), "USD"));

        assertThat(resp.outcome()).isEqualTo(TransferOutcome.REJECTED_BY_CORE);
        assertThat(repository.findByTransferId("t4")).get()
                .extracting(r -> r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void coreTimeoutLeavesReservationReserved() {
        when(legacyCoreClient.execute(eq("t5"), anyString(), any(), anyString()))
                .thenReturn(CoreExecutionResult.TIMEOUT);

        TransferResponse resp = transferService.transfer(
                new TransferRequest("t5", "cust-E", new BigDecimal("100.00"), "USD"));

        assertThat(resp.outcome()).isEqualTo(TransferOutcome.PENDING_RECONCILIATION);
        assertThat(repository.findByTransferId("t5")).get()
                .extracting(r -> r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void idempotentReuseOfTransferId() {
        TransferResponse first = transferService.transfer(
                new TransferRequest("t6", "cust-F", new BigDecimal("100.00"), "USD"));
        TransferResponse second = transferService.transfer(
                new TransferRequest("t6", "cust-F", new BigDecimal("100.00"), "USD"));

        assertThat(first.reservationId()).isEqualTo(second.reservationId());
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void failsClosedWhenLegacyTotalUnavailable() {
        when(legacyCoreClient.fetchDailyTotal(eq("cust-G"), any(), anyString()))
                .thenThrow(new LegacyTotalUnavailableException("down", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        transferService.transfer(new TransferRequest("t7", "cust-G", new BigDecimal("100.00"), "USD")))
                .isInstanceOf(LegacyTotalUnavailableException.class);

        assertThat(repository.findByTransferId("t7")).isEmpty();
    }
}
