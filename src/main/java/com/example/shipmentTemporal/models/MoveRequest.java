package com.example.shipmentTemporal.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoveRequest {
    @JsonProperty("shipment_id")
    private Integer shipmentId;
    private String from;
    private String to;
}
