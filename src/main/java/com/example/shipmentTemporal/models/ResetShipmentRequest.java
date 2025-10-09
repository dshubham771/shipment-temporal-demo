package com.example.shipmentTemporal.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetShipmentRequest {
    private String reason;
    private int shipmentId;
}
