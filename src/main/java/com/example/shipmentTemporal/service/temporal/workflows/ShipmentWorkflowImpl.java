package com.example.shipmentTemporal.service.temporal.workflows;

import com.example.shipmentTemporal.models.AuditEvent;
import com.example.shipmentTemporal.models.HopMove;
import com.example.shipmentTemporal.service.temporal.activities.ShipmentActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
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

    private final ShipmentActivity activity =
            Workflow.newActivityStub(ShipmentActivity.class, activityOptions);

    @Override
    public String executeShipment(String shipmentHandle) {
        List<String> route = activity.getRoute();
        log.info("Starting shipment workflow for handle: {} with route: {}", shipmentHandle, route);
        Integer shipmentId = activity.createShipment(shipmentHandle);
        log.info("Shipment created with ID: {}", shipmentId);
        
        auditTrail.add(AuditEvent.created(shipmentHandle));

        List<HopMove> completedMoves = new ArrayList<>();
        int currentIndex = 0;
        int retryCycle = 0;

        while (currentIndex < route.size() - 1) {
            String fromCity = route.get(currentIndex);
            String toCity = route.get(currentIndex + 1);

            log.info("Moving from {} (idx {}) to {} (idx {}) - Retry cycle: {}",
                    fromCity, currentIndex, toCity, currentIndex + 1, retryCycle);

            try {
                activity.moveShipment(shipmentId, fromCity, toCity);
                HopMove hopMove = HopMove.builder()
                        .from(fromCity).to(toCity).fromIdx(currentIndex).toIdx(currentIndex+1)
                        .build();
                completedMoves.add(hopMove);
                currentIndex++;
                retryCycle = 0;

                auditTrail.add(AuditEvent.moved(fromCity, toCity));
                if(currentIndex == route.size()-1) {
                    auditTrail.add(AuditEvent.completed(route.get(0), toCity));
                }

                log.info("Successfully moved to {}. Current index: {}", toCity, currentIndex);
            } catch (ActivityFailure e) {
                if (e.getRetryState() == RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED) {
                    log.error("Failed to move from {} to {} after all retry attempts. Starting partial compensation...",
                            fromCity, toCity, e);

                    auditTrail.add(AuditEvent.failed(fromCity, toCity, e.getMessage()));

                    if (currentIndex == 0) {
                        log.error("Failed at origin (first hop). Cannot rollback further.");
//                    throw ApplicationFailure.newNonRetryableFailure(
//                            "Shipment delivery failed at origin (" + fromCity + " -> " + toCity + "): ",
//                            e.getMessage()
//                    );
                        retryCycle++;
                        long backoffSeconds = Math.min(60, (long) Math.pow(2, retryCycle));
                        Workflow.sleep(Duration.ofSeconds(backoffSeconds));
                        continue;
                    }

                    HopMove lastMove = completedMoves.get(completedMoves.size() - 1);
                    compensateLastMove(shipmentId, lastMove);
                    completedMoves.remove(completedMoves.size() - 1);
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

    private void compensateLastMove(Integer shipmentId, HopMove lastMove) {
        log.info("Compensating last move: {} -> {}", lastMove.getFrom(), lastMove.getTo());
        int retryCycle = 0;
        while (true) {
            try {
                String reason = String.format(
                        "Rolling back from %s to %s to compensate forward move", lastMove.getTo(), lastMove.getFrom());

                activity.compensateMove(shipmentId, lastMove.getTo(), lastMove.getFrom());
                auditTrail.add(AuditEvent.compensated(lastMove.getTo(), lastMove.getFrom(), reason));
                break;
            } catch (Exception e) {
                long backoffSeconds = (long) Math.min(60, Math.pow(2, ++retryCycle));
                log.error("Compensation attempt {} failed for move {} -> {}. Retrying in {} seconds. Error: {}",
                        retryCycle, lastMove.getFrom(), lastMove.getTo(), backoffSeconds, e.getMessage());
                Workflow.sleep(Duration.ofSeconds(backoffSeconds));
            }
        }
    }
    
    @Override
    public List<AuditEvent> getAuditTrail() {
        return auditTrail;
    }
}
