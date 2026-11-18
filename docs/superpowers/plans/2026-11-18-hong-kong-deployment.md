# RunAgent Hong Kong Deployment Implementation Plan

**Goal:** Deploy and verify a private RunAgent service on the Hong Kong host, then connect Watson Running and validate the optional same-host MCP service.

**Architecture:** Build the executable JAR from a Git snapshot on the Mac. Transfer the JAR and runtime definition over SSH, assemble the image on the Linux host, and use Docker Compose with a loopback HTTP listener, a health gate, and a recoverable previous release.

**Tech Stack:** Gradle Wrapper, Docker Compose, SSH, Vite, Vercel.

**Spec:** The deployment sequence agreed in the conversation on 2026-11-18.

## Tasks

- [x] Verify the host key against the console and inventory the server and Docker.
- [x] Package the application and deliver a local release command with a health gate and rollback.
- [x] Exercise the release state machine, then start the image with Docker and verify HTTP through the loopback port.
- [x] Deploy to Hong Kong. Verify DeepSeek chat via an SSH tunnel and check the count and distance against a synthetic activity export.
- [ ] Integrate the authenticated JSON contract with Watson Running on Vercel. Verify same-origin routing and authorization at both origins.
- [ ] Enable the same-host MCP service. Verify discovery, three accepted tools, a data-backed request, and restart recovery.

Git SHA identifies the source. A release ID identifies each image build. The server records the last healthy image and one rollback target. The provider environment and activity export live outside release bundles.
