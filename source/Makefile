.PHONY: help up down logs build run test bench seed clean

# Default target
help:
	@echo "Fraud Detection Gateway - Available commands:"
	@echo ""
	@echo "  make up              - Start infrastructure (postgres, redis)"
	@echo "  make up-kafka        - Start with Kafka (for Variant C)"
	@echo "  make up-rabbitmq     - Start with RabbitMQ (for Variant D)"
	@echo "  make up-all          - Start all services including observability"
	@echo "  make down            - Stop all services"
	@echo "  make logs            - View logs"
	@echo ""
	@echo "  make build           - Build the application"
	@echo "  make run             - Run the application"
	@echo "  make test            - Run tests"
	@echo ""
	@echo "  make seed            - Seed demo data"
	@echo "  make bench:uniform   - Run uniform benchmark"
	@echo "  make bench:hot       - Run hot merchant benchmark"
	@echo "  make bench:slowdown  - Run model slowdown benchmark"
	@echo "  make bench:fault     - Run fault injection benchmark"
	@echo ""
	@echo "  make clean           - Clean build artifacts"

# Infrastructure
up:
	docker compose up -d postgres redis

up-kafka:
	docker compose --profile kafka up -d

up-rabbitmq:
	docker compose --profile rabbitmq up -d

up-observability:
	docker compose --profile observability up -d

up-all:
	docker compose --profile kafka --profile rabbitmq --profile observability up -d

down:
	docker compose --profile kafka --profile rabbitmq --profile observability down

logs:
	docker compose logs -f

# Build & Run
build:
	./gradlew build -x test

run:
	./gradlew :api-service:bootRun

test:
	./gradlew test

# Seed data
seed:
	@echo "Seeding demo data..."
	curl -X POST http://localhost:8080/admin/seed || true

# Benchmarks
bench\:uniform:
	@echo "Running uniform benchmark (2000 RPS x 60s)..."
	./gradlew :bench:gatlingRun-UniformSimulation

bench\:hot:
	@echo "Running hot merchant benchmark..."
	./gradlew :bench:gatlingRun-HotMerchantSimulation

bench\:slowdown:
	@echo "Running model slowdown benchmark..."
	./gradlew :bench:gatlingRun-ModelSlowdownSimulation

bench\:fault:
	@echo "Running fault injection benchmark..."
	./gradlew :bench:gatlingRun-FaultSimulation

# Cleanup
clean:
	./gradlew clean
	docker compose down -v
