package com.meridian.limit.transfer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;
    private final PrincipalCustomerResolver principalCustomerResolver;

    public TransferController(TransferService transferService,
                             PrincipalCustomerResolver principalCustomerResolver) {
        this.transferService = transferService;
        this.principalCustomerResolver = principalCustomerResolver;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(value = "X-Auth-Sub", required = false) String principalSub,
            @Valid @RequestBody TransferRequest request) {
        // SD-1: enforce per-customer authorization before doing any work. The
        // X-Auth-Sub header carries the authenticated Cognito `sub` forwarded by
        // the API Gateway authorizer (clients cannot set it). Reject with 403 if
        // the principal is missing/unmapped or is not bound to the request's
        // customerId.
        String authorizedCustomerId = principalCustomerResolver.resolveCustomerId(principalSub);
        if (authorizedCustomerId == null || !authorizedCustomerId.equals(request.customerId())) {
            throw new ForbiddenException(
                    "Authenticated principal is not authorized to transact for the requested customer");
        }

        TransferResponse response = transferService.transfer(request);
        HttpStatus status = switch (response.outcome()) {
            case COMMITTED -> HttpStatus.OK;
            case PENDING_RECONCILIATION -> HttpStatus.ACCEPTED;
            case LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REJECTED_BY_CORE -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(response);
    }
}
