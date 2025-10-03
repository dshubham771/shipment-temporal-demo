# Shipment Temporal App

## Run with Docker
```
docker-compose up --build
```

Test APIs
1. Start a shipment
```
curl -X POST http://localhost:9090/api/shipments/start \
  -H "Content-Type: application/json" \
  -d '{"shipmentHandle":"test-shipment"}'
```

2. Get shipment result
```
curl http://localhost:9090/api/shipments/<workflowId>/result
```

3. Get audit trail
4. 
```
curl http://localhost:9090/api/shipments/<workflowId>/audit-trail
```