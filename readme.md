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
```
curl http://localhost:9090/api/shipments/<workflowId>/audit-trail
```
Run Tests in Docker

Since the runtime container does not include Maven, tests should be run in a Maven container.

1. Using a temporary Maven container (with caching)

Create a Docker volume for Maven cache:
```
docker volume create maven-repo
```

Run tests:

```
docker run --rm \
  -v $(pwd):/app \
  -v maven-repo:/root/.m2/repository \
  -w /app \
  maven:3.9.3-eclipse-temurin-17 \
  mvn clean test
```


Using host Maven cache

If you already have Maven installed locally, you can mount your host Maven repository:

```
docker run --rm \
  -v $(pwd):/app \
  -v ~/.m2:/root/.m2 \
  -w /app \
  maven:3.9.3-eclipse-temurin-17 \
  mvn clean test

```