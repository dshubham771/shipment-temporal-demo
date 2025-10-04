package com.example.shipmentTemporal;

import com.example.shipmentTemporal.models.AuditEvent;
import com.example.shipmentTemporal.service.temporal.activities.ShipmentActivity;
import com.example.shipmentTemporal.service.temporal.workflows.ShipmentWorkflow;
import com.example.shipmentTemporal.service.temporal.workflows.ShipmentWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShipmentWorkflowTest {

    private TestWorkflowEnvironment testEnv;
    private Worker workflowWorker;
    private Worker activityWorker;
    private WorkflowClient workflowClient;
    private ShipmentActivity mockedActivity;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();

        workflowWorker = testEnv.newWorker("shipment-workflow-queue");
        workflowWorker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);

        activityWorker = testEnv.newWorker("shipment-activity-queue");

        mockedActivity = mock(ShipmentActivity.class, withSettings().withoutAnnotations());

        workflowClient = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void testShipmentFollowsSpecifiedRoute() {
        List<String> expectedRoute = List.of("Mumbai", "Delhi", "Jaipur", "Bangalore");
        String shipmentHandle = "TEST-SHIPMENT-001";
        Integer shipmentId = 123;

        when(mockedActivity.getRoute()).thenReturn(expectedRoute);
        when(mockedActivity.createShipment(shipmentHandle)).thenReturn(shipmentId);
        doNothing().when(mockedActivity).moveShipment(anyInt(), anyString(), anyString());

        activityWorker.registerActivitiesImplementations(mockedActivity);
        testEnv.start();

        ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("shipment-workflow-queue")
                        .build()
        );

        String result = workflow.executeShipment(shipmentHandle);

        assertTrue(result.contains("delivered successfully"));
        assertTrue(result.contains("Bangalore"));

        verify(mockedActivity, times(1)).getRoute();
        verify(mockedActivity, times(1)).createShipment(shipmentHandle);
        verify(mockedActivity, times(3)).moveShipment(eq(shipmentId), anyString(), anyString());

        verify(mockedActivity).moveShipment(shipmentId, "Mumbai", "Delhi");
        verify(mockedActivity).moveShipment(shipmentId, "Delhi", "Jaipur");
        verify(mockedActivity).moveShipment(shipmentId, "Jaipur", "Bangalore");

        verify(mockedActivity, never()).compensateMove(anyInt(), anyString(), anyString());

        List<AuditEvent> auditTrail = workflow.getAuditTrail();
        assertNotNull(auditTrail);
        assertEquals(5, auditTrail.size());

        assertEquals("CREATED", auditTrail.get(0).getEventType().name());
        assertEquals("MOVED", auditTrail.get(1).getEventType().name());
        assertEquals("Mumbai", auditTrail.get(1).getFrom());
        assertEquals("Delhi", auditTrail.get(1).getTo());
        assertEquals("MOVED", auditTrail.get(2).getEventType().name());
        assertEquals("Delhi", auditTrail.get(2).getFrom());
        assertEquals("Jaipur", auditTrail.get(2).getTo());
        assertEquals("MOVED", auditTrail.get(3).getEventType().name());
        assertEquals("Jaipur", auditTrail.get(3).getFrom());
        assertEquals("Bangalore", auditTrail.get(3).getTo());
        assertEquals("COMPLETED", auditTrail.get(4).getEventType().name());
    }

    @Test
    void testBacktrackAndRerouteAfterThreeSuccessiveFailures() {
        List<String> route = List.of("Mumbai", "Delhi", "Jaipur", "Bangalore");
        String shipmentHandle = "TEST-SHIPMENT-002";
        Integer shipmentId = 456;

        when(mockedActivity.getRoute()).thenReturn(route);
        when(mockedActivity.createShipment(shipmentHandle)).thenReturn(shipmentId);

        // Scenario:
        // 1. Mumbai -> Delhi: Success
        // 2. Delhi -> Jaipur: Fail 3 times (triggers backtrack)
        // 3. Compensation: Delhi -> Mumbai (backtrack)
        // 4. Mumbai -> Delhi: Success (retry after backtrack)
        // 5. Delhi -> Jaipur: Success (after backtrack)
        // 6. Jaipur -> Bangalore: Success

        doNothing().when(mockedActivity).moveShipment(shipmentId, "Mumbai", "Delhi");

        doThrow(new RuntimeException("Network error"))
                .doThrow(new RuntimeException("Network error"))
                .doThrow(new RuntimeException("Network error"))
                .doNothing()
                .when(mockedActivity).moveShipment(shipmentId, "Delhi", "Jaipur");

        doNothing().when(mockedActivity).moveShipment(shipmentId, "Jaipur", "Bangalore");

        doNothing().when(mockedActivity).compensateMove(shipmentId, "Delhi", "Mumbai");

        activityWorker.registerActivitiesImplementations(mockedActivity);
        testEnv.start();

        ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("shipment-workflow-queue")
                        .build()
        );

        String result = workflow.executeShipment(shipmentHandle);

        assertTrue(result.contains("delivered successfully"));
        assertTrue(result.contains("Bangalore"));

        verify(mockedActivity, times(2)).moveShipment(shipmentId, "Mumbai", "Delhi");
        verify(mockedActivity, times(4)).moveShipment(shipmentId, "Delhi", "Jaipur");
        verify(mockedActivity, times(1)).moveShipment(shipmentId, "Jaipur", "Bangalore");

        verify(mockedActivity, times(1)).compensateMove(shipmentId, "Delhi", "Mumbai");

        List<AuditEvent> auditTrail = workflow.getAuditTrail();
        assertNotNull(auditTrail);

        boolean hasFailedEvent = auditTrail.stream()
                .anyMatch(event -> "FAILED".equals(event.getEventType().name())
                        && "Delhi".equals(event.getFrom())
                        && "Jaipur".equals(event.getTo()));
        assertTrue(hasFailedEvent);

        boolean hasCompensatedEvent = auditTrail.stream()
                .anyMatch(event -> "COMPENSATED".equals(event.getEventType().name())
                        && "Delhi".equals(event.getFrom())
                        && "Mumbai".equals(event.getTo()));
        assertTrue(hasCompensatedEvent);

        boolean hasCompletedEvent = auditTrail.stream()
                .anyMatch(event -> "COMPLETED".equals(event.getEventType().name()));
        assertTrue(hasCompletedEvent);
    }
}
