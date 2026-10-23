# CloudPulse: Windows 10 setup and live demo guide

This guide assumes a fresh personal Windows 10 computer and walks from prerequisites to the running demo. No cloud account, paid API, Linux install, or local Java/Node/PostgreSQL install is required. Docker downloads the project runtimes and runs them locally. The optional Kubernetes mode (section 8) also runs inside Docker Desktop, on a throwaway local cluster.

> **Before you start:** reserve about 15–30 minutes for first-time installs and image downloads, plus 1–3 minutes for the first app build. Download time depends on internet speed. Keep the laptop plugged in and the internet connected for the initial setup.

## 1. Check Windows and virtualization

1. Press **Windows key + R**.
2. Type `winver`, then press **Enter**. Docker's current requirements list 64-bit Windows 10 version **22H2 (build 19045)**, Enterprise, Pro, or Education. If your PC says Windows 10 Home, check the latest Docker support matrix before relying on it.
3. Right-click the **Start** button and choose **Task Manager**.
4. Select **Performance** → **CPU**. Look for **Virtualization: Enabled** near the lower right.
5. Confirm the computer has at least **8 GB RAM** and a 64-bit CPU that supports SLAT.
6. If virtualization says **Disabled**, restart into the computer's UEFI/BIOS settings and enable Intel VT-x or AMD-V/SVM. The key and menu differ by manufacturer; use its firmware instructions. Save and restart Windows.
7. Docker Desktop needs Windows Subsystem for Linux 2 (WSL 2.1.5 or newer). If this computer is managed by a school/employer, ask its administrator before changing virtualization settings.

## 2. Install or update WSL 2

1. Click **Start**, type `PowerShell`.
2. Right-click **Windows PowerShell** → **Run as administrator** → select **Yes** in the User Account Control dialog.
3. Run:

   ```powershell
   wsl --install --no-distribution
   ```

4. If Windows says WSL is already installed, update it instead:

   ```powershell
   wsl --update
   ```

5. Restart Windows if the command asks you to.
6. After restart, open a regular PowerShell window and run `wsl --version` and `wsl --status`. Confirm WSL is version 2.1.5 or newer and its default version is 2. If needed, run `wsl --set-default-version 2`, then `wsl --update` and restart.

The `--no-distribution` option installs WSL without installing a separate Linux distribution; Docker Desktop manages its own WSL environment. Microsoft lists this option in the [WSL command reference](https://learn.microsoft.com/windows/wsl/basic-commands). Docker's Windows support matrix and installer flow can change; check [Docker Desktop system requirements and install steps](https://docs.docker.com/desktop/setup/install/windows-install/) if your edition or WSL output differs.

## 3. Install Docker Desktop

1. In a browser, open [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/).
2. Select **Download Docker Desktop** for Windows. Use the **WSL 2 backend** (recommended) unless the machine requires a different backend.
3. Run the downloaded installer. For a personal machine, select the recommended **per-user** install if offered. Approve the security prompt if Windows shows one.
4. In configuration, keep **Use WSL 2 instead of Hyper-V** selected. Complete the installation.
5. Restart when prompted.
6. Launch **Docker Desktop** from Start. Accept the terms and wait until the status says Docker is running. A Docker account sign-in is optional for normal local use; skip it if offered. You need internet access to download the images on the first run.
7. If Docker asks for a WSL integration prompt, open **Settings** (gear) → **Resources** → **WSL Integration**. Turn on the default distribution if one is listed, then choose **Apply & restart**. Compose commands from Windows PowerShell also work with Docker Desktop's engine.
8. Verify in a regular PowerShell window:

   ```powershell
   docker --version
   docker compose version
   docker info
   ```

   `docker info` should show a Server section. If it says it cannot connect to the Docker daemon, return to Docker Desktop and wait for **Engine running**.

Docker Desktop licensing is free for personal use, education, non-commercial open-source work, and qualifying small businesses, subject to current terms. See [Docker subscription FAQs](https://www.docker.com/pricing/faq/) if your use is associated with a larger organization.

## 4. Install Git for Windows

If you received a ZIP of the project instead of a repository URL, skip to section 5. To clone from a Git repository:

1. Open [Git for Windows](https://git-scm.com/download/win) and download the installer.
2. Run it. The defaults are suitable; choose **Git from the command line and also from 3rd-party software** when offered.
3. Finish setup, open a new PowerShell window, and verify `git --version`.

## 5. Get the project files

### Option A — project ZIP

1. On the GitHub repository page, click **Code** → **Download ZIP**. Choose **Save** if the browser asks.
2. In File Explorer, click **Downloads** in the left sidebar.
3. Right-click the downloaded ZIP → **Extract All…**.
4. In the Extract dialog, choose **Browse…** → **Documents** → **Select Folder**. Leave **Show extracted files when complete** checked, then click **Extract**.
5. Open the extracted folder. Its address should end with something like `Documents\CloudPulse-main`.
6. Click the address bar, type `powershell`, then press **Enter**. PowerShell opens in the project folder.

### Option B — Git clone

1. Open PowerShell (not necessarily as administrator).
2. Move to a folder where you keep projects, for example:

   ```powershell
   cd $HOME\Documents
   ```

3. Clone the repository and enter it:

   ```powershell
   git clone https://github.com/abhyudaithakur/CloudPulse.git
   cd CloudPulse
   ```

### Confirm the correct folder

Run `Get-ChildItem`. You should see `docker-compose.yml`, `README.md`, `SETUP_WINDOWS.md`, and the folders `backend`, `demo-service`, `deploy`, `frontend`, `prometheus`, `scripts` and `traffic-generator`. If not, `cd` into the extracted/cloned project directory before continuing.

## 6. Check for port conflicts

In Docker Compose mode CloudPulse uses ports **3000, 5432, 8080, 8081, 8082 and 9090**; in Kubernetes mode (section 8) it uses **30080 and 30090**. If an error reports that a port is already allocated, close the other app using that port and retry. To check listeners (replace `3000` with the port number):

```powershell
Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue
```

An empty result means nothing is listening on that port. If the port is in use, Task Manager → **Details** can help identify the process; close it normally and retry.

## 7. Build and start the complete app

Make sure Docker Desktop shows **Engine running**. In PowerShell at the project root, run:

```powershell
docker compose up --build -d
```

The first run downloads the pinned free base images, compiles the Java apps and React UI, then starts seven containers. Wait for the PowerShell prompt to return. On a slower computer, allow several minutes. Confirm services:

```powershell
docker compose ps
```

Expected service names: `postgres`, `prometheus`, `checkout-api`, `payment-api`, `api`, `traffic-generator` and `dashboard`. They should show **Up**, most of them **(healthy)**. Give them 30–60 seconds to warm up.

The two demo services are the same small Spring Boot app started twice: the traffic generator calls **checkout-api**, and every checkout request calls **payment-api**. That dependency is what lets CloudPulse say "checkout is failing *because of* payment" (the probable source).

Open **http://localhost:3000** in Edge or Chrome. The CloudPulse overview should show **Healthy**, "Prometheus connected" in the header, the two services side by side, and three detection rules per service (request latency, server errors, reachability) with live values. For the first 30–60 seconds the service can show **Health unknown**: that is correct, because the latency and error-rate rules need a minute of traffic before Prometheus can compute them, and CloudPulse never reports health it cannot measure.

Quick checks from PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
```

All three return status `UP`. Prometheus is at **http://localhost:9090** (Status → Targets lists `checkout-api`, `payment-api` and `cloudpulse-api`). The raw demo metrics are at **http://localhost:8081/actuator/prometheus**.

To automatically exercise the full flow, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-demo.ps1
```

It checks (1) a latency fault on checkout-api: incident, deduplication, escalation, recovery; and (2) an error storm on payment-api that breaks checkout too, with checkout's incident naming **payment-api** as the probable source. It needs zero active incidents at the start, prints a PASS line per checkpoint plus measured times, and leaves every fault cleared. About 6 minutes; add `-Quick` for the 3-minute latency-only run.

In this mode CloudPulse **never changes a service**: the restart runbook is shown on incidents but marked unavailable, because there is no cluster to change. Section 8 runs the same app on Kubernetes, where an approved restart really happens.

> **Upgrading from an earlier version:** if `docker compose up` fails with a Flyway error about a non-empty schema, an old database volume is attached. Run `docker compose down -v` once (it deletes the old demo history) and start again.

## 8. Kubernetes mode: guarded remediation

Docker Compose shows detection and diagnosis. The Kubernetes mode adds the last part of the RAD: CloudPulse may **restart a deployment, but only after an operator approves it**, using a Kubernetes identity that can touch nothing else. It runs on **kind** ("Kubernetes in Docker"): a one-node cluster inside Docker Desktop. Nothing is installed system-wide; the script downloads `kind.exe` and `kubectl.exe` into the project's `.tools` folder if you do not already have them.

1. Stop the Compose stack first so the two modes do not compete for memory:

   ```powershell
   docker compose down
   ```

2. Start Kubernetes mode (first run: about 5 minutes; it builds the images, creates the cluster and waits until every pod is ready):

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\k8s-up.ps1
   ```

   It ends with **CloudPulse is running in Kubernetes mode** and two addresses.

3. Open **http://localhost:30080**. The dashboard is the same; the footer now says *Running on Kubernetes*. Prometheus is at **http://localhost:30090**.

4. See what CloudPulse is allowed to change (the answer is exactly two deployments):

   ```powershell
   .\.tools\kubectl.exe auth can-i --list -n cloudpulse-demo --as=system:serviceaccount:cloudpulse:cloudpulse-api
   ```

   If `kubectl` was already installed, run it as plain `kubectl`.

5. Try the whole loop automatically (about 5 minutes):

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\verify-demo.ps1 -Kubernetes
   ```

   It makes payment-api **stuck**, waits until checkout's incident names payment-api as the probable source, approves **Restart payment-api** as operator `verify-demo`, and waits for CloudPulse to report **Recovered**.

6. Watch the cluster while it happens (optional, in a second PowerShell window):

   ```powershell
   .\.tools\kubectl.exe -n cloudpulse-demo get pods -w
   ```

   During the restart a new `payment-api-…` pod starts, becomes ready, and the old one terminates.

7. Stop and delete the cluster when you are done (this also deletes the Kubernetes-mode incident history):

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\k8s-down.ps1
   ```

Docker Desktop's own Kubernetes setting is not needed and can stay off.

## 9. Exact live demo sequence for the professor

Allow **3–4 minutes**. Open the dashboard full-screen at **http://localhost:3000** before presenting. The system has already begun generating traffic and collecting telemetry.

1. **Establish the baseline (15 seconds).** Point to **Healthy**, "Prometheus connected", and the three rule lanes, each within threshold. Explain that synthetic traffic is already exercising the instrumented service.
2. **Show the architecture (20 seconds).** Say: “The demo service exports request histograms; Prometheus scrapes every five seconds; the Spring API evaluates three rules; PostgreSQL stores incidents, evidence and an append-only audit trail; this dashboard reads the API.”
3. **Inject the fault.** Under **Inject a fault**, click **High latency**. The service adds 1.4 seconds to each request.
4. **Narrate the sustained window.** The latency lane turns amber and its bar fills: “Over threshold for 12 of 30 s”. Point out that one slow request cannot open an incident; the violation must hold for the whole window. Expect roughly **35–50 seconds** from the click to a saved incident.
5. **Show the incident.** The band turns red (**Degraded**) and the lane says "Incident open". Open the incident from **Recent incidents**: show the severity (usually **High**: the histogram estimates p95 at about 1.5–1.8 s, above 1.5× the 1 s threshold), the evidence with the exact PromQL, and the timeline.
6. **Escalate (optional).** Click **Extreme latency**. Within seconds the *same* incident escalates to **Critical**; the timeline gains "Severity escalated". One incident, not two: that is deduplication.
7. **Recover the service.** Click **Normal**. The lane shows "Back within threshold for 4 of 10 s" once the rolling one-minute window drains, then the incident becomes **Resolved** with recovery evidence. This takes about 50–70 seconds after clicking Normal.
8. **Show honest telemetry (optional, 1 minute).** In PowerShell run `docker compose stop prometheus`. Within about 5 seconds the band becomes hatched **Health unknown** and every lane shows a dash, never a stale number. Run `docker compose start prometheus` and it returns to **Healthy**. Both events appear under **Activity**.

**Error fault:** **Error storm** makes 3 of every 10 requests fail with HTTP 500, which opens a **Critical** "Sustained HTTP 5xx error rate" incident after the same 30-second window.

**Probable source (1–2 minutes, either mode).** In **Inject a fault**, select **payment-api**, then **Error storm**. Payment fails 3 in 10 requests, and because checkout calls payment, checkout fails too (HTTP 502). Two incidents open. On the service map, payment-api is marked *Probable source*. Open **checkout-api's** incident: the **Probable source** panel says *payment-api*, with the dependency path and which problem started first. Then set payment-api back to **Normal**; both incidents resolve.

**Guarded restart (Kubernetes mode, about 3 minutes).** Select **payment-api** → **Stuck**. Try **Normal**: it is refused, like a wedged process. Kubernetes does not restart it either, because the pod is alive; it is just useless. When the incidents open, open checkout-api's incident, find **Restart payment-api** (marked *Recommended: probable source*), click **Review and approve**, type your name, tick the confirmation and click **Approve restart**. The progress row moves through *Approved → Restart sent → Rolling out → Verifying → Recovered*, and the timeline shows who approved it and what Kubernetes did. In Docker Compose mode the same button says *Not available* and explains why: that refusal is part of the safety design.

## 10. Troubleshooting

### “docker is not recognized”

Docker Desktop did not finish installation, or this PowerShell window predates installation. Launch Docker Desktop, wait for it to run, close PowerShell, open a new one, and retry `docker --version`.

### “Cannot connect to the Docker daemon”

Open Docker Desktop and wait for the engine. If WSL integration is requested, follow section 3 step 7. Run `wsl --update`, restart Windows, and retry. Confirm virtualization is enabled in Task Manager → Performance → CPU.

### Page shows “Waiting for the local API”

Run `docker compose ps`, then inspect startup logs:

```powershell
docker compose logs --tail 80 api
docker compose logs --tail 80 postgres
docker compose logs --tail 80 prometheus
```

Postgres must become healthy before the API can start. If a container exited, run `docker compose up --build -d` again after reading its logs.

### Build fails downloading an image or Maven/npm package

Check that internet access is available and no campus firewall/proxy is blocking Docker Hub or Maven Central. Retry `docker compose up --build -d`. No account or API token should be required. A partial first build can be resumed.

### A port is already allocated

Use the port check in section 6, stop the other local service, then run `docker compose up -d` again. The easiest demo fix is closing local PostgreSQL, other development servers, or another Prometheus instance before start.

### Dashboard says "Health unknown" or the lanes say "No data to evaluate"

That is CloudPulse refusing to guess. Right after startup it needs about a minute of traffic before the latency and error-rate queries return values. If it persists, check `docker compose logs --tail 80 traffic-generator checkout-api payment-api prometheus api` and open **http://localhost:8081/actuator/prometheus**; it should list `http_server_requests_seconds_bucket` lines once traffic flows.

### The API container keeps restarting after an upgrade

Read `docker compose logs --tail 80 api`. A Flyway error about a "non-empty schema" means an old database volume is attached; run `docker compose down -v` and start again.

### Fault button reports unavailable / no new incident appears

Confirm `checkout-api`, `payment-api` and `api` are Up. Check API and demo logs. After selecting a fault, keep it enabled for at least 50 seconds; the rule intentionally waits for a sustained breach.

### `k8s-up.ps1` fails

- "Docker Desktop is not running": start it and wait for **Engine running**.
- A download fails: check internet access, or install kind with `winget install Kubernetes.kind` and run the script again.
- It times out waiting for a pod: run `.\.tools\kubectl.exe get pods -A` and `.\.tools\kubectl.exe -n cloudpulse logs deployment/cloudpulse-api --tail 80`. On a computer with 8 GB of RAM, close other apps and make sure the Compose stack is stopped (`docker compose down`).
- Ports 30080/30090 in use: another kind cluster may be running; list them with `.\.tools\kind.exe get clusters`.
- Starting over: `.\scripts\k8s-down.ps1`, then `.\scripts\k8s-up.ps1`.

### Need a clean restart

Use the reset instructions in section 11. `docker compose down` alone preserves data and is sufficient for a routine restart.

## 11. Stop, restart, and completely reset

From the project directory:

```powershell
docker compose down
```

This stops and removes containers but **keeps the incident database and Prometheus history**. Start again with `docker compose up -d`.

To clear all recorded incidents and metrics and return to a pristine demo, run:

```powershell
docker compose down -v
docker compose up --build -d
```

The `-v` removes the two local named data volumes (`cloudpulse-pg` and `prometheus-data`). It deletes this demo's saved incident/metrics history. It does not delete your source files. Wait for containers to start, then confirm a clean healthy dashboard before the meeting.

To stop and remove the downloaded project images later (optional; images can be reused by Docker):

```powershell
docker compose down --rmi local
```

## 12. Final presentation readiness checklist

- Docker Desktop is open and says the engine is running.
- `docker compose ps` shows all seven services Up.
- `powershell -ExecutionPolicy Bypass -File .\scripts\verify-demo.ps1 -Quick` passed on this machine today.
- If you will show remediation: `.\scripts\k8s-up.ps1` and `verify-demo.ps1 -Kubernetes` passed today (start it 10 minutes before presenting, and stop the Compose stack first).
- Dashboard opens at **http://localhost:3000** and starts Healthy.
- Prometheus opens at **http://localhost:9090**.
- You know the triggers: **p95 ≥ 1.0 s**, **5xx ≥ 5%**, each for **30 continuous seconds**; **unreachable for 15 s**.
- You know the recovery delay: rolling one-minute metric window plus **10 seconds** within threshold.
- Do not run `docker compose down -v` after you begin the demo; it erases the prototype's saved data.

Maven and npm dependency versions are locked in the project files. Container image tags select compatible major/minor lines; upstream image patch updates can occur over time. Once Docker has built the images, the application runs locally without a paid service.
