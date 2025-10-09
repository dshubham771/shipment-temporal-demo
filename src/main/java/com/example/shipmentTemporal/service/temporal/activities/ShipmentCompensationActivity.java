package com.example.shipmentTemporal.service.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ShipmentCompensationActivity {

    @ActivityMethod
    void compensateMove(Integer shipmentId, String from, String to);
}