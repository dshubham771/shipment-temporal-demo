package com.example.shipmentTemporal.service.temporal.activities;

import com.example.shipmentTemporal.clients.ShipmentClient;
import com.example.shipmentTemporal.models.MoveRequest;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = "shipment-activity-queue")
public class ShipmentCompensateActivityImpl implements ShipmentCompensationActivity {

    private final ShipmentClient apiClient;
    @Override
    public void compensateMove(Integer shipmentId, String from, String to) {
        log.info("Compensating move for shipment {} from {}", shipmentId, from);
        try {
            MoveRequest moveRequest = MoveRequest.builder().shipmentId(shipmentId).from(from).to(to).build();
            apiClient.moveShipment(moveRequest);
            log.info("Successfully compensated shipment from {} to {}", from, to);
        } catch (Exception e) {
            log.error("Failed to compensated shipment from {} to {}", from, to, e);
            throw Activity.wrap(e);
        }
    }


}
