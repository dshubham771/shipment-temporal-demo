package com.example.shipmentTemporal.service.temporal.workflows;

import com.example.shipmentTemporal.models.AuditEvent;
import com.example.shipmentTemporal.service.temporal.activities.ShipmentActivity;
import com.example.shipmentTemporal.service.temporal.activities.ResetShipmentActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@WorkflowImpl(taskQueues = "shipment-workflow-queue")
@Slf4j
public class ShipmentWorkflowImpl implements ShipmentWorkflow {

    private final List<AuditEvent> auditTrail = new ArrayList<>();

    private final ActivityOptions activityOptions = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setTaskQueue("shipment-activity-queue")
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setMaximumInterval(Duration.ofSeconds(5))
                    .build())
            .build();

    private final ActivityOptions resetShipmentActivityOptions = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofDays(30))
            .setTaskQueue("shipment-activity-queue")
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(0)
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setMaximumInterval(Duration.ofSeconds(5))
                    .build())
            .build();

    private final ResetShipmentActivity resetShipmentActivity =
            Workflow.newActivityStub(ResetShipmentActivity.class, resetShipmentActivityOptions);

    private final ShipmentActivity activity =
            Workflow.newActivityStub(ShipmentActivity.class, activityOptions);



    @Override
    public String executeShipment(String shipmentHandle) {
        List<String> route = activity.getRoute();
        log.info("Starting shipment workflow for handle: {} with route: {}", shipmentHandle, route);
        Integer shipmentId = activity.createShipment(shipmentHandle);
        log.info("Shipment created with ID: {}", shipmentId);
        
        auditTrail.add(AuditEvent.created(shipmentHandle));

        int currentIndex = 0;
        int retryCycle = 0;

        Saga.Options sagaOptions = new Saga.Options.Builder().build();
        Saga saga;
        while (currentIndex < route.size() - 1) {
            saga = new Saga(sagaOptions);
            String fromCity = route.get(currentIndex);
            String toCity = route.get(currentIndex + 1);

            log.info("Moving from {} (idx {}) to {} (idx {}) - Retry cycle: {}",
                    fromCity, currentIndex, toCity, currentIndex + 1, retryCycle);

            try {
                registerCompensation(route, currentIndex, saga, shipmentId);
                activity.moveShipment(shipmentId, fromCity, toCity);
                auditTrail.add(AuditEvent.moved(fromCity, toCity));
                if (currentIndex + 1 == route.size() - 1) {
                    auditTrail.add(AuditEvent.completed(route.get(0), toCity));
                }

                log.info("Successfully moved to {}. Current index: {}", toCity, currentIndex + 1);
                currentIndex++;
                retryCycle = 0;
            } catch (ActivityFailure e) {
                if (e.getRetryState() == RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED) {
                    log.error("Failed to move from {} to {} after all retry attempts. Starting compensation...",
                            fromCity, toCity, e);

                    auditTrail.add(AuditEvent.failed(fromCity, toCity, e.getMessage()));

                    if (currentIndex == 0) {
                        log.error("Failed at origin (first hop). Cannot rollback further.");
                        retryCycle++;
                        long backoffSeconds = Math.min(60, (long) Math.pow(2, retryCycle));
                        Workflow.sleep(Duration.ofSeconds(backoffSeconds));
                        continue;
                    }
                    try {
                        saga.compensate();
                    } catch (Exception compensationException) {
                        currentIndex = 0;
                        resetShipmentActivity.resetToOrigin(shipmentId);
                        continue;
                    }

                    currentIndex--;
                    retryCycle++;

                    long backoffSeconds = Math.min(60, (long) Math.pow(2, retryCycle));
                    log.info("Retry cycle {}: Waiting {} seconds before resuming from index {}",
                            retryCycle, backoffSeconds, currentIndex);
                    Workflow.sleep(Duration.ofSeconds(backoffSeconds));

                    log.info("Resuming from {} (idx {}) after compensation", route.get(currentIndex), currentIndex);
                }
            }
        }

        String finalLocation = route.get(route.size() - 1);
        log.info("Shipment successfully delivered to final destination: {}", finalLocation);
        return String.format("Shipment %s delivered successfully to %s", shipmentHandle, finalLocation);
    }

    private void registerCompensation(List<String> route, int currentIndex, Saga saga, Integer shipmentId) {
        if (currentIndex == 0) {
            return;
        }
        String from = route.get(currentIndex);
        String to = route.get(currentIndex - 1);
        saga.addCompensation(
                () -> {
                    activity.compensateMove(shipmentId, from, to);
                    auditTrail.add(AuditEvent.compensated(from, to, "Compensated last move"));
                }
        );
    }
    
    @Override
    public List<AuditEvent> getAuditTrail() {
        return auditTrail;
    }
}