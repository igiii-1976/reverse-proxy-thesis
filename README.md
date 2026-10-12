# Repurposing Android Phones as Servers

### A Reverse Proxy Architecture for Memory-Constrained Edge Computing

This project presents a distributed edge computing architecture that repurposes Android smartphones as edge servers for computationally intensive workloads. The system uses an Android-based reverse proxy to distribute image recognition requests across multiple Android devices running TensorFlow Lite.

The architecture addresses a key limitation of repurposed mobile hardware: **restricted Java heap memory**. A semaphore-based crash prevention mechanism regulates the admission of memory-intensive image recognition tasks, while RTT-based routing improves workload distribution according to the observed performance of each edge device.

---

## Overview

The system consists of two (2) main components:

- **Reverse Proxy Server** — receives client requests, discovers available edge devices, selects an edge server using the configured routing strategy, and forwards requests.
- **Android Edge Servers** — process image recognition workloads using TensorFlow Lite and return recognition results to the reverse proxy.

The reverse proxy and edge servers communicate over a local Wi-Fi network.

<img width="1765" height="825" alt="system architecture" src="https://github.com/user-attachments/assets/b4e522b0-f26b-4b2b-a0f0-07930eb63135" />

---

## Key Features

### Android-Based Reverse Proxy Server

The reverse proxy is implemented using [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) and acts as the central coordinator for incoming requests.

It:

- Receives HTTP requests from clients
- Maintains a registry of available edge servers
- Discovers edge devices using Android NSD/mDNS
- Monitors edge-server status and battery information
- Selects an edge server according to the active routing strategy
- Forwards requests using OkHttp
- Returns the edge-server response to the client

### Android Edge Servers

Each edge server runs an embedded NanoHTTPD server and processes image recognition requests using TensorFlow Lite.

The edge server provides:

- `/status` endpoint for runtime device information
- Image recognition request handling
- TensorFlow Lite inference
- Temporary image-file management
- Java heap monitoring
- Memory-aware request admission

### Memory-Aware Crash Prevention

Android applications operate within a bounded Java heap. Concurrent image recognition requests can therefore cause excessive memory allocation and potentially trigger `OutOfMemoryError`.

The proposed Crash Prevention Technique (CPT) estimates the Java heap memory required by each image recognition request and uses a semaphore to control admission.

```text
Incoming Request
       │
       ▼
Estimate Memory Requirement
       │
       ▼
Acquire Memory Semaphore Permits
       │
       ├── Sufficient Memory ──► Process Request
       │
       └── Insufficient Memory
                    │
                    ▼
              Wait for Permits
                    │
                    ▼
              Process Request
                    │
                    ▼
             Release Permits
```

This prevents multiple memory-intensive recognition tasks from executing simultaneously when the available heap cannot safely accommodate them.

---

## System Architecture

The complete architecture can be summarized here:

<img width="5544" height="2854" alt="timeline drawio" src="https://github.com/user-attachments/assets/714b38a2-a21d-4f50-a888-4e8779f78217" />

---

## Application Preview / Screenshots

This is how the Reverse Proxy and Edge servers' applications look.

<img width="1052" height="921" alt="UI" src="https://github.com/user-attachments/assets/c6be8e45-5432-421a-9c34-eb97b3efa3da" />

---

## Repository Structure

The implementation is divided into two repositories:

### Reverse Proxy

The Android reverse proxy implementation is available at:

https://github.com/igiii-1976/reverse-proxy-thesis

### Edge Server

The Android edge server implementation is available at:

https://github.com/Alphxs/helloServer

---

## Technologies Used

### Reverse Proxy / Edge Servers

- Java, JavaScript, Kotlin
- NanoHTTPD
- OkHttp
- Android NSD / mDNS
- TensorFlow Lite
- SSD MobileNetV1
- Java Concurrency / Semaphore

---

## Research Paper

This repository contains the implementation associated with the thesis:

> **Repurposing Android Phones as Servers: A Reverse Proxy Architecture for Memory-Constrained Edge Computing**

The work investigates whether discarded or repurposed Android smartphones can be organized into a distributed edge computing platform while addressing the memory limitations imposed by the Android Runtime.

The complete experimental methodology, algorithms, results, and discussion are documented in the accompanying thesis.

---

## Authors

**Mark Luigi M. Cumabig**  
**Azrael Darius O. Eusebio**  
**Dr. Wilson M. Tan**

Networks and Distributed Systems Laboratory  
Department of Computer Science  
University of the Philippines Diliman  
Quezon City, Philippines

---

## Acknowledgment

This project was developed as an undergraduate thesis at the **University of the Philippines Diliman, Department of Computer Science**, under the Networks and Distributed Systems Laboratory.
