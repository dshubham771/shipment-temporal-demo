package com.example.shipmentTemporal.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateShipmentResponse {
    private boolean success;
    private String error;
    private ShipmentData shipment;

    @Data
    @NoArgsConstructor
    public static class ShipmentData {
        private Integer id;
        private String handle;
        private String name;
        private String status;

        @JsonProperty("current_idx")
        private Integer currentIdx;
    }
}
