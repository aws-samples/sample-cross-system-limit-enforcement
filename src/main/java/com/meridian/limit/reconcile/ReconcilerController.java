package com.meridian.limit.reconcile;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal trigger for the reconciler. This is the "dumb trigger" endpoint an
 * EKS CronJob or EventBridge Scheduler can hit; all safety logic lives in
 * {@link Reconciler}. Draining loop pulls full batches until a partial one.
 */
@RestController
@RequestMapping("/internal/reconcile")
public class ReconcilerController {

    private final Reconciler reconciler;

    public ReconcilerController(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @PostMapping
    public Map<String, Integer> reconcile() {
        int total = 0;
        int processed;
        do {
            processed = reconciler.reconcileBatch();
            total += processed;
        } while (processed > 0);
        return Map.of("processed", total);
    }
}
