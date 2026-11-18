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
- [x] Integrate the authenticated JSON contract with Watson Running on Vercel. Verify same-origin routing and authorization at both origins.
- [x] Enable the same-host MCP service. Verify discovery, three accepted tools, a data-backed request, and restart recovery.

Git SHA identifies the source. A release ID identifies each image build. The server records the last healthy image and one rollback target. The provider environment and activity export live outside release bundles.

## Verification

- The Hong Kong host uses Ubuntu 24.04 and Docker Compose v5. The first single-service release was restored after a second successful deployment. The restored container reported healthy at approximately 219 MiB.
- DeepSeek answered a generic Chinese running question over the SSH tunnel. The running-only workflow matched the count and distance of the synthetic fixture.
- Watson Running PR #32 passed GitHub checks. The Vercel preview included the assistant bundle. Vercel platform authentication prevented the automated preview POST, so the complete POST was verified on the production domain after the authorized merge. Both the HTTPS origin and Vercel rejected anonymous requests and returned a request ID and answer for authorized requests.
- The official MCP SDK initialized the deployed MCP Server and verified all three tool results. After restarting both containers, RunAgent rediscovered all three tools and the workflow again matched the synthetic fixture. The application, management and MCP ports listened on loopback. Approximate memory in that sample was 235 MiB for RunAgent and 132 MiB for MCP.
