package com.example.shipmentTemporal.service.temporal.workflows;

import com.example.shipmentTemporal.models.AuditEvent;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;

@WorkflowInterface
public interface ShipmentWorkflow {
    
    @WorkflowMethod
    String executeShipment(String shipmentHandle);
    
    @QueryMethod
    List<AuditEvent> getAuditTrail();
}
