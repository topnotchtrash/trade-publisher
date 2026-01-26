# TradePublisher

Spring Boot microservice that generates synthetic financial trades using the Annapurna library and publishes them to Kafka for downstream processing.

---

## Overview

TradePublisher is part of a distributed trade reconciliation system. It orchestrates the generation of realistic synthetic trade data across five financial instrument types and publishes them to Kafka topics for consumption by worker services.

**System Architecture:**
- Annapurna Library: Synthetic trade data generator
- TradePublisher: Orchestration service (this service)
- Kafka: Message queue buffer
- Sherpa: trade processing workers
- Sentinel: Python reconciliation engine
- Observatory: Prometheus and Grafana monitoring

---

## Features

- REST API for triggering trade generation jobs
- Asynchronous job processing with progress tracking
- Kafka producer with batch publishing and partitioning
- Redis-based job status tracking with TTL
- Multi-threaded trade generation and publishing
- Prometheus metrics for monitoring
- Docker containerization with multi-stage builds
- Health check endpoints

---

## Technology Stack

- Java 17
- Spring Boot 3.2.1
- Spring Kafka
- Spring Data Redis
- Annapurna Library (io.annapurna:annapurna:1.0.0)
- Micrometer Prometheus
- Docker and Docker Compose
- Maven

---

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Docker and Docker Compose
- 4GB RAM minimum

---

## Quick Start

### Local Development

1. Clone the repository
2. Start infrastructure services:
```bash
docker-compose up -d kafka redis postgres
```

3. Build the application:
```bash
mvn clean package -DskipTests
```

4. Run the application:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Docker Deployment

1. Build and start all services:
```bash
docker-compose up --build -d
```

2. Check service health:
```bash
curl http://localhost:8089/actuator/health
```

---

## API Endpoints

### Start Reconciliation Job

**POST** `/api/reconciliation/start`

Request Body:
```json
{
  "tradeCount": 1000,
  "tradeDistribution": {
    "INTEREST_RATE_SWAP": 30,
    "EQUITY_SWAP": 30,
    "FX_FORWARD": 20,
    "EQUITY_OPTION": 15,
    "CREDIT_DEFAULT_SWAP": 5
  },
  "profileDistribution": {
    "CLEAN": 70,
    "EDGE_CASE": 20,
    "STRESS": 10
  }
}
```

Response (202 Accepted):
```json
{
  "jobId": "rec-20260126-213517-dbb038c8",
  "status": "IN_PROGRESS",
  "startTime": "2026-01-26T21:35:17Z",
  "estimatedCompletion": "2026-01-26T21:35:47Z",
  "message": "Trade generation started"
}
```

### Get Job Status

**GET** `/api/reconciliation/status/{jobId}`

Response (200 OK):
```json
{
  "jobId": "rec-20260126-213517-dbb038c8",
  "status": "COMPLETED",
  "tradesGenerated": 1000,
  "tradesPublished": 1000,
  "failedPublishes": 0,
  "startTime": "2026-01-26T21:35:17Z",
  "endTime": "2026-01-26T21:35:18Z",
  "duration": "0m 1s",
  "throughput": "1000 trades/sec",
  "errorMessage": null
}
```

### Health Check

**GET** `/actuator/health`

---

## Configuration

### Application Properties

Key configuration properties in `application.yml`:

```yaml
server:
  port: 8089

spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

kafka:
  topic:
    trade-input: trade-recon-input

batch:
  size: 100

job:
  ttl-hours: 24
```

### Environment Variables

- `KAFKA_BOOTSTRAP_SERVERS`: Kafka broker addresses
- `REDIS_HOST`: Redis server host
- `REDIS_PORT`: Redis server port
- `SPRING_PROFILES_ACTIVE`: Active Spring profile (local, aws)

---

## Trade Data

### Supported Trade Types

1. Interest Rate Swap (IRS)
2. Equity Swap
3. FX Forward
4. Equity Option
5. Credit Default Swap (CDS)

### Data Quality Profiles

- **CLEAN (70%)**: Valid, complete data
- **EDGE_CASE (20%)**: Missing optional fields, boundary values
- **STRESS (10%)**: Invalid data for testing error handling

---

## Kafka Integration

### Topic Configuration

- **Topic Name**: `trade-recon-input`
- **Partitions**: 10
- **Replication Factor**: 1
- **Partition Key**: Trade type (for load balancing)
- **Message Format**: JSON

### Message Example

```json
{
  "tradeType": "EQUITY_SWAP",
  "tradeId": "ANNAPURNA-EQS-20250527-33147",
  "tradeDate": "2025-05-27",
  "settlementDate": "2025-05-28",
  "maturityDate": "2026-05-27",
  "notional": 15000000,
  "currency": "USD",
  "counterparty": "Goldman Sachs",
  "book": "EQUITY_DERIVATIVES_NY",
  "trader": "John Doe"
}
```

---

## Monitoring

### Prometheus Metrics

Available at `/actuator/prometheus`:

- `trade_generation_duration`: Time to generate trades
- `trades_generated_total`: Total trades generated
- `trades_published_total`: Total trades published to Kafka
- `trades_publish_failed_total`: Failed Kafka publishes
- `jobs_completed_total`: Completed jobs
- `jobs_failed_total`: Failed jobs
- `kafka_publish_latency`: Kafka publish latency

---

## Architecture

### Service Layer

- **ReconciliationController**: REST API endpoints
- **TradeGenerationService**: Orchestrates generation and publishing (async)
- **KafkaProducerService**: Publishes trades to Kafka in batches
- **JobTrackingService**: Manages job status in Redis

### Data Flow

```
Client Request
    → ReconciliationController
    → TradeGenerationService (async)
        → Annapurna Library (generates trades)
        → KafkaProducerService (publishes to Kafka)
        → JobTrackingService (updates Redis)
    → Return Job ID to Client
```

---

## Docker Services

### docker-compose.yml includes:

- **kafka**: Message broker (port 9092)
- **redis**: Job status store (port 6379)
- **postgres**: Database for downstream services (port 5432)
- **trade-publisher**: This service (port 8089)

---

## Testing

### Verify Kafka Messages

```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trade-recon-input \
  --from-beginning \
  --max-messages 5
```

### Check Redis Job Status

```bash
docker exec -it redis redis-cli
GET "job:rec-20260126-213517-dbb038c8"
```

### View Service Logs

```bash
docker-compose logs -f trade-publisher
```

---

## Performance

### Benchmarks

- **100K trades**: Generated in ~30 seconds (3,300 trades/sec)
- **Kafka publishing**: 200 batches/sec (100 trades per batch)
- **Total job time**: ~1 minute for 100K trades end-to-end

### Resource Usage

- **Memory**: 1GB heap
- **CPU**: 4 cores recommended for parallel processing
- **Disk**: Minimal (stateless service)

---

## Troubleshooting

### Service won't start

Check if ports are available:
```bash
netstat -ano | findstr :8089
netstat -ano | findstr :9092
netstat -ano | findstr :6379
```

### Kafka connection errors

Verify Kafka is running:
```bash
docker-compose ps kafka
docker-compose logs kafka
```

### Redis connection errors

Verify Redis is running:
```bash
docker exec -it redis redis-cli ping
```

Should respond with `PONG`.

### Build fails in Docker

Clear Docker cache and rebuild:
```bash
docker-compose down -v
docker system prune -af
docker-compose up --build -d
```

---

## Project Structure

```
trade-publisher/
├── src/
│   ├── main/
│   │   ├── java/com/traderecon/tradepublisher/
│   │   │   ├── TradePublisherApplication.java
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── model/
│   │   │   ├── config/
│   │   │   └── exception/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       └── application-aws.yml
│   └── test/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## Future Enhancements

- Unit and integration test suite
- Custom Grafana dashboards
- OAuth2 authentication
- Rate limiting
- Distributed tracing with Jaeger
- Additional trade type support
- WebSocket for real-time job progress updates

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

---

## License

This project is part of a portfolio demonstration system.

---

## Contact

For questions or issues, please open a GitHub issue.