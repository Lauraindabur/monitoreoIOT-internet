import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableModel;

public class OperadorClient extends JFrame {

    private static final String ALERT_PREFIX = "ALERT ";
    private static final String ERROR_PREFIX = "ERROR ";
    private static final int RESPONSE_TIMEOUT_MS = 8_000;
    private static final int HEARTBEAT_INTERVAL_MS = 15_000;
    private static final int SUPERVISION_INTERVAL_MS = 10_000;
    private static final int MAX_LINE_LENGTH = 4096;
    private static final int MAX_RECENT_MEASUREMENTS = 80;
    private static final int MAX_ALERTS = 60;

    private static final Color PANEL_BG = new Color(248, 250, 252);
    private static final Color CARD_BORDER = new Color(209, 213, 219);
    private static final Color ACCENT = new Color(17, 94, 89);
    private static final Color WARNING = new Color(180, 83, 9);
    private static final Color DANGER = new Color(185, 28, 28);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String serverHost;
    private int serverPort;
    private String operatorId;

    private volatile Socket socket;
    private volatile PrintWriter writer;
    private volatile boolean connected = false;
    private volatile boolean refreshRunning = false;
    private volatile boolean singleQueryRunning = false;
    private volatile boolean actionCommandRunning = false;
    private volatile boolean superviseAllSensors = false;

    private volatile Thread readerThread;
    private volatile Thread alertConsumerThread;
    private volatile Thread heartbeatThread;
    private volatile Thread supervisionThread;

    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> alertQueue = new LinkedBlockingQueue<>();
    private final Object commandLock = new Object();
    private final Object sensorStateLock = new Object();

    private final Map<String, SensorSnapshot> sensorStates = new LinkedHashMap<>();
    private final Set<String> supervisedSensors = new LinkedHashSet<>();

    private JTextField hostField;
    private JTextField portField;
    private JTextField operatorIdField;
    private JButton connectButton;
    private JButton refreshButton;
    private JButton getLastButton;
    private JButton superviseSelectedButton;
    private JButton unsuperviseSelectedButton;
    private JButton toggleGlobalSupervisionButton;
    private JButton clearHistoryButton;
    private JButton clearAlertsButton;
    private JButton actionGetSensorsButton;
    private JButton actionPingButton;
    private JButton actionGetLastButton;
    private JTextField actionSensorIdField;

    private JTable sensorsTable;
    private DefaultTableModel sensorsTableModel;
    private JTable measurementsTable;
    private DefaultTableModel measurementsTableModel;
    private JTable alertsTable;
    private DefaultTableModel alertsTableModel;
    private JTable actionHistoryTable;
    private DefaultTableModel actionHistoryTableModel;
    private JTextArea logArea;

    private JLabel statusLabel;
    private JLabel activeSensorsValueLabel;
    private JLabel measurementsValueLabel;
    private JLabel alertsValueLabel;
    private JLabel supervisionValueLabel;
    private JLabel selectedSensorValueLabel;
    private JLabel selectedTypeValueLabel;
    private JLabel selectedStatusValueLabel;
    private JLabel selectedValueValueLabel;
    private JLabel selectedTimestampValueLabel;

    public OperadorClient(String defaultHost, int defaultPort) {
        super("Operador IoT - Centro de Supervision");

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect("Sesion terminada por el operador.");
                dispose();
                System.exit(0);
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        mainPanel.setBackground(PANEL_BG);

        mainPanel.add(buildConnectionPanel(defaultHost, defaultPort), BorderLayout.NORTH);
        mainPanel.add(buildDashboardPanel(), BorderLayout.CENTER);
        mainPanel.add(buildStatusBar(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1320, 820);
        setLocationRelativeTo(null);

        setConnectedState(false);
        updateMetrics();
        updateSelectedSensorDetails(null);
    }

    private JPanel buildConnectionPanel(String defaultHost, int defaultPort) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER),
                new EmptyBorder(12, 12, 12, 12)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel titleLabel = new JLabel("Conexion al servidor IoT");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 6;
        gbc.weightx = 1;
        panel.add(titleLabel, gbc);

        JLabel subtitleLabel = new JLabel(
                "Resolucion DNS obligatoria, sesiones concurrentes y recuperacion ante fallos de red.");
        subtitleLabel.setForeground(Color.DARK_GRAY);
        gbc.gridy = 1;
        panel.add(subtitleLabel, gbc);

        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.gridy = 2;

        panel.add(new JLabel("Host:"), withGrid(gbc, 0, 2, 1, 0));
        hostField = new JTextField(defaultHost, 24);
        hostField.setToolTipText("Nombre de dominio del servidor, por ejemplo iot-monitoring.example.com");
        panel.add(hostField, withGrid(gbc, 1, 2, 2, 1));

        panel.add(new JLabel("Puerto:"), withGrid(gbc, 3, 2, 1, 0));
        portField = new JTextField(String.valueOf(defaultPort), 7);
        panel.add(portField, withGrid(gbc, 4, 2, 1, 0));

        panel.add(new JLabel("ID operador:"), withGrid(gbc, 0, 3, 1, 0));
        operatorIdField = new JTextField("operador_01", 16);
        operatorIdField.setToolTipText("Formato permitido: [a-zA-Z0-9_-]{3,32}");
        panel.add(operatorIdField, withGrid(gbc, 1, 3, 2, 1));

        connectButton = new JButton("Conectar");
        connectButton.setPreferredSize(new Dimension(150, 32));
        connectButton.addActionListener(e -> onConnectButtonClicked());
        panel.add(connectButton, withGrid(gbc, 4, 3, 1, 0));

        return panel;
    }

    private GridBagConstraints withGrid(GridBagConstraints base, int x, int y, int width, double weightX) {
        GridBagConstraints copy = (GridBagConstraints) base.clone();
        copy.gridx = x;
        copy.gridy = y;
        copy.gridwidth = width;
        copy.weightx = weightX;
        return copy;
    }

    private JPanel buildDashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setOpaque(false);
        panel.add(buildMetricsPanel(), BorderLayout.NORTH);
        panel.add(buildCenterPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMetricsPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 10, 0));
        panel.setOpaque(false);

        activeSensorsValueLabel = buildMetricValueLabel();
        measurementsValueLabel = buildMetricValueLabel();
        alertsValueLabel = buildMetricValueLabel();
        supervisionValueLabel = buildMetricValueLabel();

        panel.add(createMetricCard(
                "Sensores activos",
                "Listado actual reportado por GET_SENSORS",
                activeSensorsValueLabel));
        panel.add(createMetricCard(
                "Mediciones recientes",
                "Historial generado con GET_LAST y alertas",
                measurementsValueLabel));
        panel.add(createMetricCard(
                "Alertas recibidas",
                "Eventos push enviados por el servidor",
                alertsValueLabel));
        panel.add(createMetricCard(
                "Supervision",
                "Sensores en seguimiento automatico",
                supervisionValueLabel));

        return panel;
    }

    private JLabel buildMetricValueLabel() {
        JLabel label = new JLabel("0");
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 28f));
        label.setForeground(ACCENT);
        return label;
    }

    private JPanel createMetricCard(String title, String subtitle, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(8, 8));
        card.setBackground(Color.WHITE);
        card.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER),
                new EmptyBorder(10, 12, 10, 12)));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));

        JLabel subtitleLabel = new JLabel("<html><body style='width:200px'>" + subtitle + "</body></html>");
        subtitleLabel.setForeground(Color.DARK_GRAY);

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        card.add(subtitleLabel, BorderLayout.SOUTH);
        return card;
    }

    private JSplitPane buildCenterPanel() {
        JPanel sensorsPanel = buildSensorsPanel();

        JSplitPane rightSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                buildMeasurementsPanel(),
                buildBottomTabs());
        rightSplit.setResizeWeight(0.58);
        rightSplit.setBorder(null);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sensorsPanel, rightSplit);
        splitPane.setResizeWeight(0.42);
        splitPane.setDividerLocation(480);
        splitPane.setBorder(null);
        return splitPane;
    }

    private JPanel buildSensorsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER),
                new EmptyBorder(10, 10, 10, 10)));

        JLabel sectionTitle = new JLabel("Sensores activos y supervision");
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(sectionTitle, BorderLayout.NORTH);

        sensorsTableModel = new DefaultTableModel(
                new String[]{"Sensor", "Tipo", "Estado", "Ultimo valor", "Timestamp", "Supervision"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        sensorsTable = new JTable(sensorsTableModel);
        sensorsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sensorsTable.setAutoCreateRowSorter(true);
        sensorsTable.setRowHeight(24);
        sensorsTable.getTableHeader().setReorderingAllowed(false);
        sensorsTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        sensorsTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        sensorsTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        sensorsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedSensorDetails(getSelectedSensorId());
                updateActionButtons();
            }
        });
        sensorsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onGetLast();
                }
            }
        });

        panel.add(new JScrollPane(sensorsTable), BorderLayout.CENTER);

        JPanel lowerPanel = new JPanel(new BorderLayout(8, 8));
        lowerPanel.setOpaque(false);
        lowerPanel.add(buildSelectedSensorPanel(), BorderLayout.CENTER);
        lowerPanel.add(buildActionPanel(), BorderLayout.SOUTH);
        panel.add(lowerPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildSelectedSensorPanel() {
        JPanel panel = new JPanel(new GridLayout(5, 2, 8, 6));
        panel.setBackground(new Color(249, 250, 251));
        panel.setBorder(new CompoundBorder(
                BorderFactory.createTitledBorder("Detalle del sensor seleccionado"),
                new EmptyBorder(8, 8, 8, 8)));

        selectedSensorValueLabel = new JLabel("-");
        selectedTypeValueLabel = new JLabel("-");
        selectedStatusValueLabel = new JLabel("-");
        selectedValueValueLabel = new JLabel("-");
        selectedTimestampValueLabel = new JLabel("-");

        panel.add(new JLabel("Sensor:"));
        panel.add(selectedSensorValueLabel);
        panel.add(new JLabel("Tipo:"));
        panel.add(selectedTypeValueLabel);
        panel.add(new JLabel("Estado:"));
        panel.add(selectedStatusValueLabel);
        panel.add(new JLabel("Ultimo valor:"));
        panel.add(selectedValueValueLabel);
        panel.add(new JLabel("Timestamp remoto:"));
        panel.add(selectedTimestampValueLabel);

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 3, 8, 8));
        panel.setOpaque(false);

        refreshButton = new JButton("Actualizar panel");
        refreshButton.setToolTipText("Consulta sensores activos y ultima medicion de todos los sensores");
        refreshButton.addActionListener(e -> runFullRefresh(false));
        panel.add(refreshButton);

        getLastButton = new JButton("Consultar medicion");
        getLastButton.setToolTipText("Ejecuta GET_LAST para el sensor seleccionado");
        getLastButton.addActionListener(e -> onGetLast());
        panel.add(getLastButton);

        superviseSelectedButton = new JButton("Supervisar sensor");
        superviseSelectedButton.setToolTipText("Agrega el sensor seleccionado al monitoreo automatico");
        superviseSelectedButton.addActionListener(e -> onSuperviseSelectedSensor());
        panel.add(superviseSelectedButton);

        unsuperviseSelectedButton = new JButton("Quitar supervision");
        unsuperviseSelectedButton.setToolTipText("Saca el sensor seleccionado del monitoreo automatico");
        unsuperviseSelectedButton.addActionListener(e -> onRemoveSupervisionFromSelectedSensor());
        panel.add(unsuperviseSelectedButton);

        toggleGlobalSupervisionButton = new JButton("Supervisar todos");
        toggleGlobalSupervisionButton.setToolTipText("Activa o pausa la supervision periodica de todos los sensores");
        toggleGlobalSupervisionButton.addActionListener(e -> onToggleGlobalSupervision());
        panel.add(toggleGlobalSupervisionButton);

        clearHistoryButton = new JButton("Limpiar historial");
        clearHistoryButton.setToolTipText("Borra la tabla local de mediciones recientes");
        clearHistoryButton.addActionListener(e -> clearMeasurementHistory());
        panel.add(clearHistoryButton);

        return panel;
    }

    private JPanel buildMeasurementsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER),
                new EmptyBorder(10, 10, 10, 10)));

        JLabel sectionTitle = new JLabel("Mediciones recientes");
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(sectionTitle, BorderLayout.NORTH);

        measurementsTableModel = new DefaultTableModel(
                new String[]{"Hora local", "Sensor", "Tipo", "Valor", "Timestamp remoto", "Origen"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        measurementsTable = new JTable(measurementsTableModel);
        measurementsTable.setAutoCreateRowSorter(true);
        measurementsTable.setRowHeight(23);
        measurementsTable.getTableHeader().setReorderingAllowed(false);
        measurementsTable.getColumnModel().getColumn(0).setPreferredWidth(135);
        measurementsTable.getColumnModel().getColumn(1).setPreferredWidth(95);
        measurementsTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        measurementsTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        measurementsTable.getColumnModel().getColumn(4).setPreferredWidth(170);
        measurementsTable.getColumnModel().getColumn(5).setPreferredWidth(95);

        panel.add(new JScrollPane(measurementsTable), BorderLayout.CENTER);

        JLabel helperLabel = new JLabel(
                "Se registran consultas manuales, refrescos del panel, supervision automatica y alertas.");
        helperLabel.setForeground(Color.DARK_GRAY);
        panel.add(helperLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JTabbedPane buildBottomTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Acciones", buildActionsPanel());
        tabs.addTab("Alertas", buildAlertsPanel());
        tabs.addTab("Log de sesion", buildLogPanel());
        return tabs;
    }

    private JPanel buildActionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel titleLabel = new JLabel("Acciones (comandos directos al servidor)");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(titleLabel, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridBagLayout());
        controls.setOpaque(false);
        controls.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER),
                new EmptyBorder(10, 10, 10, 10)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        controls.add(new JLabel("sensor_id (para GET_LAST):"), withGrid(gbc, 0, 0, 1, 0));
        actionSensorIdField = new JTextField("", 18);
        actionSensorIdField.setToolTipText("Ej: temp_01 (si esta vacio se usa el sensor seleccionado en la tabla)");
        controls.add(actionSensorIdField, withGrid(gbc, 1, 0, 2, 1));

        actionGetSensorsButton = new JButton("GET_SENSORS");
        actionGetSensorsButton.setToolTipText("Lista sensores activos (respuesta SENSORS ...)");
        actionGetSensorsButton.addActionListener(e -> runActionCommandAsync("GET_SENSORS"));
        controls.add(actionGetSensorsButton, withGrid(gbc, 0, 1, 1, 0));

        actionPingButton = new JButton("PING");
        actionPingButton.setToolTipText("Heartbeat manual (respuesta OK PONG)");
        actionPingButton.addActionListener(e -> runActionCommandAsync("PING"));
        controls.add(actionPingButton, withGrid(gbc, 1, 1, 1, 0));

        actionGetLastButton = new JButton("GET_LAST");
        actionGetLastButton.setToolTipText("Consulta la ultima medicion (respuesta LAST ... o ERROR ...)");
        actionGetLastButton.addActionListener(e -> onActionGetLastClicked());
        controls.add(actionGetLastButton, withGrid(gbc, 2, 1, 1, 0));

        JLabel hint = new JLabel(
                "<html><body style='width:700px'>"
                        + "Esta zona permite validar directamente el protocolo desde la GUI. "
                        + "Cada comando se ejecuta en background y se muestra la respuesta textual del servidor."
                        + "</body></html>");
        hint.setForeground(Color.DARK_GRAY);
        controls.add(hint, withGrid(gbc, 0, 2, 3, 1));

        panel.add(controls, BorderLayout.CENTER);

        actionHistoryTableModel = new DefaultTableModel(
                new String[]{"Hora local", "Comando", "Respuesta del servidor"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        actionHistoryTable = new JTable(actionHistoryTableModel);
        actionHistoryTable.setAutoCreateRowSorter(true);
        actionHistoryTable.setRowHeight(23);
        actionHistoryTable.getTableHeader().setReorderingAllowed(false);
        actionHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        actionHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        actionHistoryTable.getColumnModel().getColumn(2).setPreferredWidth(520);

        JPanel historyPanel = new JPanel(new BorderLayout(6, 6));
        historyPanel.setOpaque(false);
        historyPanel.setBorder(BorderFactory.createTitledBorder("Historial de acciones"));
        historyPanel.add(new JScrollPane(actionHistoryTable), BorderLayout.CENTER);

        JButton clearHistory = new JButton("Limpiar historial de acciones");
        clearHistory.addActionListener(e -> {
            actionHistoryTableModel.setRowCount(0);
            updateActionButtons();
        });
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        footer.setOpaque(false);
        footer.add(clearHistory);
        historyPanel.add(footer, BorderLayout.SOUTH);

        panel.add(historyPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAlertsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel titleLabel = new JLabel("Alertas en tiempo real");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        header.add(titleLabel, BorderLayout.WEST);

        clearAlertsButton = new JButton("Limpiar alertas");
        clearAlertsButton.addActionListener(e -> clearAlerts());
        header.add(clearAlertsButton, BorderLayout.EAST);

        panel.add(header, BorderLayout.NORTH);

        alertsTableModel = new DefaultTableModel(
                new String[]{"Recibida", "Sensor", "Alerta", "Valor", "Timestamp remoto"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        alertsTable = new JTable(alertsTableModel);
        alertsTable.setAutoCreateRowSorter(true);
        alertsTable.setRowHeight(23);
        alertsTable.getTableHeader().setReorderingAllowed(false);
        alertsTable.getColumnModel().getColumn(0).setPreferredWidth(135);
        alertsTable.getColumnModel().getColumn(1).setPreferredWidth(85);
        alertsTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        alertsTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        alertsTable.getColumnModel().getColumn(4).setPreferredWidth(170);

        panel.add(new JScrollPane(alertsTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, CARD_BORDER),
                new EmptyBorder(6, 8, 6, 8)));

        statusLabel = new JLabel("Desconectado");
        statusLabel.setForeground(Color.DARK_GRAY);
        panel.add(statusLabel, BorderLayout.WEST);

        JLabel noteLabel = new JLabel("Sin usuarios locales en el servidor IoT | DNS activo | Tolerante a errores");
        noteLabel.setForeground(Color.DARK_GRAY);
        panel.add(noteLabel, BorderLayout.EAST);

        return panel;
    }

    private void onConnectButtonClicked() {
        if (connected) {
            disconnect("Sesion terminada por el operador.");
            return;
        }

        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String opId = operatorIdField.getText().trim();

        if (host.isEmpty()) {
            showError("El host no puede estar vacio.");
            return;
        }
        if (!opId.matches("[a-zA-Z0-9_-]{3,32}")) {
            showError("El ID del operador debe tener entre 3 y 32 caracteres [a-zA-Z0-9_-].");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            showError("Puerto invalido. Debe ser un numero entre 1 y 65535.");
            return;
        }

        setFieldsEditable(false);
        connectButton.setEnabled(false);
        logInfo("Conectando a " + host + ":" + port + " como '" + opId + "'.");

        final String finalHost = host;
        final int finalPort = port;
        final String finalOpId = opId;
        Thread connectThread = new Thread(
                () -> doConnect(finalHost, finalPort, finalOpId),
                "connect-thread");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void doConnect(String host, int port, String opId) {
        try {
            logInfo("Resolviendo nombre del servidor: " + host);
            InetAddress address = InetAddress.getByName(host);
            logInfo("DNS resuelto: " + host + " -> " + address.getHostAddress());

            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(address, port), 5_000);
            newSocket.setSoTimeout(HEARTBEAT_INTERVAL_MS + 5_000);

            PrintWriter newWriter = new PrintWriter(
                    new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8),
                    true);
            BufferedReader tempReader = new BufferedReader(
                    new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));

            String registerCommand = "REGISTER OPERATOR " + opId;
            logInfo("TX: " + registerCommand);
            newWriter.println(registerCommand);

            String registerResponse = tempReader.readLine();
            if (registerResponse == null) {
                throw new IOException("El servidor cerro la conexion antes de registrar al operador.");
            }
            logInfo("RX: " + registerResponse);

            String expected = "OK REGISTERED OPERATOR " + opId;
            if (!expected.equals(registerResponse)) {
                throw new IOException("Respuesta inesperada al registrar el operador: " + registerResponse);
            }

            socket = newSocket;
            writer = newWriter;
            serverHost = host;
            serverPort = port;
            operatorId = opId;
            responseQueue.clear();
            alertQueue.clear();
            connected = true;

            startReaderThread(tempReader);
            startAlertConsumerThread();
            startHeartbeatThread();
            startSupervisionThread();

            SwingUtilities.invokeLater(() -> {
                setConnectedState(true);
                logInfo("Sesion conectada. El panel inicial se sincronizara ahora.");
                runFullRefresh(true);
            });
        } catch (UnknownHostException ex) {
            SwingUtilities.invokeLater(() -> {
                setFieldsEditable(true);
                connectButton.setEnabled(true);
                logError("No fue posible resolver el host '" + host + "': " + ex.getMessage());
                showError("No se pudo resolver el host:\n" + host + "\n\nVerifique el nombre de dominio.");
            });
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() -> {
                setFieldsEditable(true);
                connectButton.setEnabled(true);
                logError("Error de conexion: " + ex.getMessage());
                showError("No fue posible establecer la conexion:\n" + ex.getMessage());
            });
        }
    }

    private void disconnect(String message) {
        connected = false;

        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (alertConsumerThread != null) {
            alertConsumerThread.interrupt();
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        if (supervisionThread != null) {
            supervisionThread.interrupt();
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        socket = null;
        writer = null;
        responseQueue.clear();
        alertQueue.clear();
        refreshRunning = false;
        singleQueryRunning = false;

        synchronized (sensorStateLock) {
            sensorStates.clear();
            supervisedSensors.clear();
            superviseAllSensors = false;
        }

        SwingUtilities.invokeLater(() -> {
            setConnectedState(false);
            sensorsTableModel.setRowCount(0);
            updateSelectedSensorDetails(null);
            updateMetrics();
            if (message != null && !message.isEmpty()) {
                logInfo(message);
            }
        });
    }

    private void handleConnectionLoss(String message) {
        if (!connected && socket == null) {
            return;
        }

        connected = false;

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        socket = null;
        writer = null;
        refreshRunning = false;
        singleQueryRunning = false;

        synchronized (sensorStateLock) {
            sensorStates.clear();
            supervisedSensors.clear();
            superviseAllSensors = false;
        }

        SwingUtilities.invokeLater(() -> {
            setConnectedState(false);
            sensorsTableModel.setRowCount(0);
            updateSelectedSensorDetails(null);
            updateMetrics();
            logError(message);
        });
    }

    private void startReaderThread(BufferedReader reader) {
        readerThread = new Thread(() -> {
            String failureMessage = null;
            try {
                while (!Thread.currentThread().isInterrupted() && connected) {
                    String line = reader.readLine();
                    if (line == null) {
                        failureMessage = "La conexion con el servidor se cerro inesperadamente.";
                        break;
                    }
                    if (line.length() > MAX_LINE_LENGTH) {
                        logError("Linea demasiado larga descartada (" + line.length() + " bytes).");
                        continue;
                    }

                    if (line.startsWith(ALERT_PREFIX)) {
                        alertQueue.put(line);
                    } else {
                        responseQueue.put(line);
                    }
                }
            } catch (SocketTimeoutException ex) {
                if (connected) {
                    failureMessage = "Timeout de lectura en la conexion con el servidor.";
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                if (connected) {
                    failureMessage = "Conexion perdida: " + ex.getMessage();
                }
            } finally {
                if (failureMessage != null) {
                    handleConnectionLoss(failureMessage);
                }
            }
        }, "socket-reader");

        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void startAlertConsumerThread() {
        alertConsumerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String alertLine = alertQueue.poll(1, TimeUnit.SECONDS);
                    if (alertLine == null) {
                        continue;
                    }
                    try {
                        AlertPayload payload = parseAlert(alertLine);
                        SwingUtilities.invokeLater(() -> applyAlertPayload(payload));
                    } catch (IllegalArgumentException ex) {
                        logError("No fue posible interpretar una alerta recibida: " + ex.getMessage());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "alert-consumer");

        alertConsumerThread.setDaemon(true);
        alertConsumerThread.start();
    }

    private void startHeartbeatThread() {
        heartbeatThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && connected) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!connected) {
                        break;
                    }
                    String response = sendCommand("PING");
                    if (response == null) {
                        continue;
                    }
                    if (!"OK PONG".equals(response)) {
                        logError("Respuesta inesperada al heartbeat: " + response);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "heartbeat");

        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void startSupervisionThread() {
        supervisionThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(SUPERVISION_INTERVAL_MS);
                    if (!connected) {
                        break;
                    }
                    if (!isAutomaticSupervisionEnabled()) {
                        continue;
                    }

                    SnapshotData snapshot = fetchSnapshot(
                            isSuperviseAllEnabled(),
                            getSupervisedSensorsSnapshot(),
                            "SUPERVISION");

                    if (snapshot != null) {
                        SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "supervision");

        supervisionThread.setDaemon(true);
        supervisionThread.start();
    }

    private boolean isAutomaticSupervisionEnabled() {
        synchronized (sensorStateLock) {
            return superviseAllSensors || !supervisedSensors.isEmpty();
        }
    }

    private boolean isSuperviseAllEnabled() {
        synchronized (sensorStateLock) {
            return superviseAllSensors;
        }
    }

    private Set<String> getSupervisedSensorsSnapshot() {
        synchronized (sensorStateLock) {
            return new LinkedHashSet<>(supervisedSensors);
        }
    }

    private String sendCommand(String command) {
        synchronized (commandLock) {
            if (!connected || writer == null) {
                return null;
            }

            discardStaleResponses(command);

            logInfo("TX: " + command);
            writer.println(command);
            if (writer.checkError()) {
                handleConnectionLoss("Error de escritura al enviar el comando: " + command);
                return null;
            }

            try {
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
    }

    private void discardStaleResponses(String nextCommand) {
        ArrayList<String> staleResponses = new ArrayList<>();
        String line;
        while ((line = responseQueue.poll()) != null) {
            staleResponses.add(line);
        }

        if (!staleResponses.isEmpty()) {
            logError("Se descartaron respuestas atrasadas antes de enviar " + nextCommand + ": " + staleResponses);
        }
    }

    private void runFullRefresh(boolean silent) {
        if (!connected || refreshRunning) {
            return;
        }

        refreshRunning = true;
        SwingUtilities.invokeLater(this::updateActionButtons);

        if (!silent) {
            logInfo("Actualizando sensores activos y mediciones recientes.");
        }

        Thread refreshThread = new Thread(() -> {
            try {
                SnapshotData snapshot = fetchSnapshot(true, new LinkedHashSet<>(), "REFRESCO");
                if (snapshot != null) {
                    SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
                }
            } finally {
                refreshRunning = false;
                SwingUtilities.invokeLater(this::updateActionButtons);
            }
        }, silent ? "refresh-silent" : "refresh-manual");

        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private SnapshotData fetchSnapshot(boolean includeAllMeasurements, Set<String> targetedSensors, String origin) {
        if (!connected) {
            return null;
        }

        String response = sendCommand("GET_SENSORS");
        if (response == null) {
            return null;
        }
        if (response.startsWith(ERROR_PREFIX)) {
            logError("El servidor rechazo GET_SENSORS: " + response);
            return null;
        }

        SnapshotData snapshot;
        try {
            snapshot = parseSensorsResponse(response);
        } catch (IllegalArgumentException ex) {
            logError(ex.getMessage());
            return null;
        }
        Set<String> activeSensorIds = new LinkedHashSet<>();
        for (SensorDescriptor descriptor : snapshot.sensors) {
            activeSensorIds.add(descriptor.sensorId);
        }

        ArrayList<String> sensorsToMeasure = new ArrayList<>();
        if (includeAllMeasurements) {
            sensorsToMeasure.addAll(activeSensorIds);
        } else {
            for (String sensorId : targetedSensors) {
                if (activeSensorIds.contains(sensorId)) {
                    sensorsToMeasure.add(sensorId);
                }
            }
        }

        for (String sensorId : sensorsToMeasure) {
            String sensorType = findSensorType(snapshot.sensors, sensorId);
            MeasurementResult measurement = fetchLastMeasurement(sensorId, sensorType, origin);
            if (measurement != null) {
                snapshot.measurements.add(measurement);
            }
        }

        return snapshot;
    }

    private String findSensorType(ArrayList<SensorDescriptor> sensors, String sensorId) {
        for (SensorDescriptor descriptor : sensors) {
            if (descriptor.sensorId.equals(sensorId)) {
                return descriptor.sensorType;
            }
        }
        return "";
    }

    private SnapshotData parseSensorsResponse(String response) {
        SnapshotData snapshot = new SnapshotData();
        String[] tokens = response.split(" ");
        if (tokens.length < 2 || !"SENSORS".equals(tokens[0])) {
            throw new IllegalArgumentException("Respuesta invalida para GET_SENSORS: " + response);
        }

        for (int i = 2; i < tokens.length; i++) {
            int colon = tokens[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String sensorId = tokens[i].substring(0, colon);
            String sensorType = tokens[i].substring(colon + 1);
            snapshot.sensors.add(new SensorDescriptor(sensorId, sensorType));
        }

        snapshot.sensors.sort(Comparator.comparing(descriptor -> descriptor.sensorId));
        return snapshot;
    }

    private MeasurementResult fetchLastMeasurement(String sensorId, String sensorType, String origin) {
        String response = sendCommand("GET_LAST " + sensorId);
        if (response == null) {
            return null;
        }
        if (response.startsWith("ERROR 404 NO_DATA")) {
            logInfo("El sensor " + sensorId + " aun no reporta mediciones.");
            return MeasurementResult.noData(sensorId, sensorType, origin);
        }
        if (response.startsWith("ERROR 404 SENSOR_NOT_FOUND")) {
            logError("El sensor " + sensorId + " ya no esta disponible: " + response);
            return MeasurementResult.noData(sensorId, sensorType, origin);
        }
        if (response.startsWith(ERROR_PREFIX)) {
            logError("GET_LAST " + sensorId + " respondio con error: " + response);
            return null;
        }

        String[] parts = response.split(" ", 4);
        if (parts.length < 4 || !"LAST".equals(parts[0])) {
            logError("Respuesta invalida para GET_LAST " + sensorId + ": " + response);
            return null;
        }

        Double numericValue = parseNumericValue(parts[2]);
        String remoteTimestamp = parts[3];
        return MeasurementResult.withData(
                parts[1],
                sensorType,
                parts[2],
                numericValue,
                remoteTimestamp,
                origin);
    }

    private Double parseNumericValue(String valueText) {
        try {
            return Double.parseDouble(valueText);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void onGetLast() {
        if (!connected || singleQueryRunning) {
            return;
        }

        String sensorId = getSelectedSensorId();
        if (sensorId == null) {
            showError("Seleccione un sensor primero.");
            return;
        }

        singleQueryRunning = true;
        updateActionButtons();

        Thread queryThread = new Thread(() -> {
            try {
                String sensorType = getSensorType(sensorId);
                MeasurementResult result = fetchLastMeasurement(sensorId, sensorType, "MANUAL");
                SwingUtilities.invokeLater(() -> {
                    if (result == null) {
                        logError("No se pudo consultar la ultima medicion de " + sensorId + ".");
                        return;
                    }

                    applyMeasurementResult(result, true);
                    if (result.hasData) {
                        String message = "Sensor: " + result.sensorId +
                                "\nTipo: " + safeText(result.sensorType) +
                                "\nValor: " + result.valueText +
                                "\nTimestamp remoto: " + result.remoteTimestamp;
                        JOptionPane.showMessageDialog(
                                this,
                                message,
                                "Ultima medicion",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                this,
                                "El sensor seleccionado aun no tiene mediciones recientes.",
                                "Sin datos",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            } finally {
                singleQueryRunning = false;
                SwingUtilities.invokeLater(this::updateActionButtons);
            }
        }, "manual-get-last");

        queryThread.setDaemon(true);
        queryThread.start();
    }

    private void onActionGetLastClicked() {
        if (!connected) {
            return;
        }

        String sensorId = actionSensorIdField == null ? "" : safeText(actionSensorIdField.getText()).trim();
        if (sensorId.isEmpty()) {
            sensorId = getSelectedSensorId();
        }
        if (sensorId == null || sensorId.isEmpty()) {
            showError("Indique un sensor_id en el campo o seleccione un sensor en la tabla.");
            return;
        }
        if (!sensorId.matches("[a-zA-Z0-9_-]{3,32}")) {
            showError("sensor_id invalido. Formato permitido: [a-zA-Z0-9_-]{3,32}");
            return;
        }

        runActionCommandAsync("GET_LAST " + sensorId);
    }

    private void runActionCommandAsync(String command) {
        if (!connected || actionCommandRunning) {
            return;
        }

        actionCommandRunning = true;
        SwingUtilities.invokeLater(this::updateActionButtons);

        Thread actionThread = new Thread(() -> {
            String response = sendCommand(command);
            if (response == null) {
                response = "(sin respuesta: timeout o error de red)";
            }
            final String finalResponse = response;

            SwingUtilities.invokeLater(() -> {
                if (actionHistoryTableModel != null) {
                    actionHistoryTableModel.insertRow(0, new Object[]{
                            LocalDateTime.now().format(TS_FMT),
                            command,
                            finalResponse
                    });
                    trimTableRows(actionHistoryTableModel, 120);
                }
                actionCommandRunning = false;
                updateActionButtons();
            });
        }, "action-command");

        actionThread.setDaemon(true);
        actionThread.start();
    }

    private void onSuperviseSelectedSensor() {
        String sensorId = getSelectedSensorId();
        if (sensorId == null) {
            showError("Seleccione un sensor para activarle la supervision.");
            return;
        }

        synchronized (sensorStateLock) {
            supervisedSensors.add(sensorId);
        }

        logInfo("Supervision individual activada para " + sensorId + ".");
        refreshSensorsTable(sensorId);
        runTargetedSupervisionCycle(sensorId);
    }

    private void onRemoveSupervisionFromSelectedSensor() {
        String sensorId = getSelectedSensorId();
        if (sensorId == null) {
            showError("Seleccione un sensor para quitarle la supervision.");
            return;
        }

        synchronized (sensorStateLock) {
            supervisedSensors.remove(sensorId);
        }

        logInfo("Supervision individual desactivada para " + sensorId + ".");
        refreshSensorsTable(sensorId);
    }

    private void onToggleGlobalSupervision() {
        boolean enabled;
        synchronized (sensorStateLock) {
            superviseAllSensors = !superviseAllSensors;
            enabled = superviseAllSensors;
        }

        if (enabled) {
            logInfo("Supervision global activada para todos los sensores activos.");
            runFullRefresh(true);
        } else {
            logInfo("Supervision global pausada.");
        }

        refreshSensorsTable(getSelectedSensorId());
    }

    private void runTargetedSupervisionCycle(String sensorId) {
        if (!connected) {
            return;
        }

        Thread thread = new Thread(() -> {
            Set<String> targets = new LinkedHashSet<>();
            targets.add(sensorId);
            SnapshotData snapshot = fetchSnapshot(false, targets, "SUPERVISION");
            if (snapshot != null) {
                SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
            }
        }, "targeted-supervision");

        thread.setDaemon(true);
        thread.start();
    }

    private void applySnapshot(SnapshotData snapshot) {
        String selectedSensorId = getSelectedSensorId();
        Set<String> activeSensorIds = new LinkedHashSet<>();

        synchronized (sensorStateLock) {
            for (SensorDescriptor descriptor : snapshot.sensors) {
                activeSensorIds.add(descriptor.sensorId);
                SensorSnapshot state = sensorStates.get(descriptor.sensorId);
                if (state == null) {
                    sensorStates.put(descriptor.sensorId, new SensorSnapshot(descriptor.sensorId, descriptor.sensorType));
                } else {
                    state.sensorType = descriptor.sensorType;
                }
            }

            sensorStates.keySet().retainAll(activeSensorIds);
            supervisedSensors.retainAll(activeSensorIds);
        }

        for (MeasurementResult result : snapshot.measurements) {
            applyMeasurementResult(result, false);
        }

        logInfo("Panel actualizado: " + snapshot.sensors.size() + " sensores activos.");
        refreshSensorsTable(selectedSensorId);
    }

    private void applyMeasurementResult(MeasurementResult result, boolean showDialogInDetails) {
        boolean addToHistory = result.hasData;
        String selectedSensorId = getSelectedSensorId();

        synchronized (sensorStateLock) {
            SensorSnapshot state = sensorStates.get(result.sensorId);
            if (state == null) {
                sensorStates.put(result.sensorId, new SensorSnapshot(result.sensorId, result.sensorType));
                state = sensorStates.get(result.sensorId);
            }

            if (!safeText(result.sensorType).isEmpty()) {
                state.sensorType = result.sensorType;
            }

            if (result.hasData) {
                boolean changed = !result.remoteTimestamp.equals(state.lastTimestamp)
                        || !result.valueText.equals(state.lastValueText);

                state.lastValueText = result.valueText;
                state.lastTimestamp = result.remoteTimestamp;
                state.statusLabel = determineStatusLabel(state.sensorType, result.numericValue);

                addToHistory = changed || "ALERTA".equals(result.origin);
            } else if (state.lastTimestamp.isEmpty()) {
                state.lastValueText = "Sin datos";
                state.statusLabel = "Sin datos";
            }
        }

        if (addToHistory) {
            insertMeasurementRow(result);
        }

        refreshSensorsTable(selectedSensorId);

        if (showDialogInDetails) {
            updateSelectedSensorDetails(result.sensorId);
        }
    }

    private void applyAlertPayload(AlertPayload payload) {
        alertsTableModel.insertRow(0, new Object[]{
                payload.receivedAt,
                payload.sensorId,
                payload.alertLabel,
                payload.valueText,
                payload.remoteTimestamp
        });
        trimTableRows(alertsTableModel, MAX_ALERTS);

        String sensorType = getSensorType(payload.sensorId);
        if (sensorType.isEmpty()) {
            sensorType = inferSensorTypeFromAlert(payload.alertCode);
        }

        MeasurementResult alertMeasurement = MeasurementResult.withData(
                payload.sensorId,
                sensorType,
                payload.valueText,
                payload.numericValue,
                payload.remoteTimestamp,
                "ALERTA");

        synchronized (sensorStateLock) {
            SensorSnapshot state = sensorStates.get(payload.sensorId);
            if (state == null) {
                sensorStates.put(payload.sensorId, new SensorSnapshot(payload.sensorId, sensorType));
                state = sensorStates.get(payload.sensorId);
            }
            state.alertCount++;
        }

        applyMeasurementResult(alertMeasurement, false);
        logInfo("Alerta recibida: " + payload.sensorId + " -> " + payload.alertLabel + ".");
        updateMetrics();
        updateActionButtons();
    }

    private AlertPayload parseAlert(String alertLine) {
        String[] parts = alertLine.split(" ", 5);
        if (parts.length < 5 || !"ALERT".equals(parts[0])) {
            throw new IllegalArgumentException(alertLine);
        }

        Double numericValue = parseNumericValue(parts[3]);
        return new AlertPayload(
                parts[1],
                parts[2],
                formatAlertLabel(parts[2]),
                parts[3],
                numericValue,
                parts[4],
                LocalDateTime.now().format(TS_FMT));
    }

    private void clearMeasurementHistory() {
        measurementsTableModel.setRowCount(0);
        updateMetrics();
        updateActionButtons();
        logInfo("Historial local de mediciones limpiado.");
    }

    private void clearAlerts() {
        alertsTableModel.setRowCount(0);
        synchronized (sensorStateLock) {
            for (SensorSnapshot snapshot : sensorStates.values()) {
                snapshot.alertCount = 0;
            }
        }
        refreshSensorsTable(getSelectedSensorId());
        logInfo("Alertas locales limpiadas.");
    }

    private void insertMeasurementRow(MeasurementResult result) {
        measurementsTableModel.insertRow(0, new Object[]{
                result.recordedAt,
                result.sensorId,
                safeText(result.sensorType),
                result.valueText,
                result.remoteTimestamp,
                result.origin
        });
        trimTableRows(measurementsTableModel, MAX_RECENT_MEASUREMENTS);
        updateMetrics();
        updateActionButtons();
    }

    private void trimTableRows(DefaultTableModel model, int maxRows) {
        while (model.getRowCount() > maxRows) {
            model.removeRow(model.getRowCount() - 1);
        }
    }

    private void refreshSensorsTable(String preferredSelection) {
        ArrayList<SensorSnapshot> snapshots;
        Set<String> supervisedCopy;
        boolean globalMode;

        synchronized (sensorStateLock) {
            snapshots = new ArrayList<>(sensorStates.values());
            snapshots.sort(Comparator.comparing(snapshot -> snapshot.sensorId));
            supervisedCopy = new LinkedHashSet<>(supervisedSensors);
            globalMode = superviseAllSensors;
        }

        sensorsTableModel.setRowCount(0);
        for (SensorSnapshot snapshot : snapshots) {
            sensorsTableModel.addRow(new Object[]{
                    snapshot.sensorId,
                    safeText(snapshot.sensorType),
                    buildStatusText(snapshot),
                    snapshot.lastValueText,
                    snapshot.lastTimestamp.isEmpty() ? "Sin datos" : snapshot.lastTimestamp,
                    supervisionModeFor(snapshot.sensorId, globalMode, supervisedCopy)
            });
        }

        reselectSensor(preferredSelection);
        updateSelectedSensorDetails(getSelectedSensorId());
        updateMetrics();
        updateActionButtons();
    }

    private void reselectSensor(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            sensorsTable.clearSelection();
            return;
        }

        for (int modelRow = 0; modelRow < sensorsTableModel.getRowCount(); modelRow++) {
            Object value = sensorsTableModel.getValueAt(modelRow, 0);
            if (sensorId.equals(value)) {
                int viewRow = sensorsTable.convertRowIndexToView(modelRow);
                sensorsTable.setRowSelectionInterval(viewRow, viewRow);
                return;
            }
        }

        sensorsTable.clearSelection();
    }

    private String buildStatusText(SensorSnapshot snapshot) {
        if (snapshot.alertCount > 0 && !"Sin datos".equals(snapshot.statusLabel)) {
            return snapshot.statusLabel + " (" + snapshot.alertCount + " alertas)";
        }
        return snapshot.statusLabel;
    }

    private String supervisionModeFor(String sensorId, boolean globalMode, Set<String> supervisedCopy) {
        if (globalMode) {
            return "Global";
        }
        if (supervisedCopy.contains(sensorId)) {
            return "Seleccion";
        }
        return "Libre";
    }

    private void updateSelectedSensorDetails(String sensorId) {
        SensorSnapshot snapshot = null;
        if (sensorId != null) {
            synchronized (sensorStateLock) {
                snapshot = sensorStates.get(sensorId);
            }
        }

        if (snapshot == null) {
            selectedSensorValueLabel.setText("-");
            selectedTypeValueLabel.setText("-");
            selectedStatusValueLabel.setText("-");
            selectedValueValueLabel.setText("-");
            selectedTimestampValueLabel.setText("-");
            return;
        }

        selectedSensorValueLabel.setText(snapshot.sensorId);
        selectedTypeValueLabel.setText(safeText(snapshot.sensorType).isEmpty() ? "-" : snapshot.sensorType);
        selectedStatusValueLabel.setText(buildStatusText(snapshot));
        selectedValueValueLabel.setText(snapshot.lastValueText);
        selectedTimestampValueLabel.setText(
                snapshot.lastTimestamp.isEmpty() ? "Sin datos" : snapshot.lastTimestamp);
    }

    private void updateMetrics() {
        int sensorCount;
        int supervisedCount;
        boolean globalMode;

        synchronized (sensorStateLock) {
            sensorCount = sensorStates.size();
            supervisedCount = supervisedSensors.size();
            globalMode = superviseAllSensors;
        }

        activeSensorsValueLabel.setText(String.valueOf(sensorCount));
        measurementsValueLabel.setText(String.valueOf(measurementsTableModel.getRowCount()));
        alertsValueLabel.setText(String.valueOf(alertsTableModel.getRowCount()));
        supervisionValueLabel.setText(globalMode ? "GLOBAL" : String.valueOf(supervisedCount));
    }

    private void updateActionButtons() {
        String selectedSensorId = getSelectedSensorId();
        boolean hasSelection = selectedSensorId != null;
        boolean selectedSupervised = hasSelection && isSelectedSensorIndividuallySupervised(selectedSensorId);
        boolean globalMode = isSuperviseAllEnabled();

        connectButton.setEnabled(true);
        refreshButton.setEnabled(connected && !refreshRunning);
        getLastButton.setEnabled(connected && hasSelection && !singleQueryRunning);
        superviseSelectedButton.setEnabled(connected && hasSelection && !globalMode && !selectedSupervised);
        unsuperviseSelectedButton.setEnabled(connected && hasSelection && !globalMode && selectedSupervised);
        toggleGlobalSupervisionButton.setEnabled(connected);
        toggleGlobalSupervisionButton.setText(globalMode ? "Pausar supervision" : "Supervisar todos");
        clearHistoryButton.setEnabled(measurementsTableModel.getRowCount() > 0);
        clearAlertsButton.setEnabled(alertsTableModel.getRowCount() > 0);

        if (actionGetSensorsButton != null) {
            actionGetSensorsButton.setEnabled(connected && !actionCommandRunning);
        }
        if (actionPingButton != null) {
            actionPingButton.setEnabled(connected && !actionCommandRunning);
        }
        if (actionGetLastButton != null) {
            actionGetLastButton.setEnabled(connected && !actionCommandRunning);
        }
        if (actionSensorIdField != null) {
            actionSensorIdField.setEnabled(connected && !actionCommandRunning);
        }
    }

    private boolean isSelectedSensorIndividuallySupervised(String sensorId) {
        synchronized (sensorStateLock) {
            return supervisedSensors.contains(sensorId);
        }
    }

    private String getSelectedSensorId() {
        int selectedViewRow = sensorsTable.getSelectedRow();
        if (selectedViewRow < 0) {
            return null;
        }
        int selectedModelRow = sensorsTable.convertRowIndexToModel(selectedViewRow);
        Object value = sensorsTableModel.getValueAt(selectedModelRow, 0);
        return value == null ? null : value.toString();
    }

    private String getSensorType(String sensorId) {
        synchronized (sensorStateLock) {
            SensorSnapshot snapshot = sensorStates.get(sensorId);
            return snapshot == null ? "" : safeText(snapshot.sensorType);
        }
    }

    private String determineStatusLabel(String sensorType, Double numericValue) {
        if (numericValue == null) {
            return "Sin datos";
        }

        String normalizedType = safeText(sensorType).toLowerCase(Locale.ROOT);
        if ("temperatura".equals(normalizedType) && numericValue > 50.0) {
            return "Alerta";
        }
        if ("humedad".equals(normalizedType) && numericValue < 20.0) {
            return "Atencion";
        }
        if ("vibracion".equals(normalizedType) && numericValue > 80.0) {
            return "Alerta";
        }
        if ("presion".equals(normalizedType) && (numericValue < 900.0 || numericValue > 1100.0)) {
            return "Atencion";
        }
        if ("consumo".equals(normalizedType) && numericValue > 1000.0) {
            return "Alerta";
        }
        return "Normal";
    }

    private String inferSensorTypeFromAlert(String alertCode) {
        if (alertCode == null) {
            return "";
        }
        if (alertCode.startsWith("temperatura")) {
            return "temperatura";
        }
        if (alertCode.startsWith("humedad")) {
            return "humedad";
        }
        if (alertCode.startsWith("vibracion")) {
            return "vibracion";
        }
        if (alertCode.startsWith("presion")) {
            return "presion";
        }
        if (alertCode.startsWith("consumo")) {
            return "consumo";
        }
        return "";
    }

    private String formatAlertLabel(String alertCode) {
        if (alertCode == null || alertCode.isEmpty()) {
            return "Alerta";
        }
        String label = alertCode.replace('_', ' ');
        return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private void setConnectedState(boolean isConnected) {
        connectButton.setText(isConnected ? "Desconectar" : "Conectar");
        setFieldsEditable(!isConnected);

        if (isConnected) {
            statusLabel.setText("Conectado a " + serverHost + ":" + serverPort + " como '" + operatorId + "'");
            statusLabel.setForeground(ACCENT);
        } else {
            statusLabel.setText("Desconectado. La aplicacion sigue disponible para reconectar.");
            statusLabel.setForeground(Color.DARK_GRAY);
        }

        updateActionButtons();
    }

    private void setFieldsEditable(boolean editable) {
        hostField.setEditable(editable);
        portField.setEditable(editable);
        operatorIdField.setEditable(editable);
    }

    private void logInfo(String message) {
        appendLog("INFO", message, new Color(31, 41, 55));
    }

    private void logError(String message) {
        appendLog("ERROR", message, DANGER);
    }

    private void appendLog(String level, String message, Color color) {
        String line = "[" + LocalDateTime.now().format(TS_FMT) + "] " + level + "  " + message + "\n";
        if ("ERROR".equals(level)) {
            System.err.print(line);
        } else {
            System.out.print(line);
        }

        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
            statusLabel.setText(message);
            statusLabel.setForeground("ERROR".equals(level) ? DANGER : color);
        });
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        String host = System.getenv().getOrDefault("IOT_SERVER_HOST", "localhost");
        int port = 9000;

        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) {
                host = args[i + 1];
            } else if ("--port".equals(args[i])) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ex) {
                    System.err.println("Puerto invalido: " + args[i + 1] + ". Se usara 9000.");
                }
            }
        }

        String portEnv = System.getenv("IOT_SERVER_PORT");
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final String finalHost = host;
        final int finalPort = port;
        SwingUtilities.invokeLater(() -> {
            OperadorClient client = new OperadorClient(finalHost, finalPort);
            client.setVisible(true);
        });
    }

    private static final class SensorSnapshot {
        private final String sensorId;
        private String sensorType;
        private String lastValueText = "Sin datos";
        private String lastTimestamp = "";
        private String statusLabel = "Sin datos";
        private int alertCount = 0;

        private SensorSnapshot(String sensorId, String sensorType) {
            this.sensorId = sensorId;
            this.sensorType = sensorType;
        }
    }

    private static final class SensorDescriptor {
        private final String sensorId;
        private final String sensorType;

        private SensorDescriptor(String sensorId, String sensorType) {
            this.sensorId = sensorId;
            this.sensorType = sensorType;
        }
    }

    private static final class SnapshotData {
        private final ArrayList<SensorDescriptor> sensors = new ArrayList<>();
        private final ArrayList<MeasurementResult> measurements = new ArrayList<>();
    }

    private static final class MeasurementResult {
        private final String sensorId;
        private final String sensorType;
        private final String valueText;
        private final Double numericValue;
        private final String remoteTimestamp;
        private final String recordedAt;
        private final String origin;
        private final boolean hasData;

        private MeasurementResult(
                String sensorId,
                String sensorType,
                String valueText,
                Double numericValue,
                String remoteTimestamp,
                String origin,
                boolean hasData) {
            this.sensorId = sensorId;
            this.sensorType = sensorType;
            this.valueText = valueText;
            this.numericValue = numericValue;
            this.remoteTimestamp = remoteTimestamp;
            this.recordedAt = LocalDateTime.now().format(TS_FMT);
            this.origin = origin;
            this.hasData = hasData;
        }

        private static MeasurementResult withData(
                String sensorId,
                String sensorType,
                String valueText,
                Double numericValue,
                String remoteTimestamp,
                String origin) {
            return new MeasurementResult(
                    sensorId,
                    sensorType,
                    valueText,
                    numericValue,
                    remoteTimestamp,
                    origin,
                    true);
        }

        private static MeasurementResult noData(String sensorId, String sensorType, String origin) {
            return new MeasurementResult(
                    sensorId,
                    sensorType,
                    "Sin datos",
                    null,
                    "",
                    origin,
                    false);
        }
    }

    private static final class AlertPayload {
        private final String sensorId;
        private final String alertCode;
        private final String alertLabel;
        private final String valueText;
        private final Double numericValue;
        private final String remoteTimestamp;
        private final String receivedAt;

        private AlertPayload(
                String sensorId,
                String alertCode,
                String alertLabel,
                String valueText,
                Double numericValue,
                String remoteTimestamp,
                String receivedAt) {
            this.sensorId = sensorId;
            this.alertCode = alertCode;
            this.alertLabel = alertLabel;
            this.valueText = valueText;
            this.numericValue = numericValue;
            this.remoteTimestamp = remoteTimestamp;
            this.receivedAt = receivedAt;
        }
    }
}
