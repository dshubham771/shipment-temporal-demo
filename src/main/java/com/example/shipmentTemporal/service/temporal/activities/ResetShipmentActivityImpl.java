package com.example.shipmentTemporal.service.temporal.activities;

import com.example.shipmentTemporal.clients.ShipmentClient;
import com.example.shipmentTemporal.models.ResetShipmentRequest;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = "shipment-activity-queue")
public class ResetShipmentActivityImpl implements ResetShipmentActivity {

    private final ShipmentClient apiClient;

    @Override
    public void resetToOrigin(Integer shipmentId) {
        log.info("Resetting shipment to origin with shipmentId: {}", shipmentId);

        try {
            ResetShipmentRequest request = ResetShipmentRequest.builder().shipmentId(shipmentId).reason("reset").build();
            apiClient.resetShipment(request);
        } catch (Exception e) {
            log.error("Failed to reset shipment with id {}",shipmentId, e);
            throw Activity.wrap(e);
        }
    }
}
