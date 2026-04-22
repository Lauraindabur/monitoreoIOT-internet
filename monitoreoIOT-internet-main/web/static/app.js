const dashboardRoot = document.querySelector(".dashboard-grid");

if (dashboardRoot) {
    const state = {
        snapshot: null,
        selectedSensorId: "",
        pollHandle: null,
        busyAction: false,
    };

    const banner = document.getElementById("dashboard-banner");
    const statsGrid = document.getElementById("stats-grid");
    const sensorsBody = document.getElementById("sensors-body");
    const measurementsBody = document.getElementById("measurements-body");
    const alertsList = document.getElementById("alerts-list");
    const statusList = document.getElementById("status-list");
    const sensorDetail = document.getElementById("sensor-detail");
    const sensorFocusSelect = document.getElementById("sensor-focus-select");
    const snapshotTime = document.getElementById("snapshot-time");
    const connectionPill = document.getElementById("connection-pill");
    const alertsPill = document.getElementById("alerts-pill");
    const consoleForm = document.getElementById("iot-console-form");
    const commandSelect = document.getElementById("iot-command-select");
    const commandSensorId = document.getElementById("iot-sensor-id");
    const consoleOutput = document.getElementById("iot-console-output");
    const consoleClear = document.getElementById("iot-console-clear");

    document.querySelectorAll("[data-action]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (state.busyAction) {
                return;
            }
            const action = button.getAttribute("data-action");
            if (!action) {
                return;
            }
            state.busyAction = true;
            button.disabled = true;
            try {
                await executeAction(action);
            } finally {
                state.busyAction = false;
                button.disabled = false;
            }
        });
    });

    sensorFocusSelect.addEventListener("change", () => {
        state.selectedSensorId = sensorFocusSelect.value;
        renderSelectedSensor();
        highlightSelectedRow();
    });

    if (consoleForm) {
        consoleForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            await submitConsoleCommand();
        });
    }

    if (commandSelect) {
        commandSelect.addEventListener("change", () => {
            const selected = commandSelect.value;
            const needsSensor = selected === "GET_LAST";
            commandSensorId.disabled = !needsSensor;
            commandSensorId.placeholder = needsSensor ? "temp_01" : "No aplica";
        });
        commandSelect.dispatchEvent(new Event("change"));
    }

    if (consoleClear) {
        consoleClear.addEventListener("click", () => {
            consoleOutput.innerHTML = `<div class="empty-state">Aun no has enviado comandos.</div>`;
        });
    }

    bootstrap();

    async function bootstrap() {
        await refreshSnapshot();
        state.pollHandle = window.setInterval(refreshSnapshot, 5000);
    }

    async function executeAction(action) {
        const endpoints = {
            "refresh": "/api/actions/refresh",
            "acknowledge-alerts": "/api/actions/acknowledge-alerts",
        };
        const endpoint = endpoints[action];
        if (!endpoint) {
            return;
        }

        const response = await fetch(endpoint, {
            method: "POST",
            headers: { "Accept": "application/json" },
        });

        if (response.status === 401) {
            window.location.assign("/login");
            return;
        }
        if (!response.ok) {
            throw new Error(`No fue posible ejecutar la accion (${response.status})`);
        }

        state.snapshot = await response.json();
        syncSelectedSensor();
        render();
    }

    async function refreshSnapshot() {
        try {
            const response = await fetch("/api/dashboard", {
                headers: { "Accept": "application/json" },
            });

            if (response.status === 401) {
                window.location.assign("/login");
                return;
            }
            if (!response.ok) {
                throw new Error(`Respuesta inesperada del dashboard (${response.status})`);
            }

            state.snapshot = await response.json();
            syncSelectedSensor();
            render();
        } catch (error) {
            renderBanner({
                level: "critical",
                message: error instanceof Error ? error.message : "No fue posible consultar el dashboard.",
            });
        }
    }

    function syncSelectedSensor() {
        const sensors = state.snapshot?.sensors || [];
        if (!sensors.length) {
            state.selectedSensorId = "";
            return;
        }
        if (state.selectedSensorId && sensors.some((sensor) => sensor.id === state.selectedSensorId)) {
            return;
        }
        state.selectedSensorId = sensors[0].id;
    }

    function render() {
        if (!state.snapshot) {
            return;
        }
        renderBannerFromSnapshot(state.snapshot);
        renderStats(state.snapshot.metrics);
        renderSensors(state.snapshot.sensors);
        renderMeasurements(state.snapshot.measurements);
        renderAlerts(state.snapshot.alerts);
        renderStatus(state.snapshot.system);
        renderSelectedSensor();
        renderToolbar(state.snapshot);
    }

    async function submitConsoleCommand() {
        if (!commandSelect) {
            return;
        }

        const command = commandSelect.value;
        const sensorId = commandSensorId?.value?.trim() || "";
        if (command === "GET_LAST" && !sensorId) {
            renderBanner({ level: "warning", message: "Debes indicar un sensor_id para GET_LAST." });
            return;
        }

        appendConsoleLine({
            direction: "TX",
            text: command === "GET_LAST" ? `GET_LAST ${sensorId}` : command,
        });

        try {
            const response = await fetch("/api/commands", {
                method: "POST",
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({ command, sensor_id: sensorId }),
            });

            if (response.status === 401) {
                window.location.assign("/login");
                return;
            }
            const data = await response.json();
            if (!response.ok) {
                appendConsoleLine({
                    direction: "RX",
                    text: `ERROR ${data.error || response.status}`,
                    meta: "Fallo HTTP",
                });
                return;
            }

            const item = data.transcript?.[0];
            appendConsoleLine({
                direction: "RX",
                text: item?.response || "(sin respuesta)",
                meta: `IoT ${data.iot_host}:${data.iot_port}`,
            });
        } catch (error) {
            appendConsoleLine({
                direction: "RX",
                text: error instanceof Error ? error.message : "Error desconocido",
                meta: "Excepcion",
            });
        }
    }

    function appendConsoleLine({ direction, text, meta }) {
        if (!consoleOutput) {
            return;
        }

        if (consoleOutput.querySelector(".empty-state")) {
            consoleOutput.innerHTML = "";
        }

        const timestamp = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
        const metaText = meta ? `${timestamp} - ${meta}` : timestamp;
        const line = document.createElement("div");
        line.className = "console-line";
        line.innerHTML = `
            <div class="console-meta">${escapeHtml(direction)} - ${escapeHtml(metaText)}</div>
            <code>${escapeHtml(text)}</code>
        `;
        consoleOutput.prepend(line);
    }

    function renderToolbar(snapshot) {
        snapshotTime.textContent = snapshot.updated_at
            ? `Ultima actualizacion: ${formatTimestamp(snapshot.updated_at)}`
            : "Sin actualizacion aun";

        connectionPill.textContent = snapshot.system.iot_reachable
            ? "Servidor IoT reachable"
            : "Servidor IoT con error";
        connectionPill.className = `pill ${snapshot.system.iot_reachable ? "status-ok" : "status-critical"}`;

        alertsPill.textContent = snapshot.alerts.length
            ? `${snapshot.alerts.length} alerta(s) pendientes`
            : "Sin alertas pendientes";
        alertsPill.className = `pill pill-soft ${snapshot.alerts.length ? "status-warning" : "status-ok"}`;

        const options = snapshot.sensors.map((sensor) => {
            const selected = sensor.id === state.selectedSensorId ? " selected" : "";
            return `<option value="${escapeHtml(sensor.id)}"${selected}>${escapeHtml(sensor.id)} (${escapeHtml(sensor.tipo)})</option>`;
        });
        sensorFocusSelect.innerHTML = `<option value="">Seleccione un sensor</option>${options.join("")}`;
    }

    function renderStats(metrics) {
        const cards = [
            { label: "Sensores activos", value: metrics.sensor_count },
            { label: "Alertas pendientes", value: metrics.alert_count },
            { label: "Mediciones recientes", value: metrics.measurement_count },
            { label: "Sensores criticos", value: metrics.critical_count },
            { label: "Sensores en atencion", value: metrics.warning_count },
        ];

        Object.entries(metrics.by_type).forEach(([type, count]) => {
            cards.push({
                label: `Tipo ${type}`,
                value: count,
            });
        });

        statsGrid.innerHTML = cards.map((card) => (
            `<article class="stat-card"><strong>${escapeHtml(String(card.value))}</strong><span>${escapeHtml(card.label)}</span></article>`
        )).join("");
    }

    function renderSensors(sensors) {
        if (!sensors.length) {
            sensorsBody.innerHTML = `<tr><td colspan="5" class="empty-cell">No hay sensores activos en este momento.</td></tr>`;
            return;
        }

        sensorsBody.innerHTML = sensors.map((sensor) => {
            return `
                <tr data-sensor-id="${escapeHtml(sensor.id)}">
                    <td><strong>${escapeHtml(sensor.id)}</strong></td>
                    <td><span class="sensor-type-chip sensor-type-${escapeHtml(sensor.tipo)}">${escapeHtml(sensor.tipo)}</span></td>
                    <td>${escapeHtml(sensor.last_value || "Sin dato")}</td>
                    <td>${escapeHtml(formatTimestamp(sensor.last_timestamp) || "Sin dato")}</td>
                    <td><span class="status-chip status-${escapeHtml(sensor.status)}">${escapeHtml(sensor.status_label)}</span></td>
                </tr>
            `;
        }).join("");

        sensorsBody.querySelectorAll("tr[data-sensor-id]").forEach((row) => {
            row.addEventListener("click", () => {
                const sensorId = row.getAttribute("data-sensor-id");
                if (!sensorId) {
                    return;
                }
                state.selectedSensorId = sensorId;
                sensorFocusSelect.value = sensorId;
                renderSelectedSensor();
                highlightSelectedRow();
            });
        });

        highlightSelectedRow();
    }

    function renderSelectedSensor() {
        const sensors = state.snapshot?.sensors || [];
        const selected = sensors.find((sensor) => sensor.id === state.selectedSensorId);
        if (!selected) {
            sensorDetail.className = "detail-card detail-empty";
            sensorDetail.textContent = "Selecciona un sensor para inspeccionar su ultima medicion y su estado operativo.";
            return;
        }

        sensorDetail.className = "detail-card";
        sensorDetail.innerHTML = `
            <span class="sensor-type-chip sensor-type-${escapeHtml(selected.tipo)}">${escapeHtml(selected.tipo)}</span>
            <h3>${escapeHtml(selected.id)}</h3>
            <p class="detail-meta">${escapeHtml(selected.status_detail)}</p>
            <div class="detail-grid">
                <div>
                    <span>Ultima medicion</span>
                    <strong>${escapeHtml(selected.last_value || "Sin dato")}</strong>
                </div>
                <div>
                    <span>Estado</span>
                    <strong>${escapeHtml(selected.status_label)}</strong>
                </div>
                <div>
                    <span>Timestamp</span>
                    <strong>${escapeHtml(formatTimestamp(selected.last_timestamp) || "Sin dato")}</strong>
                </div>
                <div>
                    <span>Tipo</span>
                    <strong>${escapeHtml(selected.tipo)}</strong>
                </div>
            </div>
        `;
    }

    function renderAlerts(alerts) {
        if (!alerts.length) {
            alertsList.className = "stack-list empty-state";
            alertsList.textContent = "Aun no se han recibido alertas desde el servidor.";
            return;
        }

        alertsList.className = "stack-list";
        alertsList.innerHTML = alerts.map((alert) => `
            <article class="alert-card status-${escapeHtml(alert.severity)}">
                <strong>${escapeHtml(alert.label)} - ${escapeHtml(alert.sensor_id)}</strong>
                <p>Valor: ${escapeHtml(alert.value || "Sin dato")}</p>
                <p>Timestamp del servidor: ${escapeHtml(formatTimestamp(alert.timestamp))}</p>
                <p>Recibida en web: ${escapeHtml(formatTimestamp(alert.received_at))}</p>
            </article>
        `).join("");
    }

    function renderMeasurements(measurements) {
        if (!measurements.length) {
            measurementsBody.innerHTML = `<tr><td colspan="5" class="empty-cell">Sin mediciones recientes disponibles.</td></tr>`;
            return;
        }

        measurementsBody.innerHTML = measurements.map((measurement) => `
            <tr>
                <td><strong>${escapeHtml(measurement.sensor_id)}</strong></td>
                <td>${escapeHtml(measurement.tipo)}</td>
                <td>${escapeHtml(measurement.value)}</td>
                <td>${escapeHtml(formatTimestamp(measurement.timestamp))}</td>
                <td><span class="status-chip status-${escapeHtml(measurement.status)}">${escapeHtml(measurement.status_label)}</span></td>
            </tr>
        `).join("");
    }

    function renderStatus(system) {
        const rows = [
            ["Host IoT", system.iot_host],
            ["Puerto IoT", String(system.iot_port)],
            ["Resolucion", system.dns_strategy],
            ["Usuarios", "Servicio externo"],
            ["Clientes simultaneos", system.supports_multi_clients ? "Soportados" : "No disponible"],
            ["Reachable", system.iot_reachable ? "Si" : "No"],
            ["Stream de alertas", system.alert_stream_connected ? "Conectado" : "Desconectado"],
            ["Ultimo exito", system.last_success_at || "Sin exito aun"],
            ["Ultima alerta", system.last_alert_at || "Sin alertas"],
            ["Stale", system.stale ? "Si" : "No"],
        ];

        if (system.last_error) {
            rows.push(["Ultimo error", system.last_error]);
        }

        statusList.innerHTML = rows.map(([label, value]) => `
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(formatTimestamp(value))}</dd>
        `).join("");
    }

    function renderBannerFromSnapshot(snapshot) {
        if (!snapshot.system.iot_reachable) {
            renderBanner({
                level: "critical",
                message: snapshot.system.last_error || "No fue posible consultar el servidor IoT. La interfaz mantiene el ultimo snapshot disponible.",
            });
            return;
        }
        if (snapshot.system.stale) {
            renderBanner({
                level: "warning",
                message: "El dashboard esta usando datos viejos. Verifica la conectividad con el servidor IoT.",
            });
            return;
        }
        if (snapshot.alerts.length) {
            renderBanner({
                level: "warning",
                message: `Hay ${snapshot.alerts.length} alerta(s) pendientes de revision.`,
            });
            return;
        }
        hideBanner();
    }

    function renderBanner({ level, message }) {
        banner.className = `banner ${level}`;
        banner.textContent = message;
    }

    function hideBanner() {
        banner.className = "banner hidden";
        banner.textContent = "";
    }

    function highlightSelectedRow() {
        sensorsBody.querySelectorAll("tr[data-sensor-id]").forEach((row) => {
            const isSelected = row.getAttribute("data-sensor-id") === state.selectedSensorId;
            row.classList.toggle("is-selected", isSelected);
        });
    }

    function formatTimestamp(value) {
        if (!value) {
            return "";
        }
        return String(value).replace("T", " ");
    }

    function escapeHtml(value) {
        return String(value)
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    }
}
