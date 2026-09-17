project obj:

build a prod-ready async batch eval engine that reads a file array of prompts from a local workspace, chunks and fans out execution across a bounded worker pool invoking live inference endpoints, shall manage backpressure from upstream endpoints and shall aggregate scattered results into structured reports

FR:
1. Batch file ingest : shall read from local workspace, probably a json containing prompts (say 1000). Should return the job ID immediately.

2. Scatter-Gather distrib : partition the prompts into concurrent execution chunks and feed them to bounded internal worker pool invoking live-inference endpoints. Workers should prioritize cost-efficient confifs for high-throughput
testing

3. Handle backpressure

4. Report partial failures: isolate individual failures. Successfull prompts should be compiled together, GET /job/{id}/status should work and GET /job/{id}/download should retrive the final compiled array

NFR:

1. Should handle OOM crashes in case of resource exhaustion. Should have scaling limits
2. unit and int tests
3. Deploy functionally to git


Iterations on top if time is there:
1. Persist data to DigitalOcean space bucket
2. Webhook support : provide registration of callback url to notify if anyone is listening in case of heavy aggregation processing job

## Index Diagram

```mermaid
flowchart LR
    A[Prompt Batch File] --> B[Ingest API]
    B --> C[Job ID Returned Immediately]
    C --> D[Orchestrator]
    D --> E[Chunking / Partitioning]
    E --> F[Admission Queue]
    F --> G[Worker Pool Manager / Autoscaler]
    G --> H[Bounded Worker Pool]
    H --> I[Inference Call]
    I --> J[Live Inference Endpoint]

    J --> K[Response Metrics]
    K --> L[Metrics Collector]
    L --> M[Backpressure Controller]
    L --> G
    M --> N[Throttle / Reduce Concurrency / Reject]
    N --> F

    H --> O[Result Queue]
    O --> P[Result Aggregator Pool]
    P --> Q[Job State Store]
    Q --> R[GET /job/{id}/status]
    P --> S[Final Report / Download]
    S --> T[GET /job/{id}/download]

    P --> U[Partial Failure Isolation]
    P --> V[Success Compilation]

    W[Resource Guard / Max Concurrency] --> G
    W --> M

    X[Unit Tests + Integration Tests] --> Y[Git Deployment]
```

## Architecture Notes

### Backpressure ownership
The Backpressure Controller is the component that decides when the system should slow or reject work. It receives feedback from the Metrics Collector, which aggregates worker latency, timeout rate, queue depth, and resource-pressure signals from the downstream inference endpoints.

### When backpressure is applied
Backpressure is triggered before the worker pool or queue exceeds safe limits. Typical triggers include queue depth above threshold, in-flight concurrency at the configured cap, increasing downstream latency, elevated 429/5xx error rates, or memory/CPU pressure.

### How downstream pressure is detected
Workers emit per-request runtime signals such as latency, timeout, retry count, and HTTP status codes. The Metrics Collector aggregates these signals and provides them to the Backpressure Controller and the Worker Pool Manager so they can adapt concurrency and queue admission.

### Memory exhaustion during chunking
If memory exhaustion is detected during the chunking stage, the Orchestrator should stop accepting or buffering additional chunks and apply backpressure before the heap grows past safe limits. The system must stream or chunk input in bounded sizes and enforce max prompt count, max buffered bytes, and max queue depth.

### Worker pool sizing and scaling
The worker pool limit should be derived from endpoint throughput, memory headroom, latency targets, and cost caps. The Worker Pool Manager adjusts concurrency dynamically using min/max bounds and scaling signals from queue depth, error rate, and resource metrics.

### Result aggregation
The Result Aggregator Pool is separate from the worker pool and is responsible for consuming result events, isolating failed prompts, compiling successful results, and updating job status. This keeps the expensive inference workers focused on execution while the smaller aggregator pool manages final assembly.

### Cost-efficiency for high-throughput testing
Cost efficiency is achieved by preferring the cheapest viable inference configuration for high-volume prompts, routing low-risk workloads to lower-cost endpoints, and only escalating to premium models when necessary. The system should use bounded concurrency, adaptive scaling, retry caps, deduplication, batching, and cost-aware routing instead of blindly scaling workers for throughput. The key metric is successful throughput per dollar, not raw request rate alone.

## Running

This repository contains a Spring Boot 3.4 service targeting Java 17. Run it with:

```bash
mvn spring-boot:run
```

Place a JSON array of prompts in the configured local workspace directory (`./workspace-input` by default). Submit the server-side file name; the API returns immediately with a job ID:

```bash
curl -X POST --data-urlencode 'file=prompts.json' http://localhost:8080/job/local
curl http://localhost:8080/job/{jobId}/status
curl http://localhost:8080/job/{jobId}/download
```

The multipart `POST /job` endpoint is also available when a client needs to upload a file. The default inference adapter echoes prompts for local development. Set `inference.endpoint` to a POST endpoint that accepts `{ "prompt": "..." }` to use live inference. Bounded worker, queue, prompt-count, upload-size, and retained-job limits are configured in `application.yml`.

Ingestion uses Jackson's token streaming API and never materializes the full prompt array. Each job owns a bounded prompt-chunk queue keyed by its job object: the ingestion pool blocks when that queue is full, and the worker pool consumes chunks independently. `batch.ingestion-threads` controls file readers; `batch.worker-threads` controls inference workers.

For cost-efficient high-throughput testing, inference uses the configured `cheap` route by default. Routes can point to separate models or providers and declare an estimated request price:

```yaml
inference:
    default-route: cheap
    routes:
        - name: cheap
            endpoint: https://low-cost-model.example/evaluate
            cost-per-request: 0.001
        - name: premium
            endpoint: https://high-quality-model.example/evaluate
            cost-per-request: 0.02
```

The selected route is recorded in `batch.inference.route.requests`, and its estimated request cost is added to `batch.inference.estimated.cost`. This gives high-volume tests a low-cost default while retaining an explicit premium route for future workload classification. The current router selects `inference.default-route`; automatic risk-based escalation is not enabled.

Jobs also apply an internal streaming workload decision. An explicit `route` request parameter always wins. If no route is supplied, prompts start on `inference.default-route`; once the streamed prompt count reaches `batch.high-throughput-prompt-threshold`, the job switches to `batch.high-throughput-route` (configured as `cheap` by default). This avoids buffering the file just to classify its size.

Run unit and integration tests with:

```bash
mvn test
```

