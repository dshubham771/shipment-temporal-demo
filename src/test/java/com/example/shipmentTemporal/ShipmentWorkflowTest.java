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
        // Create test environment
        testEnv = TestWorkflowEnvironment.newInstance();

        // Create worker for workflow queue
        workflowWorker = testEnv.newWorker("shipment-workflow-queue");
        workflowWorker.registerWorkflowImplementationTypes(ShipmentWorkflowImpl.class);

        // Create separate worker for activity queue (matching your ActivityOptions)
        activityWorker = testEnv.newWorker("shipment-activity-queue");

        // Create mock activity
        mockedActivity = mock(ShipmentActivity.class, withSettings().withoutAnnotations());

        // Get workflow client
        workflowClient = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    /**
     * Test Requirement 2: Verify that the shipment went along the specified route
     * This test verifies normal flow without failures
     */
    @Test
    void testShipmentFollowsSpecifiedRoute() {
        // Arrange
        List<String> expectedRoute = List.of("Mumbai", "Delhi", "Jaipur", "Bangalore");
        String shipmentHandle = "TEST-SHIPMENT-001";
        Integer shipmentId = 123;

        // Mock activity responses for successful flow
        when(mockedActivity.getRoute()).thenReturn(expectedRoute);
        when(mockedActivity.createShipment(shipmentHandle)).thenReturn(shipmentId);
        doNothing().when(mockedActivity).moveShipment(anyInt(), anyString(), anyString());

        // Register mocked activity to the activity worker
        activityWorker.registerActivitiesImplementations(mockedActivity);

        // Start test environment
        testEnv.start();

        // Act
        ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("shipment-workflow-queue")
                        .build()
        );

        String result = workflow.executeShipment(shipmentHandle);

        // Assert
        // Verify the workflow completed successfully
        assertTrue(result.contains("delivered successfully"));
        assertTrue(result.contains("Bangalore"));

        // Verify getRoute was called once
        verify(mockedActivity, times(1)).getRoute();

        // Verify createShipment was called once with correct handle
        verify(mockedActivity, times(1)).createShipment(shipmentHandle);

        // Verify moveShipment was called for each hop in the route (total hops = route.size() - 1)
        verify(mockedActivity, times(3)).moveShipment(eq(shipmentId), anyString(), anyString());

        // Verify exact sequence of moves along the route
        verify(mockedActivity).moveShipment(shipmentId, "Mumbai", "Delhi");
        verify(mockedActivity).moveShipment(shipmentId, "Delhi", "Jaipur");
        verify(mockedActivity).moveShipment(shipmentId, "Jaipur", "Bangalore");

        // Verify compensation was never called (no failures)
        verify(mockedActivity, never()).compensateMove(anyInt(), anyString(), anyString());

        // Verify audit trail contains expected events
        List<AuditEvent> auditTrail = workflow.getAuditTrail();
        assertNotNull(auditTrail);
        assertEquals(5, auditTrail.size()); // created + 3 moves + completed

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

    /**
     * Test Requirement 3: Verify that when 3 successive failures occur,
     * the workflow backtracks and re-routes through the previous routing node
     */
    @Test
    void testBacktrackAndRerouteAfterThreeSuccessiveFailures() {
        // Arrange
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

        // Mock moveShipment behavior
        doNothing().when(mockedActivity).moveShipment(shipmentId, "Mumbai", "Delhi");

        // Fail 3 times for Delhi -> Jaipur (first attempt), then succeed
        doThrow(new RuntimeException("Network error"))
                .doThrow(new RuntimeException("Network error"))
                .doThrow(new RuntimeException("Network error"))
                .doNothing() // Success after backtrack and retry
                .when(mockedActivity).moveShipment(shipmentId, "Delhi", "Jaipur");

        doNothing().when(mockedActivity).moveShipment(shipmentId, "Jaipur", "Bangalore");

        // Mock compensation (backtrack from Delhi to Mumbai)
        doNothing().when(mockedActivity).compensateMove(shipmentId, "Delhi", "Mumbai");

        // Register mocked activity to the activity worker
        activityWorker.registerActivitiesImplementations(mockedActivity);

        // Start test environment
        testEnv.start();

        // Act
        ShipmentWorkflow workflow = workflowClient.newWorkflowStub(
                ShipmentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("shipment-workflow-queue")
                        .build()
        );

        String result = workflow.executeShipment(shipmentHandle);

        // Assert
        // Verify the workflow eventually completed successfully
        assertTrue(result.contains("delivered successfully"));
        assertTrue(result.contains("Bangalore"));

        // Verify moveShipment for Mumbai -> Delhi was called twice (initial + after backtrack)
        verify(mockedActivity, times(2)).moveShipment(shipmentId, "Mumbai", "Delhi");

        // Verify moveShipment for Delhi -> Jaipur was called 4 times (3 failures + 1 success after backtrack)
        verify(mockedActivity, times(4)).moveShipment(shipmentId, "Delhi", "Jaipur");

        // Verify moveShipment for Jaipur -> Bangalore was called once
        verify(mockedActivity, times(1)).moveShipment(shipmentId, "Jaipur", "Bangalore");

        // CRITICAL ASSERTION: Verify compensation/backtrack was called after 3 failures
        verify(mockedActivity, times(1)).compensateMove(shipmentId, "Delhi", "Mumbai");

        // Verify audit trail shows the failure, compensation, and eventual success
        List<AuditEvent> auditTrail = workflow.getAuditTrail();
        assertNotNull(auditTrail);

        // Find and verify FAILED event
        boolean hasFailedEvent = auditTrail.stream()
                .anyMatch(event -> "FAILED".equals(event.getEventType().name())
                        && "Delhi".equals(event.getFrom())
                        && "Jaipur".equals(event.getTo()));
        assertTrue(hasFailedEvent, "Audit trail should contain FAILED event for Delhi -> Jaipur");

        // Find and verify COMPENSATED event (backtrack)
        boolean hasCompensatedEvent = auditTrail.stream()
                .anyMatch(event -> "COMPENSATED".equals(event.getEventType().name())
                        && "Delhi".equals(event.getFrom())
                        && "Mumbai".equals(event.getTo()));
        assertTrue(hasCompensatedEvent, "Audit trail should contain COMPENSATED event showing backtrack from Delhi to Mumbai");

        // Verify final completion
        boolean hasCompletedEvent = auditTrail.stream()
                .anyMatch(event -> "COMPLETED".equals(event.getEventType().name()));
        assertTrue(hasCompletedEvent, "Audit trail should contain COMPLETED event");
    }
}