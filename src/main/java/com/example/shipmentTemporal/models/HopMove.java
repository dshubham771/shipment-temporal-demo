package com.example.shipmentTemporal.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HopMove {
    private String from;
    private String to;
    private int fromIdx;
    private int toIdx;
}
