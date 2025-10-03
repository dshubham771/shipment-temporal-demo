package com.example.shipmentTemporal.models;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentResponse {
    private boolean success;
    private String message;
    private String workflowId;
    private String runId;
}