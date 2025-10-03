package com.example.shipmentTemporal.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteResponse {
    private int count;
    private List<WaypointOrder> order;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaypointOrder {
        private int idx;
        private String city;
        private String handle;
        private int id;
    }
}