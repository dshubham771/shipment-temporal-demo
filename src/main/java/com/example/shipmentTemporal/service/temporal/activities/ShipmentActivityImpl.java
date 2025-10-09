package com.example.shipmentTemporal.service.temporal.activities;

import com.example.shipmentTemporal.clients.ShipmentClient;
import com.example.shipmentTemporal.models.CreateShipmentRequest;
import com.example.shipmentTemporal.models.MoveRequest;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = "shipment-activity-queue")
public class ShipmentActivityImpl implements ShipmentActivity {

    private final ShipmentClient apiClient;

    @Override
    public List<String> getRoute() {
        return apiClient.getRoute();
    }

    @Override
    public Integer createShipment(String handle) {
        log.info("Creating shipment with handle: {}", handle);

        try {
            CreateShipmentRequest request = CreateShipmentRequest.builder().handle(handle)
                    .name("Shipment-" + handle).build();
            return apiClient.createShipment(request);
        } catch (Exception e) {
            log.error("Failed to create shipment", e);
            throw Activity.wrap(e);
        }
    }

    @Override
    public void moveShipment(Integer shipmentId, String from, String to) {
        try {
            MoveRequest moveRequest = MoveRequest.builder().shipmentId(shipmentId).from(from).to(to).build();
            apiClient.moveShipment(moveRequest);
            log.info("Successfully moved shipment from {} to {}", from, to);
        } catch (Exception e) {
            log.error("Failed to move shipment from {} to {}", from, to, e);
            throw Activity.wrap(e);
        }
    }

}
