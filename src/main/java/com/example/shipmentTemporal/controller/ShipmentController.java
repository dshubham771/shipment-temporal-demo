package com.example.shipmentTemporal.controller;

import com.example.shipmentTemporal.models.AuditTrailResponse;
import com.example.shipmentTemporal.models.ShipmentRequest;
import com.example.shipmentTemporal.models.ShipmentResponse;
import com.example.shipmentTemporal.service.ShipmentService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentController {
    
    private final ShipmentService shipmentService;

    @PostMapping("/start")
    public ResponseEntity<ShipmentResponse> startShipment(@RequestBody ShipmentRequest request) {
        log.info("Received shipment request: {}", request);
        if (StringUtils.isBlank(request.getShipmentHandle())) {
            return ResponseEntity.badRequest()
                .body(ShipmentResponse.builder()
                    .success(false)
                    .message("Shipment handle is required")
                    .build());
        }
        
        return ResponseEntity.ok(shipmentService.startShipment(request));
    }

    @GetMapping("/{workflowId}/result")
    public ResponseEntity<ShipmentResponse> getWorkflowResult(@PathVariable String workflowId) {
        log.info("Fetching result for workflow: {}", workflowId);
        ShipmentResponse response = shipmentService.getWorkflowResult(workflowId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{workflowId}/audit-trail")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(@PathVariable String workflowId) {
        log.info("Fetching audit trail for workflow: {}", workflowId);
        return ResponseEntity.ok(shipmentService.getAuditTrail(workflowId));
    }
}
