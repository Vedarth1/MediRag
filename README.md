# MediRAG — AI-Powered Healthcare Platform

A production-ready microservices backend built with Java 21, Spring Boot 3.3, and Docker.

## Architecture

| Service              | Port | Responsibility                          |
|----------------------|------|-----------------------------------------|
| API Gateway          | 8080 | JWT validation, rate limiting, routing  |
| Auth Service         | 8081 | Registration, login, JWT issuance       |
| Appointment Service  | 8082 | Bookings, doctor slots, email           |
| Health & Wellness    | 8083 | Profiles, sleep logs, AI meal plans     |
| Mental Health        | 8084 | AI chat, relaxation resources           |
| AI Diagnostic        | 8085 | X-ray upload, Vision AI, MinIO          |

## Infrastructure

| Component  | Port      | Purpose                          |
|------------|-----------|----------------------------------|
| PostgreSQL | 5432      | Primary DB — 5 schemas           |
| Redis      | 6379      | Cache, JWT sessions, rate limits |
| MinIO      | 9000/9001 | Object storage for X-ray files   |

## Prerequisites

- Docker Desktop
- Java 21 (for local development)
- Maven 3.9+

## Quick Start

1. Clone the repo
2. Create a `.env` file in the root:
3. Add the required entries
4. Start everything:

```bash
docker compose up --build
```

## API Documentation (Swagger UI)

Once running, visit:

- Auth Service: http://localhost:8081/swagger-ui.html
- Appointment Service: http://localhost:8082/swagger-ui.html
- Health Service: http://localhost:8083/swagger-ui.html
- Mental Health Service: http://localhost:8084/swagger-ui.html
- Diagnostic Service: http://localhost:8085/swagger-ui.html
- MinIO Console: http://localhost:9001 (minioadmin / minioadmin123)

## Technology Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.3.0
- **Gateway**: Spring Cloud Gateway 2023.0.2 (Netty/reactive)
- **Database**: PostgreSQL 16 (schema-per-service)
- **Cache**: Redis 7
- **Storage**: MinIO (S3-compatible)
- **AI**: OpenAI GPT-4o (vision), GPT-4o-mini (chat and meals)
- **Security**: JWT (jjwt 0.12.5), BCrypt
- **Build**: Maven 3.9, Docker Compose
- **Docs**: SpringDoc OpenAPI (Swagger UI)

## Architecture

![My Image](https://github.com/Vedarth1/MediRag/blob/main/Resources/MediRAG.png)

## Project Report

https://docs.google.com/document/d/1ulWP9ggSW_vwUgjTTFjhJCC-oYw1BQrm0BAJSodKd2Q
