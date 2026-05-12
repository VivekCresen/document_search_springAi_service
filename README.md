# Document Search Spring AI Service

Intelligent document search microservice using Azure OpenAI and Spring AI framework.

## Overview

This service provides AI-powered document search and question-answering capabilities. It integrates with Azure OpenAI for natural language processing and Azure Blob Storage for document management. The service uses vector embeddings for semantic search and supports Retrieval-Augmented Generation (RAG) for accurate responses.

## Architecture

- **Backend**: Spring Boot with Spring AI
- **AI Engine**: Azure OpenAI (GPT-4, embeddings)
- **Storage**: Azure Blob Storage for documents
- **Database**: PostgreSQL with pgvector for embeddings
- **Discovery**: Netflix Eureka for service registration
- **Monitoring**: Spring Boot Actuator with Prometheus metrics

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- PostgreSQL with pgvector extension
- Azure OpenAI account
- Azure Blob Storage account

### Setup

1. **Install pgvector extension in PostgreSQL**
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

2. **Configure environment variables**
```bash
cp .env.example .env
# Edit .env with your Azure credentials
```

3. **Build and run**
```bash
mvn clean install
mvn spring-boot:run
```

### Service Information
- **Port**: 8084 (configurable via `server.port`)
- **Service Name**: DOCUMENT-SEARCH-SERVICE
- **API Gateway Route**: `http://localhost:8080/api/documents/**`
- **Swagger UI**: `http://localhost:8084/swagger-ui.html`
- **Health Check**: `http://localhost:8084/actuator/health`

## Key Features

- ✅ Document upload to Azure Blob Storage
- ✅ Semantic search using vector embeddings
- ✅ Question answering over documents (RAG)
- ✅ Document summarization
- ✅ PDF and DOCX support
- ✅ Eureka service discovery
- ✅ Circuit breaker and rate limiting
- ✅ Prometheus metrics
- ✅ Async processing for performance
- ✅ User access control and permissions
- ✅ Chat history management

## API Endpoints

### Document Management
- `POST /api/documents/upload` - Upload a document
- `GET /api/documents/download/{documentId}` - Download a document

### Query Processing
- `POST /query` - Ask questions about documents

## Configuration

The service is configured via `application.yaml`. Key configurations include:

- **Server**: Port and context path
- **Database**: PostgreSQL connection settings
- **Azure OpenAI**: API endpoint, key, and model deployment
- **Azure Storage**: Account details and container name
- **Eureka**: Service discovery settings (can be disabled for local dev)
- **Async**: Thread pool configuration for concurrent processing
- **Cache**: Caffeine cache settings for performance

## Development

### Running Locally
1. Ensure PostgreSQL is running with pgvector
2. Set environment variables for Azure services
3. Run `mvn spring-boot:run`
4. Access Swagger UI at `http://localhost:8084/swagger-ui.html`

### Testing
```bash
mvn test
```

### Building
```bash
mvn clean package
```

## API Endpoints

### Via API Gateway (Port 8080)
```bash
# Upload document
POST http://localhost:8080/api/documents/upload

# Search documents
POST http://localhost:8080/api/documents/search
Body: { "query": "your search query" }

# Ask question
POST http://localhost:8080/api/documents/ask
Body: { "question": "your question" }

# Get document
GET http://localhost:8080/api/documents/{id}

# Delete document
DELETE http://localhost:8080/api/documents/{id}
```

## Architecture

```
User Request → API Gateway (8080) → Document Search Service (8084)
                                    ↓
                            Azure OpenAI (Embeddings/Chat)
                                    ↓
                            PostgreSQL + pgvector
                                    ↓
                            Azure Blob Storage
```

## Dependencies

### Spring AI
- `spring-ai-azure-openai-spring-boot-starter` - Azure OpenAI integration
- `spring-ai-pgvector-store-spring-boot-starter` - Vector database
- `spring-ai-pdf-document-reader` - PDF processing
- `spring-ai-tika-document-reader` - Multi-format document processing

### Azure
- `azure-storage-blob` - Blob storage client

### Spring Cloud
- `spring-cloud-starter-netflix-eureka-client` - Service discovery

### Monitoring
- `spring-boot-starter-actuator` - Health and metrics
- `micrometer-registry-prometheus` - Prometheus integration

## Configuration

### Azure OpenAI
```yaml
spring:
  ai:
    azure:
      openai:
        api-key: ${AZURE_OPENAI_API_KEY}
        endpoint: ${AZURE_OPENAI_ENDPOINT}
```

### Vector Store
```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
```

### Azure Storage
```yaml
azure:
  storage:
    account-name: cresengpt
    account-key: ${AZURE_STORAGE_ACCOUNT_KEY}
    container-name: internchatgptdoccontainer
```

## Development

### Run locally
```bash
mvn spring-boot:run
```

### Build
```bash
mvn clean package
```

### Run with custom profile
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Monitoring

### Health Check
```bash
curl http://localhost:8084/actuator/health
```

### Metrics
```bash
curl http://localhost:8084/actuator/metrics
```

### Prometheus
```bash
curl http://localhost:8084/actuator/prometheus
```

## Troubleshooting

### Service not starting
- Check if port 8084 is available
- Verify PostgreSQL is running
- Ensure pgvector extension is installed

### Azure connection issues
- Verify API keys in environment variables
- Check Azure OpenAI endpoint URL
- Ensure Azure Storage credentials are correct

### Eureka registration failed
- Verify Eureka server is running on port 8761
- Check network connectivity
- Review application.yaml configuration

## Documentation

See [AZURE_AI_DOCUMENTATION.md](./AZURE_AI_DOCUMENTATION.md) for comprehensive documentation including:
- Architecture details
- API specifications
- Security considerations
- Performance optimization
- Best practices
- Troubleshooting guide

## Support

For issues or questions, contact the development team.
