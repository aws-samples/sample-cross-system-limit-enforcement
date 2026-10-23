package com.meridian.limit.legacy.stub;

import com.meridian.limit.legacy.CoreExecutionResult;
import com.meridian.limit.legacy.CoreSettlementStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP control surface for {@link StubLegacyCore}, so a test script can arrange
 * scenarios (seed legacy totals, choose the next execution outcome, force the
 * total API down, set settlement status). Active only under {@code stub-legacy}.
 */
@RestController
@RequestMapping("/stub/legacy")
@Profile("stub-legacy")
public class StubLegacyCoreController {

    private final StubLegacyCore stub;

    public StubLegacyCoreController(StubLegacyCore stub) {
        this.stub = stub;
    }

    @PostMapping("/total")
    public Map<String, String> setTotal(@RequestBody Map<String, String> body) {
        stub.setLegacyTotal(body.get("customerId"), new BigDecimal(body.get("total")));
        return Map.of("ok", "true");
    }

    @PostMapping("/next-result")
    public Map<String, String> setNextResult(@RequestBody Map<String, String> body) {
        stub.setNextResult(CoreExecutionResult.valueOf(body.get("result")));
        return Map.of("ok", "true");
    }

    @PostMapping("/total-unavailable")
    public Map<String, String> setTotalUnavailable(@RequestBody Map<String, String> body) {
        stub.setTotalUnavailable(Boolean.parseBoolean(body.get("unavailable")));
        return Map.of("ok", "true");
    }

    @PostMapping("/settlement")
    public Map<String, String> setSettlement(@RequestBody Map<String, String> body) {
        stub.setSettlement(body.get("transferId"), CoreSettlementStatus.valueOf(body.get("status")));
        return Map.of("ok", "true");
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        stub.reset();
        return Map.of("ok", "true");
    }
}
