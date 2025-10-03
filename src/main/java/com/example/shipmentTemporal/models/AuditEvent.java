package com.example.shipmentTemporal.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {
    private AuditEventType eventType;
    private String message;
    private String from;
    private String to;
    private String reason;
    private Instant timestamp;

    public static AuditEvent created(String shipmentHandle) {
        return AuditEvent.builder()
                .eventType(AuditEventType.CREATED)
                .message(String.format("Shipment '%s' created", shipmentHandle))
                .timestamp(Instant.now())
                .build();
    }

    public static AuditEvent moved(String from, String to) {
        return AuditEvent.builder()
                .eventType(AuditEventType.MOVED)
                .message(String.format("Moved from %s to %s", from, to))
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build();
    }

    public static AuditEvent failed(String from, String to, String reason) {
        return AuditEvent.builder()
                .eventType(AuditEventType.FAILED)
                .message(String.format("Failed to move from %s to %s", from, to))
                .from(from)
                .to(to)
                .reason(reason)
                .timestamp(Instant.now())
                .build();
    }

    public static AuditEvent compensated(String from, String to, String reason) {
        return AuditEvent.builder()
                .eventType(AuditEventType.COMPENSATED)
                .message(String.format("Compensated: rolled back from %s to %s", from, to))
                .from(from)
                .to(to)
                .reason(reason)
                .timestamp(Instant.now())
                .build();
    }

    public static AuditEvent completed(String from, String to) {
        return AuditEvent.builder()
                .eventType(AuditEventType.COMPLETED)
                .message(String.format("Shipment completed from %s to %s", from, to))
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build();
    }
}