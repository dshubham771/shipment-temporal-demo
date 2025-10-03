package com.example.shipmentTemporal.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTrailResponse {
    private boolean success;
    private String message;
    private String workflowId;
    private List<AuditEvent> auditTrail;
}