package org.gameshakers.servidor;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.gameshakers.comun.Red;
import org.gameshakers.comun.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ventana del servidor: arranca el servidor WebSocket conectado a Kafka y muestra el estado de las tres salas.
 */
public class ServidorApp extends JFrame {

    private ServidorWs servidor;
    private PuenteKafka kafka;
    private volatile boolean encendido;

    private final Cabecera cabecera = new Cabecera(Protocolo.NOMBRE_RADIO, "Servidor · apagado");
    private final JSpinner spPuerto = new JSpinner(new SpinnerNumberModel(Protocolo.PUERTO_PREDETERMINADO, 1024, 65535, 1));
    private final JTextField txKafka = new JTextField(Protocolo.KAFKA_PREDETERMINADO);
    private final JPasswordField txClaveLocutores = new JPasswordField();
    private final JPasswordField txClaveOyentes = new JPasswordField();
    private final JSpinner spMax = new JSpinner(new SpinnerNumberModel(Protocolo.MAX_OYENTES_POR_SALA, 1, 200, 1));
    private final JButton btEncender = Tema.botonPrincipal("Encender servidor", Tema.ACENTO);
    private final JLabel lbConexion = Tema.etiqueta("", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final JLabel lbIpPublica = Tema.etiqueta("IP pública: sin consultar", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final JButton btIpPublica = new JButton("Consultar");
    private final JLabel lbKafka = Tema.etiqueta(" ", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final Map<Sala, TarjetaSala> tarjetas = new EnumMap<>(Sala.class);
    private final JTextArea bitacora = new JTextArea();

    public ServidorApp() {
        super(Protocolo.NOMBRE_RADIO + " · Servidor");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                apagar();
                dispose();
                System.exit(0);
            }
        });
        construir();
        actualizar();
        new Timer(1000, e -> refrescarSalas()).start();
        setMinimumSize(new Dimension(1000, 640));
        setSize(1120, 720);
        setLocationRelativeTo(null);
    }

    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(Tema.FONDO);
        raiz.add(cabecera, BorderLayout.NORTH);

        JPanel cuerpo = new JPanel(new BorderLayout(16, 16));
        cuerpo.setOpaque(false);
        cuerpo.setBorder(new EmptyBorder(16, 16, 16, 16));

        // Configuración
        Tarjeta conf = new Tarjeta("Configuración");
        conf.setPreferredSize(new Dimension(320, 10));
        JPanel c = conf.contenido();
        c.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        String[] etiquetas = {"Puerto WebSocket", "Kafka", "Clave locutores", "Clave oyentes", "Máx. por sala"};
        spPuerto.setEditor(new JSpinner.NumberEditor(spPuerto, "#"));
        txClaveLocutores.putClientProperty("JTextField.placeholderText", "Recomendada");
        txClaveOyentes.putClientProperty("JTextField.placeholderText", "Opcional");
        txClaveLocutores.putClientProperty("JTextField.showRevealButton", true);
        txClaveOyentes.putClientProperty("JTextField.showRevealButton", true);
        txKafka.setToolTipText("Dirección del broker de Kafka (bootstrap servers)");
        JComponent[] campos = {spPuerto, txKafka, txClaveLocutores, txClaveOyentes, spMax};
        for (int i = 0; i < campos.length; i++) {
            g.gridy = i;
            g.gridx = 0;
            g.weightx = 0;
            g.insets = new Insets(0, 0, 8, 10);
            c.add(Tema.etiqueta(etiquetas[i], Font.PLAIN, 12f, Tema.TEXTO_SUAVE), g);
            g.gridx = 1;
            g.weightx = 1;
            g.insets = new Insets(0, 0, 8, 0);
            c.add(campos[i], g);
        }
        g.gridy = campos.length;
        g.gridx = 0;
        g.gridwidth = 2;
        g.insets = new Insets(8, 0, 12, 0);
        btEncender.setIcon(Iconos.de(Iconos.Tipo.ANTENA, 20, Color.WHITE));
        btEncender.setPreferredSize(new Dimension(10, 48));
        btEncender.setMinimumSize(new Dimension(10, 48));
        btEncender.addActionListener(e -> alternar());
        c.add(btEncender, g);
        g.gridy++;
        g.insets = new Insets(0, 0, 8, 0);
        c.add(lbConexion, g);
        g.gridy++;
        JPanel filaIp = new JPanel(new BorderLayout(8, 0));
        filaIp.setOpaque(false);
        filaIp.add(lbIpPublica, BorderLayout.CENTER);
        btIpPublica.setToolTipText("Pregunta a api.ipify.org la IP con la que este equipo sale a internet");
        btIpPublica.addActionListener(e -> consultarIpPublica());
        filaIp.add(btIpPublica, BorderLayout.EAST);
        c.add(filaIp, g);
        g.gridy++;
        c.add(lbKafka, g);
        g.gridy++;
        g.weighty = 1;
        c.add(Box.createGlue(), g);
        cuerpo.add(conf, BorderLayout.WEST);

        // Salas
        JPanel centro = new JPanel(new BorderLayout(0, 16));
        centro.setOpaque(false);
        JPanel filaSalas = new JPanel(new GridLayout(1, 3, 16, 0));
        filaSalas.setOpaque(false);
        for (Sala s : Sala.values()) {
            TarjetaSala t = new TarjetaSala(s);
            tarjetas.put(s, t);
            filaSalas.add(t);
        }
        centro.add(filaSalas, BorderLayout.NORTH);

        Tarjeta tLog = new Tarjeta("Actividad");
        bitacora.setEditable(false);
        bitacora.setLineWrap(true);
        bitacora.setBackground(Tema.TARJETA);
        bitacora.setForeground(Tema.TEXTO_SUAVE);
        bitacora.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(bitacora);
        sp.setBorder(null);
        tLog.contenido().setLayout(new BorderLayout());
        tLog.contenido().add(sp);
        centro.add(tLog, BorderLayout.CENTER);
        cuerpo.add(centro, BorderLayout.CENTER);

        raiz.add(cuerpo, BorderLayout.CENTER);
        setContentPane(raiz);
    }

    // ------------------------------------------------------------------ acciones

    private void alternar() {
        if (encendido) {
            apagar();
            registrar("Servidor apagado");
            actualizar();
            return;
        }
        int puerto = (Integer) spPuerto.getValue();
        String direccionKafka = txKafka.getText().strip();
        String claveLoc = new String(txClaveLocutores.getPassword());
        String claveOy = new String(txClaveOyentes.getPassword());
        int max = (Integer) spMax.getValue();
        if (claveLoc.isEmpty()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "No hay clave para locutores: cualquiera que llegue al servidor podría transmitir en una sala libre.\n\n"
                            + "¿Encender sin clave de locutores?", "Sin clave", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        btEncender.setEnabled(false);
        btEncender.setText("Conectando con Kafka…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                ServidorWs ws = new ServidorWs(puerto, claveLoc, claveOy, max, ServidorApp.this::registrar,
                        () -> SwingUtilities.invokeLater(ServidorApp.this::refrescarSalas));
                PuenteKafka k = new PuenteKafka(direccionKafka, ws);
                k.iniciar();                      // falla aquí si Kafka no está encendido
                ws.setKafka(k);
                ws.start();
                try {
                    ws.esperarInicio();
                } catch (Exception e) {
                    k.detener();
                    throw e;
                }
                kafka = k;
                servidor = ws;
                return null;
            }

            @Override
            protected void done() {
                btEncender.setEnabled(true);
                try {
                    get();
                    encendido = true;
                    List<String> ips = Red.ipsLocales();
                    registrar("Servidor encendido en ws://" + (ips.isEmpty() ? "localhost" : ips.get(0)) + ":" + puerto
                            + " · Kafka " + direccionKafka);
                } catch (Exception e) {
                    Throwable causa = e.getCause() != null ? e.getCause() : e;
                    registrar("No se pudo encender: " + causa.getMessage());
                    JOptionPane.showMessageDialog(ServidorApp.this, causa.getMessage(), "No se pudo encender",
                            JOptionPane.ERROR_MESSAGE);
                    if (kafka != null) kafka.detener();
                    kafka = null;
                    servidor = null;
                }
                actualizar();
            }
        }.execute();
    }

    private void apagar() {
        encendido = false;
        try {
            if (servidor != null) servidor.apagar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (kafka != null) kafka.detener();
        servidor = null;
        kafka = null;
    }

    private void consultarIpPublica() {
        btIpPublica.setEnabled(false);
        lbIpPublica.setText("IP pública: consultando…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return Red.ipPublica();
            }

            @Override
            protected void done() {
                btIpPublica.setEnabled(true);
                String ip;
                try {
                    ip = get();
                } catch (Exception e) {
                    ip = null;
                }
                lbIpPublica.setText(ip == null ? "IP pública: no se pudo consultar"
                        : "<html>IP pública: <b style='color:#ECEAF5'>" + ip + "</b></html>");
            }
        }.execute();
    }

    // ------------------------------------------------------------------ estado

    private void actualizar() {
        btEncender.setText(encendido ? "Apagar servidor" : "Encender servidor");
        Tema.estiloPrincipal(btEncender, encendido ? new Color(0x3D, 0x38, 0x60) : Tema.ACENTO);
        for (JComponent c : new JComponent[]{spPuerto, txKafka, txClaveLocutores, txClaveOyentes, spMax}) c.setEnabled(!encendido);
        cabecera.setEnVivo(encendido, encendido ? "EN LÍNEA" : "APAGADO");
        int puerto = (Integer) spPuerto.getValue();
        cabecera.setSubtitulo(encendido ? "Servidor WebSocket en el puerto " + puerto + " · audio y chat por Kafka" : "Servidor · apagado");
        StringBuilder sb = new StringBuilder("<html>");
        if (encendido) {
            sb.append("Locutores y oyentes se conectan a:<br>");
            List<String> ips = Red.ipsLocales();
            if (ips.isEmpty()) sb.append("<b style='color:#ECEAF5'>localhost:").append(puerto).append("</b><br>");
            for (String ip : ips) sb.append("<b style='color:#ECEAF5'>").append(ip).append(":").append(puerto).append("</b><br>");
            sb.append("Desde otras redes: tu IP pública y el puerto TCP ").append(puerto).append(" redirigido en el router.");
        } else {
            sb.append("Antes de encender, inicia Kafka (ver kafka/LEEME.md).");
        }
        lbConexion.setText(sb.append("</html>").toString());
        refrescarSalas();
    }

    private void refrescarSalas() {
        ServidorWs ws = servidor;
        for (Sala s : Sala.values()) tarjetas.get(s).mostrar(ws);
        PuenteKafka k = kafka;
        lbKafka.setText(k == null ? " " : "<html>Kafka: <b style='color:#2EE59D'>conectado</b> · " + k.registrosEnviados()
                + " publicados · " + k.registrosRecibidos() + " consumidos</html>");
    }

    private void registrar(String msg) {
        String linea = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + msg;
        System.out.println(linea);
        SwingUtilities.invokeLater(() -> {
            bitacora.append(linea + "\n");
            bitacora.setCaretPosition(bitacora.getDocument().getLength());
        });
    }

    /** Tarjeta con el estado de una sala. */
    private static class TarjetaSala extends JPanel {
        private final Sala sala;
        private final JLabel lbLocutor = Tema.etiqueta("", Font.BOLD, 14f, Color.WHITE);
        private final JLabel lbSonando = Tema.etiqueta(" ", Font.PLAIN, 12f, new Color(255, 255, 255, 210));
        private final JLabel lbOyentes = Tema.etiqueta("", Font.PLAIN, 12f, new Color(255, 255, 255, 210));
        private final JLabel lbPaquetes = Tema.etiqueta(" ", Font.PLAIN, 11f, new Color(255, 255, 255, 170));
        private final JTextArea taNombres = new JTextArea(4, 10);

        TarjetaSala(Sala sala) {
            super(new BorderLayout(0, 8));
            this.sala = sala;
            setOpaque(false);
            setBorder(new EmptyBorder(14, 16, 14, 16));
            JPanel textos = new JPanel(new GridLayout(0, 1, 0, 2));
            textos.setOpaque(false);
            textos.add(Tema.etiqueta(sala.titulo.toUpperCase(), Font.BOLD, 18f, Color.WHITE));
            textos.add(lbLocutor);
            textos.add(lbSonando);
            textos.add(lbOyentes);
            textos.add(lbPaquetes);
            add(textos, BorderLayout.NORTH);
            taNombres.setEditable(false);
            taNombres.setOpaque(false);
            taNombres.setForeground(Color.WHITE);
            taNombres.setLineWrap(true);
            taNombres.setWrapStyleWord(true);
            taNombres.setFont(Tema.fuente(Font.PLAIN, 12f));
            add(taNombres, BorderLayout.CENTER);
            setPreferredSize(new Dimension(200, 220));
        }

        void mostrar(ServidorWs ws) {
            if (ws == null) {
                lbLocutor.setText("Servidor apagado");
                lbSonando.setText(" ");
                lbOyentes.setText(" ");
                lbPaquetes.setText(" ");
                taNombres.setText("");
                return;
            }
            String loc = ws.nombreLocutor(sala);
            lbLocutor.setText(loc.isEmpty() ? "Sin locutor" : "Locutor: " + loc + (ws.micAbierto(sala) ? "  · MIC" : ""));
            String t = ws.titulo(sala);
            lbSonando.setText(t.isEmpty() ? " " : "Sonando: " + t);
            List<String> oy = ws.oyentes(sala);
            lbOyentes.setText(oy.size() == 1 ? "1 oyente" : oy.size() + " oyentes");
            lbPaquetes.setText(ws.paquetesAudio(sala) + " paquetes de audio vía Kafka");
            taNombres.setText(String.join(", ", oy));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, sala.color.brighter(), getWidth(), getHeight(), sala.color.darker()));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Tema.aplicar();
            new ServidorApp().setVisible(true);
        });
    }
}
