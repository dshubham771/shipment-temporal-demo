package com.example.shipmentTemporal;

import com.example.shipmentTemporal.controller.ShipmentController;
import com.example.shipmentTemporal.models.AuditTrailResponse;
import com.example.shipmentTemporal.models.ShipmentRequest;
import com.example.shipmentTemporal.models.ShipmentResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
class ShipmentTest {

    @Autowired
    private ShipmentController shipmentController;

    @Test
    void testShipmentFlow() {
        ShipmentRequest request = new ShipmentRequest();
        request.setShipmentHandle("test-shipment-" + System.currentTimeMillis());

        ResponseEntity<ShipmentResponse> startResponse = shipmentController.startShipment(request);

        assertThat(startResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(startResponse.getBody()).isNotNull();
        assertThat(startResponse.getBody().isSuccess()).isTrue();

        String workflowId = startResponse.getBody().getWorkflowId();
        assertThat(workflowId).isNotEmpty();

        ResponseEntity<ShipmentResponse> resultEntity = shipmentController.getWorkflowResult(workflowId);
        ShipmentResponse resultResponse = resultEntity.getBody();

        assertThat(resultResponse).isNotNull();
        assertThat(resultResponse.isSuccess()).isTrue();

        ResponseEntity<AuditTrailResponse> auditResponse = shipmentController.getAuditTrail(workflowId);

        assertThat(auditResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(auditResponse.getBody()).isNotNull();
        assertThat(auditResponse.getBody().getAuditTrail()).isNotEmpty();

        log.info("                                ==== Audit trail ==== \n\n");
        auditResponse.getBody().getAuditTrail().forEach(auditEvent -> log.info(auditEvent.getMessage()));
    }
}
