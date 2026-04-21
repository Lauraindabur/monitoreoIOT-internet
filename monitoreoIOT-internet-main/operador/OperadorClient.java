/**
 * OperadorClient.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Cliente operador para el sistema distribuido de monitoreo IoT.
 *
 * Rol dentro del sistema:
 *   - Se conecta al servidor central via TCP (sin IPs fijas, usa DNS).
 *   - Se registra como OPERATOR usando el protocolo IOT-MONITOR-TEXT v1.0.
 *   - Permite consultar sensores activos y su ultima medicion.
 *   - Recibe alertas en tiempo real que el servidor empuja (push) de forma
 *     asincrona sin que el operador las solicite.
 *
 * Protocolo utilizado (ver docs/02-protocolo/especificacion-protocolo.md):
 *   REGISTER OPERATOR <id>   → OK REGISTERED OPERATOR <id>
 *   GET_SENSORS              → SENSORS <n> <id>:<tipo> ...
 *   GET_LAST <sensor_id>     → LAST <id> <valor> <timestamp>
 *   PING                     → OK PONG   (heartbeat)
 *   (recibido del servidor)  ← ALERT <id> <tipo_alerta> <valor> <timestamp>
 *
 * Arquitectura interna:
 *   - Hilo principal: Swing EDT (Event Dispatch Thread) — maneja la GUI.
 *   - Hilo lector   : ConnectionReader — lee lineas del socket en background.
 *       · Si la linea empieza con "ALERT " → va a la cola de alertas.
 *       · Cualquier otra respuesta         → va a la cola de respuestas.
 *   - BlockingQueue<String> responseQueue : sincroniza solicitudes/respuestas.
 *   - BlockingQueue<String> alertQueue    : acumula alertas push del servidor.
 *
 * Compilacion:
 *   javac OperadorClient.java
 *
 * Ejecucion:
 *   java OperadorClient
 *   java OperadorClient --host iot-monitoring.example.com --port 9000
 */

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;

public class OperadorClient extends JFrame {

    // ─── Constantes del protocolo ────────────────────────────────────────────

    /** Prefijo que identifica un mensaje de alerta enviado por el servidor. */
    private static final String ALERT_PREFIX = "ALERT ";

    /** Prefijo de respuesta exitosa del servidor. */
    private static final String OK_PREFIX = "OK ";

    /** Prefijo de error del servidor. */
    private static final String ERROR_PREFIX = "ERROR ";

    /** Tiempo maximo (ms) esperando la respuesta del servidor a un comando. */
    private static final int RESPONSE_TIMEOUT_MS = 8_000;

    /** Intervalo (ms) entre cada envio de PING para mantener la conexion viva. */
    private static final int HEARTBEAT_INTERVAL_MS = 15_000;

    /** Longitud maxima de linea permitida por el protocolo (bytes). */
    private static final int MAX_LINE_LENGTH = 4096;

    // ─── Argumentos de conexion ───────────────────────────────────────────────

    /** Nombre del host del servidor. Resuelto via DNS, nunca IP hardcodeada. */
    private String serverHost;

    /** Puerto TCP del servidor. */
    private int serverPort;

    /** Identificador de este operador dentro del sistema. */
    private String operatorId;

    // ─── Estado de la conexion ────────────────────────────────────────────────

    /** Socket TCP activo. null si no hay conexion. */
    private volatile Socket socket = null;

    /** Writer para enviar lineas al servidor (UTF-8). */
    private volatile PrintWriter writer = null;

    /** Cola donde el hilo lector deposita las respuestas a comandos enviados. */
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    /** Cola donde el hilo lector deposita las alertas push del servidor. */
    private final BlockingQueue<String> alertQueue = new LinkedBlockingQueue<>();

    /** Referencia al hilo lector en segundo plano. */
    private volatile Thread readerThread = null;

    /** Hilo que consume alertas de la cola y las pinta en la GUI. */
    private volatile Thread alertConsumerThread = null;

    /** Hilo que envia PING periodico para mantener la sesion activa. */
    private volatile Thread heartbeatThread = null;

    /** Indica si la conexion esta activa y registrada. */
    private volatile boolean connected = false;

    // ─── Componentes de la GUI ────────────────────────────────────────────────

    /** Campo para ingresar el host del servidor. */
    private JTextField hostField;

    /** Campo para ingresar el puerto del servidor. */
    private JTextField portField;

    /** Campo para ingresar el ID del operador. */
    private JTextField operatorIdField;

    /** Boton de conexion / desconexion. */
    private JButton connectButton;

    /** Boton para actualizar la lista de sensores (GET_SENSORS). */
    private JButton refreshButton;

    /** Boton para consultar la ultima medicion del sensor seleccionado. */
    private JButton getLastButton;

    /** Tabla que muestra los sensores activos reportados por el servidor. */
    private JTable sensorsTable;

    /** Modelo de datos de la tabla de sensores. */
    private DefaultTableModel sensorsTableModel;

    /** Area de texto donde se imprimen las alertas recibidas del servidor. */
    private JTextArea alertsArea;

    /** Area de texto con el registro de eventos de la sesion (log local). */
    private JTextArea logArea;

    /** Etiqueta de estado de la conexion en la barra inferior. */
    private JLabel statusLabel;

    // ─── Formato de fecha local ───────────────────────────────────────────────

    /** Formateador para los timestamps que se imprimen en el log y alertas. */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // =========================================================================
    //  Constructor / inicializacion de la ventana
    // =========================================================================

    /**
     * Construye la ventana principal y configura todos los paneles de la GUI.
     *
     * @param defaultHost host sugerido en el campo de conexion al arrancar
     * @param defaultPort puerto sugerido en el campo de conexion al arrancar
     */
    public OperadorClient(String defaultHost, int defaultPort) {
        super("Operador IoT — Sistema de Monitoreo");

        // Icono del sistema
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Al cerrar la ventana, desconectar limpiamente antes de salir
                disconnect();
                dispose();
                System.exit(0);
            }
        });

        // ── Construccion de paneles ──────────────────────────────────────────
        JPanel mainPanel = new JPanel(new BorderLayout(6, 6));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        mainPanel.add(buildConnectionPanel(defaultHost, defaultPort), BorderLayout.NORTH);
        mainPanel.add(buildCenterPanel(), BorderLayout.CENTER);
        mainPanel.add(buildStatusBar(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
        setSize(900, 620);
        setLocationRelativeTo(null); // Centrar en pantalla

        // Estado inicial: desconectado
        setConnectedState(false);
    }

    // =========================================================================
    //  Paneles de la GUI
    // =========================================================================

    /**
     * Construye el panel superior de conexion con campos de host, puerto, ID
     * de operador y el boton de conectar/desconectar.
     */
    private JPanel buildConnectionPanel(String defaultHost, int defaultPort) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Conexion al servidor"));

        // Campo de host (resolucion DNS — no se permite IP directa por diseno)
        panel.add(new JLabel("Host:"));
        hostField = new JTextField(defaultHost, 22);
        hostField.setToolTipText("Nombre de dominio del servidor (ej: iot-monitoring.example.com)");
        panel.add(hostField);

        // Campo de puerto
        panel.add(new JLabel("Puerto:"));
        portField = new JTextField(String.valueOf(defaultPort), 6);
        panel.add(portField);

        // Campo de ID de operador
        panel.add(new JLabel("ID Operador:"));
        operatorIdField = new JTextField("operador_01", 14);
        operatorIdField.setToolTipText("Identificador del operador: [a-zA-Z0-9_-]{3,32}");
        panel.add(operatorIdField);

        // Boton de conexion / desconexion
        connectButton = new JButton("Conectar");
        connectButton.setPreferredSize(new Dimension(110, 28));
        connectButton.addActionListener(e -> onConnectButtonClicked());
        panel.add(connectButton);

        return panel;
    }

    /**
     * Construye el panel central dividido en tres secciones:
     *   - Izquierda : tabla de sensores + botones de consulta
     *   - Derecha (arriba): alertas en tiempo real
     *   - Derecha (abajo) : log de sesion
     */
    private JSplitPane buildCenterPanel() {
        // ── Panel izquierdo: sensores ─────────────────────────────────────────
        JPanel sensorsPanel = new JPanel(new BorderLayout(4, 4));
        sensorsPanel.setBorder(BorderFactory.createTitledBorder("Sensores activos"));

        // Columnas de la tabla
        String[] columns = {"ID Sensor", "Tipo"};
        sensorsTableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        sensorsTable = new JTable(sensorsTableModel);
        sensorsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sensorsTable.getTableHeader().setReorderingAllowed(false);
        sensorsTable.setRowHeight(22);

        // Ancho fijo de columnas
        sensorsTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        sensorsTable.getColumnModel().getColumn(1).setPreferredWidth(110);

        sensorsPanel.add(new JScrollPane(sensorsTable), BorderLayout.CENTER);

        // Botones de accion sobre sensores
        JPanel sensorButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        refreshButton = new JButton("Actualizar sensores");
        refreshButton.setToolTipText("Envia GET_SENSORS al servidor");
        refreshButton.addActionListener(e -> onRefreshSensors());
        sensorButtons.add(refreshButton);

        getLastButton = new JButton("Ultima medicion");
        getLastButton.setToolTipText("Envia GET_LAST <sensor_id> para el sensor seleccionado");
        getLastButton.addActionListener(e -> onGetLast());
        sensorButtons.add(getLastButton);

        sensorsPanel.add(sensorButtons, BorderLayout.SOUTH);
        sensorsPanel.setPreferredSize(new Dimension(300, 400));

        // ── Panel derecho: alertas + log ──────────────────────────────────────
        JPanel rightPanel = new JPanel(new GridLayout(2, 1, 0, 6));

        // Subpanel de alertas (mensajes push del servidor)
        JPanel alertsPanel = new JPanel(new BorderLayout());
        alertsPanel.setBorder(BorderFactory.createTitledBorder("⚠ Alertas en tiempo real"));
        alertsArea = new JTextArea();
        alertsArea.setEditable(false);
        alertsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        alertsArea.setBackground(new Color(255, 252, 230)); // Fondo amarillo suave
        alertsPanel.add(new JScrollPane(alertsArea), BorderLayout.CENTER);

        JButton clearAlertsButton = new JButton("Limpiar alertas");
        clearAlertsButton.addActionListener(e -> alertsArea.setText(""));
        JPanel alertButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        alertButtons.add(clearAlertsButton);
        alertsPanel.add(alertButtons, BorderLayout.SOUTH);
        rightPanel.add(alertsPanel);

        // Subpanel de log de sesion
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log de sesion"));
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setForeground(new Color(40, 40, 40));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        rightPanel.add(logPanel);

        // ── Split horizontal: sensores | alertas+log ──────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sensorsPanel, rightPanel);
        split.setDividerLocation(310);
        split.setResizeWeight(0.35);
        return split;
    }

    /**
     * Construye la barra de estado inferior con etiqueta de conexion.
     */
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.setBorder(new MatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        statusLabel = new JLabel("Desconectado");
        statusLabel.setForeground(Color.DARK_GRAY);
        bar.add(statusLabel);
        return bar;
    }

    // =========================================================================
    //  Logica de conexion / desconexion
    // =========================================================================

    /**
     * Maneja el clic en el boton Conectar / Desconectar.
     * Si estamos conectados, invoca {@link #disconnect()}.
     * Si estamos desconectados, lee los campos y llama {@link #connect()}.
     */
    private void onConnectButtonClicked() {
        if (connected) {
            disconnect();
            return;
        }

        // Leer y validar campos de la GUI
        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String opId = operatorIdField.getText().trim();

        if (host.isEmpty()) {
            showError("El campo Host no puede estar vacio.");
            return;
        }
        if (!opId.matches("[a-zA-Z0-9_-]{3,32}")) {
            showError("El ID de operador debe tener entre 3 y 32 caracteres [a-zA-Z0-9_-].");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showError("Puerto invalido. Ingrese un numero entre 1 y 65535.");
            return;
        }

        // Deshabilitar campos durante la conexion para evitar edicion concurrente
        setFieldsEditable(false);
        connectButton.setEnabled(false);
        logInfo("Conectando a " + host + ":" + port + " como operador '" + opId + "'...");

        // La conexion TCP puede tardar: ejecutar en hilo de fondo para no
        // bloquear el EDT (Event Dispatch Thread) de Swing.
        final String finalHost = host;
        final int finalPort = port;
        final String finalOpId = opId;
        new Thread(() -> doConnect(finalHost, finalPort, finalOpId), "connect-thread").start();
    }

    /**
     * Realiza la conexion TCP y el handshake de registro en un hilo de fondo.
     * Al terminar (exito o error) actualiza la GUI desde el EDT.
     *
     * @param host   nombre de dominio del servidor (resuelto via DNS)
     * @param port   puerto TCP del servidor
     * @param opId   identificador del operador a registrar
     */
    private void doConnect(String host, int port, String opId) {
        try {
            // ── Paso 1: Resolucion DNS ──────────────────────────────────────
            // Se usa el nombre de dominio; el SO resuelve la IP via DNS.
            // Nunca se pone la IP directamente en el codigo fuente.
            logInfo("Resolviendo DNS para: " + host);
            InetAddress address = InetAddress.getByName(host);
            logInfo("DNS resuelto: " + host + " → " + address.getHostAddress());

            // ── Paso 2: Conexion TCP ────────────────────────────────────────
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(address, port), 5_000); // timeout 5s
            newSocket.setSoTimeout(HEARTBEAT_INTERVAL_MS + 5_000); // timeout de lectura

            PrintWriter newWriter = new PrintWriter(
                    new OutputStreamWriter(newSocket.getOutputStream(), "UTF-8"), true);

            // ── Paso 3: REGISTER OPERATOR ────────────────────────────────────
            // Enviamos el comando de registro antes de exponer el socket,
            // para que el hilo lector no se active hasta que todo este listo.
            String registerCmd = "REGISTER OPERATOR " + opId;
            logInfo("TX: " + registerCmd);
            newWriter.println(registerCmd);

            // Leer respuesta directamente (sin hilo lector aun activo)
            BufferedReader tempReader = new BufferedReader(
                    new InputStreamReader(newSocket.getInputStream(), "UTF-8"));
            String registerResponse = tempReader.readLine();
            logInfo("RX: " + registerResponse);

            if (registerResponse == null) {
                throw new IOException("El servidor cerro la conexion antes de responder.");
            }
            String expectedRegister = "OK REGISTERED OPERATOR " + opId;
            if (!registerResponse.equals(expectedRegister)) {
                throw new IOException("Respuesta inesperada al registrar: " + registerResponse);
            }

            // ── Paso 4: Guardar estado y lanzar hilos de fondo ───────────────
            socket = newSocket;
            writer = newWriter;
            serverHost = host;
            serverPort = port;
            operatorId = opId;

            // Hilo lector: escucha el socket continuamente y clasifica mensajes
            startReaderThread(tempReader);

            // Hilo consumidor de alertas: saca alertas de la cola y actualiza GUI
            startAlertConsumerThread();

            // Hilo de heartbeat: envia PING periodico para evitar timeout del servidor
            startHeartbeatThread();

            // ── Paso 5: Actualizar GUI en el EDT ─────────────────────────────
            SwingUtilities.invokeLater(() -> {
                connected = true;
                setConnectedState(true);
                logInfo("Conexion establecida. Sesion activa como operador '" + opId + "'.");
                // Cargar lista de sensores automaticamente al conectar
                onRefreshSensors();
            });

        } catch (UnknownHostException ex) {
            // Error de DNS: el nombre no pudo resolverse
            SwingUtilities.invokeLater(() -> {
                setFieldsEditable(true);
                connectButton.setEnabled(true);
                logError("Error DNS: no se pudo resolver el host '" + host + "'. " + ex.getMessage());
                showError("No se pudo resolver el host:\n" + host + "\n\nVerifique el nombre de dominio y la conexion a Internet.");
            });
        } catch (IOException ex) {
            // Error de red o de protocolo durante la conexion
            SwingUtilities.invokeLater(() -> {
                setFieldsEditable(true);
                connectButton.setEnabled(true);
                logError("Error de conexion: " + ex.getMessage());
                showError("Error al conectar:\n" + ex.getMessage());
            });
        }
    }

    /**
     * Cierra la conexion activa de forma ordenada:
     *   1. Marca la conexion como inactiva (para que los hilos de fondo terminen).
     *   2. Cierra el socket (desbloquea los hilos que estaban en recv/send).
     *   3. Actualiza la GUI.
     */
    private void disconnect() {
        connected = false;

        // Interrumpir hilos de fondo
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (alertConsumerThread != null) {
            alertConsumerThread.interrupt();
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }

        // Cerrar socket (libera los bloqueos en recv)
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}

        socket = null;
        writer = null;

        // Actualizar GUI desde el EDT
        SwingUtilities.invokeLater(() -> {
            setConnectedState(false);
            logInfo("Sesion terminada.");
        });
    }

    // =========================================================================
    //  Hilos de fondo
    // =========================================================================

    /**
     * Lanza el hilo lector que consume lineas del socket y las clasifica:
     *   - Lineas "ALERT ..."  → alertQueue (mensajes push del servidor)
     *   - Cualquier otra      → responseQueue (respuesta a un comando enviado)
     *
     * @param reader BufferedReader ya abierto sobre el socket del servidor
     */
    private void startReaderThread(BufferedReader reader) {
        readerThread = new Thread(() -> {
            try {
                String line;
                // Leer lineas hasta que la conexion se cierre o se interrumpa el hilo
                while (!Thread.currentThread().isInterrupted() && connected) {
                    line = reader.readLine();

                    if (line == null) {
                        // El servidor cerro la conexion
                        break;
                    }

                    if (line.length() > MAX_LINE_LENGTH) {
                        // Ignorar lineas demasiado largas (violacion del protocolo)
                        logError("Linea demasiado larga descartada (" + line.length() + " bytes).");
                        continue;
                    }

                    if (line.startsWith(ALERT_PREFIX)) {
                        // Es un ALERT empujado por el servidor: va a la cola de alertas
                        alertQueue.put(line);
                    } else {
                        // Es la respuesta a un comando que enviamos: va a la cola de respuestas
                        responseQueue.put(line);
                    }
                }
            } catch (InterruptedException ex) {
                // El hilo fue interrumpido intencionalmente (p.ej. al desconectar)
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                if (connected) {
                    // Perdida inesperada de conexion
                    logError("Conexion perdida: " + ex.getMessage());
                }
            } finally {
                // Si llegamos aqui con la conexion marcada como activa, se perdio la sesion
                if (connected) {
                    connected = false;
                    SwingUtilities.invokeLater(() -> {
                        setConnectedState(false);
                        logError("La conexion con el servidor se cerro inesperadamente.");
                    });
                }
            }
        }, "socket-reader");

        readerThread.setDaemon(true); // Termina automaticamente cuando cierra la app
        readerThread.start();
    }

    /**
     * Lanza el hilo consumidor de alertas.
     * Saca mensajes de alertQueue y los muestra en la GUI.
     * Separado del hilo lector para no bloquear la lectura del socket
     * mientras se actualiza la GUI.
     */
    private void startAlertConsumerThread() {
        alertConsumerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // Esperar hasta 1 segundo por una nueva alerta
                    String alert = alertQueue.poll(1, TimeUnit.SECONDS);
                    if (alert != null) {
                        // Parsear: ALERT <sensor_id> <tipo_alerta> <valor> <timestamp>
                        String displayText = formatAlert(alert);
                        SwingUtilities.invokeLater(() -> appendAlert(displayText));
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "alert-consumer");

        alertConsumerThread.setDaemon(true);
        alertConsumerThread.start();
    }

    /**
     * Lanza el hilo de heartbeat que envia PING cada HEARTBEAT_INTERVAL_MS
     * para evitar que el servidor cierre la sesion por inactividad (timeout 30s).
     */
    private void startHeartbeatThread() {
        heartbeatThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && connected) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!connected) break;

                    // Enviar PING en hilo de fondo para no bloquear el EDT
                    String response = sendCommand("PING");
                    if (!"OK PONG".equals(response)) {
                        logError("Respuesta inesperada al PING: " + response);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "heartbeat");

        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    // =========================================================================
    //  Envio de comandos y procesamiento de respuestas
    // =========================================================================

    /**
     * Envia un comando de texto al servidor y espera la respuesta.
     * Metodo seguro para llamar desde cualquier hilo (NO desde el EDT).
     *
     * El envio es sincronico: escribe la linea y bloquea hasta recibir la
     * respuesta en responseQueue (con timeout RESPONSE_TIMEOUT_MS).
     *
     * @param command linea de texto del protocolo (sin '\n')
     * @return respuesta del servidor, o null si hay timeout o error
     */
    private String sendCommand(String command) {
        if (!connected || writer == null) {
            return null;
        }

        logInfo("TX: " + command);
        writer.println(command); // PrintWriter con autoFlush=true envia inmediatamente

        if (writer.checkError()) {
            logError("Error al enviar comando: " + command);
            return null;
        }

        try {
            // Esperar la respuesta con timeout para evitar bloqueo indefinido
            String response = responseQueue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (response == null) {
                logError("Timeout esperando respuesta para: " + command);
                return null;
            }
            logInfo("RX: " + response);
            return response;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // =========================================================================
    //  Acciones de la GUI
    // =========================================================================

    /**
     * Accion del boton "Actualizar sensores".
     * Envia GET_SENSORS en un hilo de fondo y actualiza la tabla al recibir
     * la respuesta.
     *
     * Formato de respuesta esperado:
     *   SENSORS <n> <id1>:<tipo1> <id2>:<tipo2> ...
     */
    private void onRefreshSensors() {
        if (!connected) return;

        refreshButton.setEnabled(false);
        new Thread(() -> {
            String response = sendCommand("GET_SENSORS");
            SwingUtilities.invokeLater(() -> {
                refreshButton.setEnabled(true);
                if (response == null) {
                    logError("No se recibio respuesta a GET_SENSORS.");
                    return;
                }
                if (response.startsWith(ERROR_PREFIX)) {
                    logError("Servidor respondio: " + response);
                    return;
                }
                parseSensorsResponse(response);
            });
        }, "get-sensors").start();
    }

    /**
     * Parsea la respuesta de GET_SENSORS y repuebla la tabla de sensores.
     *
     * Formato: SENSORS <n> <id1>:<tipo1> <id2>:<tipo2> ...
     *
     * @param response linea de respuesta del servidor
     */
    private void parseSensorsResponse(String response) {
        // Vaciar la tabla antes de cargar los nuevos datos
        sensorsTableModel.setRowCount(0);

        String[] tokens = response.split(" ");
        if (tokens.length < 2 || !tokens[0].equals("SENSORS")) {
            logError("Formato inesperado en respuesta de GET_SENSORS: " + response);
            return;
        }

        int count = 0;
        try {
            count = Integer.parseInt(tokens[1]);
        } catch (NumberFormatException ex) {
            logError("No se pudo parsear el conteo de sensores: " + tokens[1]);
            return;
        }

        // Agregar cada par id:tipo como fila de la tabla
        for (int i = 2; i < tokens.length; i++) {
            String pair = tokens[i];
            int colonIdx = pair.indexOf(':');
            if (colonIdx <= 0) continue;

            String sensorId = pair.substring(0, colonIdx);
            String sensorType = pair.substring(colonIdx + 1);
            sensorsTableModel.addRow(new Object[]{sensorId, sensorType});
        }

        logInfo("Sensores activos: " + count +
                (count > 0 ? " (" + sensorsTableModel.getRowCount() + " mostrados)" : ""));
    }

    /**
     * Accion del boton "Ultima medicion".
     * Envia GET_LAST <sensor_id> para el sensor seleccionado en la tabla.
     *
     * Formato de respuesta esperado:
     *   LAST <sensor_id> <valor> <timestamp>
     */
    private void onGetLast() {
        if (!connected) return;

        int selectedRow = sensorsTable.getSelectedRow();
        if (selectedRow < 0) {
            showError("Seleccione un sensor de la tabla primero.");
            return;
        }

        String sensorId = (String) sensorsTableModel.getValueAt(selectedRow, 0);
        getLastButton.setEnabled(false);

        new Thread(() -> {
            String response = sendCommand("GET_LAST " + sensorId);
            SwingUtilities.invokeLater(() -> {
                getLastButton.setEnabled(true);
                if (response == null) {
                    logError("No se recibio respuesta a GET_LAST " + sensorId);
                    return;
                }
                if (response.startsWith(ERROR_PREFIX)) {
                    logError("Servidor respondio: " + response);
                    return;
                }
                // Formato: LAST <sensor_id> <valor> <timestamp>
                String[] parts = response.split(" ", 4);
                if (parts.length >= 4 && parts[0].equals("LAST")) {
                    String msg = "Sensor: " + parts[1] +
                                 "  |  Valor: " + parts[2] +
                                 "  |  Timestamp: " + parts[3];
                    logInfo("Ultima medicion → " + msg);
                    JOptionPane.showMessageDialog(this, msg,
                            "Ultima medicion: " + parts[1],
                            JOptionPane.INFORMATION_MESSAGE);
                } else if (parts.length == 3 && parts[0].equals("LAST")) {
                    // Algunos builds pueden omitir timestamp si no hay datos
                    logInfo("Ultima medicion → Sensor: " + parts[1] + "  |  Valor: " + parts[2]);
                } else {
                    logError("Formato inesperado: " + response);
                }
            });
        }, "get-last").start();
    }

    // =========================================================================
    //  Utilidades de GUI
    // =========================================================================

    /**
     * Formatea un mensaje de alerta para mostrarlo en la GUI.
     * Formato de entrada: ALERT <sensor_id> <tipo_alerta> <valor> <timestamp>
     *
     * @param alert linea de alerta recibida del servidor
     * @return texto formateado para el area de alertas
     */
    private String formatAlert(String alert) {
        // Quitar el prefijo "ALERT " y dividir el resto
        String body = alert.substring(ALERT_PREFIX.length());
        String[] parts = body.split(" ", 4);

        String localTime = LocalDateTime.now().format(TS_FMT);
        if (parts.length >= 4) {
            return "[" + localTime + "] ⚠ " +
                   parts[0] + " | " + parts[1].replace("_", " ").toUpperCase() +
                   " | Valor: " + parts[2] + " | " + parts[3] + "\n";
        }
        // Formato de respaldo si la linea no tiene la cantidad esperada de partes
        return "[" + localTime + "] ⚠ " + body + "\n";
    }

    /**
     * Agrega una linea de alerta al area de alertas y hace scroll al final.
     * Debe llamarse desde el EDT.
     *
     * @param text texto a agregar
     */
    private void appendAlert(String text) {
        alertsArea.append(text);
        // Auto-scroll al final para que se vea la alerta mas reciente
        alertsArea.setCaretPosition(alertsArea.getDocument().getLength());
    }

    /**
     * Imprime un mensaje informativo en el log de sesion con timestamp.
     * Puede llamarse desde cualquier hilo.
     *
     * @param message texto del mensaje
     */
    private void logInfo(String message) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String line = "[" + ts + "] INFO  " + message + "\n";
        System.out.print(line); // Tambien a consola para depuracion
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Imprime un mensaje de error en el log de sesion con timestamp.
     * Puede llamarse desde cualquier hilo.
     *
     * @param message texto del error
     */
    private void logError(String message) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String line = "[" + ts + "] ERROR " + message + "\n";
        System.err.print(line);
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Muestra un dialogo de error al usuario.
     * Debe llamarse desde el EDT.
     *
     * @param message texto del error a mostrar
     */
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Actualiza todos los componentes de la GUI segun el estado de conexion.
     * Debe llamarse desde el EDT.
     *
     * @param isConnected true si hay conexion activa, false si no
     */
    private void setConnectedState(boolean isConnected) {
        connectButton.setEnabled(true);
        connectButton.setText(isConnected ? "Desconectar" : "Conectar");
        refreshButton.setEnabled(isConnected);
        getLastButton.setEnabled(isConnected);
        setFieldsEditable(!isConnected);

        if (isConnected) {
            statusLabel.setText("Conectado a " + serverHost + ":" + serverPort +
                                " como '" + operatorId + "'");
            statusLabel.setForeground(new Color(0, 128, 0)); // Verde
        } else {
            statusLabel.setText("Desconectado");
            statusLabel.setForeground(Color.DARK_GRAY);
            sensorsTableModel.setRowCount(0); // Limpiar tabla al desconectar
        }
    }

    /**
     * Habilita o deshabilita la edicion de los campos de conexion.
     *
     * @param editable true para habilitar edicion, false para bloquear
     */
    private void setFieldsEditable(boolean editable) {
        hostField.setEditable(editable);
        portField.setEditable(editable);
        operatorIdField.setEditable(editable);
    }

    // =========================================================================
    //  Punto de entrada
    // =========================================================================

    /**
     * Punto de entrada de la aplicacion.
     *
     * Argumentos opcionales:
     *   --host <hostname>   host del servidor (defecto: localhost)
     *   --port <puerto>     puerto TCP (defecto: 9000)
     *
     * La GUI se crea en el EDT de Swing (buena practica).
     *
     * @param args argumentos de linea de comandos
     */
    public static void main(String[] args) {
        // Valores por defecto
        String host = System.getenv().getOrDefault("IOT_SERVER_HOST", "localhost");
        int port = 9000;

        // Parsear argumentos --host y --port
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) {
                host = args[i + 1];
            } else if ("--port".equals(args[i])) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ex) {
                    System.err.println("Puerto invalido: " + args[i + 1] + ". Usando 9000.");
                }
            }
        }

        // Tambien leer la variable de entorno para el puerto
        String portEnv = System.getenv("IOT_SERVER_PORT");
        if (portEnv != null) {
            try { port = Integer.parseInt(portEnv); }
            catch (NumberFormatException ignored) {}
        }

        // Usar el look-and-feel del sistema operativo para una apariencia nativa
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Crear y mostrar la ventana en el EDT (obligatorio en Swing)
        final String finalHost = host;
        final int finalPort = port;
        SwingUtilities.invokeLater(() -> {
            OperadorClient client = new OperadorClient(finalHost, finalPort);
            client.setVisible(true);
        });
    }
}
