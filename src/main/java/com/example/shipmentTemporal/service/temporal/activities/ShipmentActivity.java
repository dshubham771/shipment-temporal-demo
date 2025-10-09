package com.example.shipmentTemporal.service.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface ShipmentActivity {

    @ActivityMethod
    List<String> getRoute();

    @ActivityMethod
    Integer createShipment(String handle);
    
    @ActivityMethod
    void moveShipment(Integer shipmentId, String from, String to);

}