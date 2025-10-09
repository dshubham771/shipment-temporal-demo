package com.example.shipmentTemporal.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResetShipmentResponse {
    private Boolean success;
    private ShipmentView shipment;
    private String error;
    private String note;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ShipmentView {
        
        private Long id;
        @JsonProperty("current_idx")
        private Integer currentIdx;
        @JsonProperty("current_location")
        private String currentLocation;
        private String status;
        private Instant updatedAt;
        private Instant createdAt;
    }
}