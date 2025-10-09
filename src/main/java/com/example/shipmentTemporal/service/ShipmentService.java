package com.example.shipmentTemporal.service;


import com.example.shipmentTemporal.models.AuditEvent;
import com.example.shipmentTemporal.models.AuditTrailResponse;
import com.example.shipmentTemporal.models.ShipmentRequest;
import com.example.shipmentTemporal.models.ShipmentResponse;
import com.example.shipmentTemporal.service.temporal.workflows.ShipmentWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {
    
    private final WorkflowClient workflowClient;

    public ShipmentResponse startShipment(ShipmentRequest request) {
        log.info("Starting shipment workflow for request: {}", request);
        
        try {
            String workflowId = "shipment-" + request.getShipmentHandle();

            WorkflowOptions options = WorkflowOptions.newBuilder()
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setTaskQueue("shipment-workflow-queue")
                .setWorkflowExecutionTimeout(Duration.ofDays(1))
                .build();
            
            ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                options
            );
            
            WorkflowClient.start(
                () -> workflow.executeShipment(request.getShipmentHandle())
            );
            
            log.info("Workflow started successfully with ID: {}", workflowId);
            
            return ShipmentResponse.builder()
                .success(true).message("Shipment workflow started successfully")
                .workflowId(workflowId)
                .build();
            
        } catch (Exception e) {
            log.error("Failed to start shipment workflow", e);
            return ShipmentResponse.builder()
                .success(false)
                .message("Failed to start workflow: " + e.getMessage())
                .build();
        }
    }

    public ShipmentResponse getWorkflowResult(String workflowId) {
        log.info("Fetching workflow result for ID: {}", workflowId);

        try {
            ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                    ShipmentWorkflow.class,
                    workflowId
            );
            String result = WorkflowStub.fromTyped(workflow).getResult(String.class);

            return ShipmentResponse.builder()
                    .success(true).message(result)
                    .workflowId(workflowId)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get workflow result for workflow: {}", workflowId, e);
            return ShipmentResponse.builder()
                    .success(false).message("Failed to get workflow result: " + e.getMessage())
                    .workflowId(workflowId)
                    .build();
        }
    }

    public AuditTrailResponse getAuditTrail(String workflowId) {
        log.info("Fetching audit trail for workflow ID: {}", workflowId);
        
        try {
            ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                workflowId
            );
            
            List<AuditEvent> auditTrail = workflow.getAuditTrail();
            
            return AuditTrailResponse.builder()
                .success(true)
                .message("Audit trail fetched successfully")
                .workflowId(workflowId)
                .auditTrail(auditTrail)
                .build();
            
        } catch (Exception e) {
            log.error("Failed to get audit trail for workflow: {}", workflowId, e);
            return AuditTrailResponse.builder()
                .success(false)
                .message("Failed to get audit trail: " + e.getMessage())
                .workflowId(workflowId)
                .build();
        }
    }
}
