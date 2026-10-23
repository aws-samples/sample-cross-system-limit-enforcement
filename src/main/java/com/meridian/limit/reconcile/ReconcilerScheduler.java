package com.meridian.limit.reconcile;

import com.meridian.limit.config.LimitEnforcementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-cluster trigger for the reconciler. This mirrors the "dumb trigger, smart
 * service" split from the design: all the safety logic (idempotency, claiming
 * with {@code SKIP LOCKED}) lives in {@link Reconciler}, so the trigger can be
 * simple and retries are always safe.
 *
 * <p>Enabled via {@code limit-enforcement.reconciler.enabled}. In production
 * this can be replaced by an EKS CronJob or EventBridge Scheduler hitting an
 * internal endpoint, keeping the trigger contract identical.
 */
@Component
@ConditionalOnProperty(prefix = "limit-enforcement.reconciler", name = "enabled", havingValue = "true")
public class ReconcilerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerScheduler.class);

    private final Reconciler reconciler;
    private final LimitEnforcementProperties props;

    public ReconcilerScheduler(Reconciler reconciler, LimitEnforcementProperties props) {
        this.reconciler = reconciler;
        this.props = props;
    }

    @Scheduled(cron = "${limit-enforcement.reconciler.cron}")
    public void run() {
        int batchSize = props.getReconciler().getBatchSize();
        int processed;
        // Drain: keep pulling full batches until a partial batch is returned.
        do {
            processed = reconciler.reconcileBatch();
        } while (processed >= batchSize);
    }
}
