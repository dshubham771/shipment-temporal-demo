package com.example.shipmentTemporal.clients;

import com.example.shipmentTemporal.models.*;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class ShipmentClient {

    @Autowired
    private RestTemplate restTemplate;
    @Value("${roulette-server.baseUrl}")
    private String baseUrl;

    public Integer createShipment(CreateShipmentRequest request) {
        
        try {
            HttpHeaders headers = getBasicHttpHeaders();
            HttpEntity<CreateShipmentRequest> entity = new HttpEntity<>(request, headers);
            
            ResponseEntity<CreateShipmentResponse> responseEntity = restTemplate.exchange(baseUrl + "/shipments",
                    HttpMethod.POST, entity, CreateShipmentResponse.class);
            CreateShipmentResponse response = responseEntity.getBody();
            
            if (response != null && response.isSuccess() && response.getShipment() != null) {
                log.info("Shipment created successfully with ID: {}", response.getShipment().getId());
                return response.getShipment().getId();
            }
            
            throw new RuntimeException("Failed to create shipment: " +
                (response != null ? response.getError() : "Unknown error"));
                
        } catch (HttpClientErrorException.Conflict e) {
            log.error("Shipment already exists");
            throw new RuntimeException("Failed to create shipment: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error creating shipment", e);
            throw new RuntimeException("Failed to create shipment: " + e.getMessage(), e);
        }
    }

    public void moveShipment(MoveRequest request) {
        log.info("Moving shipment {} from {} to {}", request.getShipmentId(), request.getFrom(), request.getTo());
        
        try {
            HttpHeaders headers = getBasicHttpHeaders();
            HttpEntity<MoveRequest> entity = new HttpEntity<>(request, headers);
            
            ResponseEntity<CreateShipmentResponse> responseEntity = restTemplate.exchange(
                baseUrl + "/move", HttpMethod.POST, entity, CreateShipmentResponse.class);

            CreateShipmentResponse response = responseEntity.getBody();
            
            if (response == null || !response.isSuccess()) {
                throw new RuntimeException("Move failed: " +
                    (response != null ? response.getError() : "No response"));
            }
            
            log.info("Move successful from {} to {}", request.getFrom(), request.getTo());
        } catch (Exception e) {
            log.error("Error moving shipment", e);
            throw new RuntimeException("Failed to move shipment: " + e.getMessage(), e);
        }
    }

    public List<String> getRoute() {
        log.info("Fetching route from external API");
        
        try {
            String url = baseUrl + "/route";
            ResponseEntity<RouteResponse> responseEntity = restTemplate.getForEntity(url, RouteResponse.class);
            RouteResponse response = responseEntity.getBody();
            
            if (response != null && response.getOrder() != null) {
                List<String> route = response.getOrder().stream()
                    .map(RouteResponse.WaypointOrder::getCity)
                    .collect(Collectors.toList());
                log.info("Fetched route with {} waypoints: {}", route.size(), route);
                return route;
            }
            
            throw new RuntimeException("Failed to fetch route: empty response");
        } catch (Exception e) {
            log.error("Error fetching route from external API", e);
            throw new RuntimeException("Failed to fetch route: " + e.getMessage(), e);
        }
    }

    private static HttpHeaders getBasicHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
