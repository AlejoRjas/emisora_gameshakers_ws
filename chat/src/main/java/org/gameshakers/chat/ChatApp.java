package org.gameshakers.chat;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.gameshakers.comun.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de GameShakers Chat: se conecta por WebSocket al servidor, entra a una de las tres
 * salas (Xbox, PlayStation, PC · Steam), chatea y puede cambiarse de sala.
 *
 * Uso: java -jar chat.jar [servidor]   (p. ej. localhost:8080 o miapp.onrender.com)
 */
public class ChatApp extends JFrame implements ConexionChat.Eventos {

    private volatile ConexionChat conexion;
    private Sala salaElegida = Sala.XBOX;
    private Sala salaActual;
    private String miNombre = "";

    private final Cabecera cabecera = new Cabecera(Protocolo.NOMBRE_CHAT, "Sin conectar");
    private final JTextField txServidor = new JTextField("localhost:" + Protocolo.PUERTO_PREDETERMINADO);
    private final JTextField txNombre = new JTextField(System.getProperty("user.name", ""));
    private final JButton btConectar = Tema.botonPrincipal("Conectar", Tema.ACENTO);
    private final JLabel lbEstado = Tema.etiqueta("Elige una sala y pulsa Conectar.", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final Map<Sala, BotonSala> botonesSala = new EnumMap<>(Sala.class);
    private final JLabel lbTituloSala = Tema.etiqueta("Sin sala", Font.BOLD, 20f, Color.WHITE);
    private final JPanel franjaSala = new JPanel(new BorderLayout()) {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = salaActual != null ? salaActual.color : Tema.BORDE;
            g2.setPaint(new GradientPaint(0, 0, c.brighter(), getWidth(), 0, c));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
        }
    };
    private final PanelChat chat = new PanelChat(t -> { if (conexion != null) conexion.escribir(t); });
    private final PanelPersonas personas = new PanelPersonas();

    public ChatApp(String servidorInicial) {
        super(Protocolo.NOMBRE_CHAT);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (conexion != null) conexion.salir();
                dispose();
                System.exit(0);
            }
        });
        if (servidorInicial != null) txServidor.setText(servidorInicial);
        construir();
        actualizar();
        setMinimumSize(new Dimension(980, 620));
        setSize(1120, 720);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ interfaz

    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(Tema.FONDO);
        raiz.add(cabecera, BorderLayout.NORTH);
        JPanel cuerpo = new JPanel(new BorderLayout(16, 0));
        cuerpo.setOpaque(false);
        cuerpo.setBorder(new EmptyBorder(16, 16, 16, 16));

        // Izquierda: conexión y salas
        JPanel izq = new JPanel(new BorderLayout(0, 16));
        izq.setOpaque(false);
        izq.setPreferredSize(new Dimension(280, 10));
        Tarjeta con = new Tarjeta("Conexión");
        JPanel c = con.contenido();
        c.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        String[] etiquetas = {"Servidor", "Tu nombre"};
        txServidor.setToolTipText("<html>En tu red: <b>localhost:8080</b> o <b>192.168.x.x:8080</b><br>"
                + "En internet: el enlace del servidor, p. ej. <b>miapp.onrender.com</b></html>");
        JComponent[] campos = {txServidor, txNombre};
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
        g.gridy = 2;
        g.gridx = 0;
        g.gridwidth = 2;
        g.insets = new Insets(6, 0, 8, 0);
        btConectar.setPreferredSize(new Dimension(10, 44));
        btConectar.setMinimumSize(new Dimension(10, 44));
        btConectar.addActionListener(e -> alternarConexion());
        c.add(btConectar, g);
        g.gridy = 3;
        g.insets = new Insets(0, 0, 0, 0);
        lbEstado.setPreferredSize(new Dimension(10, 48));
        lbEstado.setVerticalAlignment(SwingConstants.TOP);
        c.add(lbEstado, g);
        izq.add(con, BorderLayout.NORTH);

        Tarjeta tSalas = new Tarjeta("Salas del servidor");
        JPanel listaSalas = tSalas.contenido();
        listaSalas.setLayout(new GridLayout(Sala.values().length, 1, 0, 10));
        for (Sala s : Sala.values()) {
            BotonSala b = new BotonSala(s);
            botonesSala.put(s, b);
            listaSalas.add(b);
        }
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(tSalas, BorderLayout.NORTH);
        izq.add(arriba, BorderLayout.CENTER);
        cuerpo.add(izq, BorderLayout.WEST);

        // Centro: conversación
        Tarjeta tChat = new Tarjeta(null);
        JPanel ch = tChat.contenido();
        ch.setLayout(new BorderLayout(0, 12));
        franjaSala.setOpaque(false);
        franjaSala.setBorder(new EmptyBorder(10, 14, 10, 14));
        franjaSala.add(lbTituloSala, BorderLayout.WEST);
        ch.add(franjaSala, BorderLayout.NORTH);
        ch.add(chat, BorderLayout.CENTER);
        cuerpo.add(tChat, BorderLayout.CENTER);

        // Derecha: personas de la sala
        Tarjeta tPersonas = new Tarjeta("En esta sala");
        tPersonas.setPreferredSize(new Dimension(230, 10));
        tPersonas.contenido().setLayout(new BorderLayout());
        tPersonas.contenido().add(personas);
        cuerpo.add(tPersonas, BorderLayout.EAST);

        raiz.add(cuerpo, BorderLayout.CENTER);
        setContentPane(raiz);
    }

    // ------------------------------------------------------------------ acciones

    private void alternarConexion() {
        if (conexion != null) {
            conexion.salir();
            return;
        }
        String nombre = Protocolo.limpiarNombre(txNombre.getText());
        String servidor = txServidor.getText().strip();
        if (nombre.isEmpty() || servidor.isEmpty()) {
            lbEstado.setText("Escribe el servidor y tu nombre.");
            return;
        }
        try {
            conexion = new ConexionChat(servidor, nombre, salaElegida, this);
        } catch (IllegalArgumentException e) {
            lbEstado.setText("Dirección no válida: " + e.getMessage());
            return;
        }
        miNombre = nombre;
        chat.setYo(nombre);
        lbEstado.setText("<html>Conectando con<br>" + conexion.uri() + "…</html>");
        conexion.conectar();
        actualizar();
    }

    private void elegirSala(Sala sala) {
        ConexionChat c = conexion;
        if (c != null && salaActual != null) {
            if (sala != salaActual) c.cambiarSala(sala);
        } else if (c == null) {
            salaElegida = sala;
            actualizar();
        }
    }

    // ------------------------------------------------------------------ eventos de la conexión

    @Override
    public void dentro(Sala sala) {
        SwingUtilities.invokeLater(() -> {
            salaActual = sala;
            salaElegida = sala;
            chat.limpiar();
            chat.aviso("Entraste a la sala " + sala.titulo + ". Estos son sus mensajes recientes:");
            chat.setActivo(true);
            lbEstado.setText("<html>Conectado a<br>" + conexion.uri() + "</html>");
            actualizar();
        });
    }

    @Override
    public void salas(Map<Sala, Integer> personasPorSala) {
        SwingUtilities.invokeLater(() -> personasPorSala.forEach((s, n) -> botonesSala.get(s).setPersonas(n)));
    }

    @Override
    public void usuarios(Sala sala, List<String> nombres) {
        SwingUtilities.invokeLater(() -> { if (sala == salaActual) personas.mostrar(nombres, miNombre); });
    }

    @Override
    public void mensaje(Sala sala, String hora, String autor, String texto) {
        SwingUtilities.invokeLater(() -> { if (sala == salaActual) chat.mensaje(hora, autor, texto); });
    }

    @Override
    public void aviso(String texto) {
        SwingUtilities.invokeLater(() -> chat.aviso(texto));
    }

    @Override
    public void cerrada(String motivo) {
        SwingUtilities.invokeLater(() -> {
            conexion = null;
            salaActual = null;
            chat.setActivo(false);
            personas.limpiar();
            for (BotonSala b : botonesSala.values()) b.setPersonas(-1);
            if (motivo != null) chat.aviso(motivo);
            lbEstado.setText(motivo == null ? "Te desconectaste." : "<html><span style='color:#FFC83D'>" + motivo + "</span></html>");
            actualizar();
        });
    }

    // ------------------------------------------------------------------ estado

    private void actualizar() {
        boolean conectado = conexion != null;
        boolean dentro = conectado && salaActual != null;
        btConectar.setText(conectado ? "Desconectar" : "Conectar");
        Tema.estiloPrincipal(btConectar, conectado ? new Color(0x3D, 0x38, 0x60) : Tema.ACENTO);
        txServidor.setEnabled(!conectado);
        txNombre.setEnabled(!conectado);
        Sala marcada = dentro ? salaActual : salaElegida;
        for (BotonSala b : botonesSala.values()) b.setEstado(b.sala == marcada, dentro);
        lbTituloSala.setText(dentro ? "Sala " + salaActual.titulo : "Elige una sala y conéctate");
        franjaSala.repaint();
        cabecera.setEnVivo(dentro, dentro ? "CONECTADO" : (conectado ? "CONECTANDO…" : "SIN CONEXIÓN"));
        cabecera.setSubtitulo(dentro ? miNombre + " · Sala " + salaActual.titulo : "3 salas: Xbox · PlayStation · PC Steam");
    }

    /** Botón grande de una sala con su color y cuántas personas tiene. */
    private class BotonSala extends JPanel {
        final Sala sala;
        private boolean marcada;
        private final JLabel lbPersonas = Tema.etiqueta(" ", Font.PLAIN, 12f, new Color(255, 255, 255, 210));
        private final JLabel lbMarca = Tema.etiqueta(" ", Font.BOLD, 10f, Color.WHITE);

        BotonSala(Sala sala) {
            super(new BorderLayout());
            this.sala = sala;
            setOpaque(false);
            setBorder(new EmptyBorder(10, 14, 10, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JPanel textos = new JPanel(new GridLayout(2, 1));
            textos.setOpaque(false);
            textos.add(Tema.etiqueta(sala.titulo, Font.BOLD, 16f, Color.WHITE));
            textos.add(lbPersonas);
            add(textos, BorderLayout.CENTER);
            add(lbMarca, BorderLayout.EAST);
            setPreferredSize(new Dimension(10, 64));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    elegirSala(sala);
                }
            });
        }

        void setPersonas(int n) {
            lbPersonas.setText(n < 0 ? " " : n == 1 ? "1 persona" : n + " personas");
        }

        void setEstado(boolean marcada, boolean dentro) {
            this.marcada = marcada;
            lbMarca.setText(marcada ? (dentro ? "ESTÁS AQUÍ" : "ELEGIDA") : (dentro ? "Cambiarme" : " "));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = marcada ? sala.color : sala.color.darker().darker();
            g2.setPaint(new GradientPaint(0, 0, base.brighter(), getWidth(), getHeight(), base));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            if (marcada) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
            }
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Tema.aplicar();
            new ChatApp(args.length > 0 ? args[0] : null).setVisible(true);
        });
    }
}
