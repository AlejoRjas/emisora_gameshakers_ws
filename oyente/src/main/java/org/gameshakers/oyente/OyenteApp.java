package org.gameshakers.oyente;

import org.gameshakers.comun.ConexionRadio;
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
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ventana del oyente: se conecta por WebSocket al servidor, elige una de las tres salas
 * (Xbox, PlayStation, PC · Steam), escucha su transmisión y participa en el chat.
 */
public class OyenteApp extends JFrame implements ConexionRadio.Eventos {

    private volatile ConexionRadio conexion;
    private volatile Reproductor reproductor;
    private volatile long paquetes;
    private Sala salaElegida = Sala.XBOX;
    private Sala salaActual;

    private final Cabecera cabecera = new Cabecera(Protocolo.NOMBRE_RADIO, "Oyente · sin conectar");
    private final JTextField txServidor = new JTextField("localhost:" + Protocolo.PUERTO_PREDETERMINADO);
    private final JTextField txNombre = new JTextField(System.getProperty("user.name", ""));
    private final JPasswordField txClave = new JPasswordField();
    private final JComboBox<Reproductor.Modo> cbModo = new JComboBox<>(Reproductor.Modo.values());
    private final JButton btConectar = Tema.botonPrincipal("Conectar", Tema.ACENTO);
    private final JLabel lbEstado = Tema.etiqueta("Elige una sala y pulsa Conectar.", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);

    private final Map<Sala, TarjetaSala> tarjetas = new EnumMap<>(Sala.class);
    private final JLabel lbSalaActual = Tema.etiqueta("Sin sala", Font.BOLD, 12f, Tema.TEXTO_SUAVE);
    private final Ecualizador ecualizador = new Ecualizador(Analizador.FRECUENCIAS.length);
    private final JLabel lbTitulo = Tema.etiqueta("Sin conexión", Font.BOLD, 20f, Tema.TEXTO);
    private final JLabel lbLocutor = Tema.etiqueta(" ", Font.PLAIN, 13f, Tema.TEXTO_SUAVE);
    private final JLabel lbMic = Tema.etiqueta("  El locutor está hablando", Font.BOLD, 12f, Color.WHITE);

    private final JToggleButton btSilencio = new JToggleButton(Iconos.de(Iconos.Tipo.PARLANTE, 18, Tema.TEXTO));
    private final JSlider slVolumen = new JSlider(0, 100, 90);
    private final VuMetro vu = new VuMetro();
    private final JLabel lbEstadisticas = Tema.etiqueta(" ", Font.PLAIN, 11f, Tema.TEXTO_SUAVE);

    private final PanelPersonas personas = new PanelPersonas();
    private final PanelChat chat = new PanelChat(t -> { if (conexion != null) conexion.enviarChat(t); });

    public OyenteApp(String servidorInicial) {
        super(Protocolo.NOMBRE_RADIO + " · Oyente");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                desconectar();
                dispose();
                System.exit(0);
            }
        });
        if (servidorInicial != null) txServidor.setText(servidorInicial);
        construir();
        actualizarControles();
        new Timer(40, e -> animar()).start();
        setMinimumSize(new Dimension(1100, 680));
        setSize(1240, 760);
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
        cuerpo.add(columnaIzquierda(), BorderLayout.WEST);
        cuerpo.add(columnaCentral(), BorderLayout.CENTER);
        cuerpo.add(columnaDerecha(), BorderLayout.EAST);
        raiz.add(cuerpo, BorderLayout.CENTER);
        setContentPane(raiz);
    }

    private JComponent columnaIzquierda() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(290, 10));

        Tarjeta con = new Tarjeta("Conexión");
        JPanel c = con.contenido();
        c.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        String[] etiquetas = {"Servidor", "Tu nombre", "Clave", "Red"};
        txServidor.setToolTipText("IP o dominio del servidor, con :puerto si no es " + Protocolo.PUERTO_PREDETERMINADO);
        txClave.putClientProperty("JTextField.placeholderText", "Solo si el servidor tiene");
        txClave.putClientProperty("JTextField.showRevealButton", true);
        cbModo.setToolTipText("Por internet un buffer mayor evita cortes, a cambio de más retraso");
        JComponent[] campos = {txServidor, txNombre, txClave, cbModo};
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
        g.insets = new Insets(6, 0, 8, 0);
        btConectar.setPreferredSize(new Dimension(10, 46));
        btConectar.setMinimumSize(new Dimension(10, 46));
        btConectar.addActionListener(e -> alternarConexion());
        c.add(btConectar, g);
        g.gridy++;
        g.insets = new Insets(0, 0, 0, 0);
        lbEstado.setPreferredSize(new Dimension(10, 34));
        lbEstado.setVerticalAlignment(SwingConstants.TOP);
        c.add(lbEstado, g);
        col.add(con, BorderLayout.NORTH);

        Tarjeta vol = new Tarjeta("Volumen");
        JPanel v = vol.contenido();
        v.setLayout(new BorderLayout(10, 8));
        btSilencio.putClientProperty("JButton.buttonType", "roundRect");
        btSilencio.setToolTipText("Silenciar");
        btSilencio.setFocusPainted(false);
        btSilencio.addActionListener(e -> {
            btSilencio.setIcon(Iconos.de(btSilencio.isSelected() ? Iconos.Tipo.SILENCIO : Iconos.Tipo.PARLANTE, 18, Tema.TEXTO));
            if (reproductor != null) reproductor.setSilencio(btSilencio.isSelected());
        });
        slVolumen.addChangeListener(e -> { if (reproductor != null) reproductor.setVolumen(slVolumen.getValue() / 100f); });
        v.add(btSilencio, BorderLayout.WEST);
        v.add(slVolumen, BorderLayout.CENTER);
        JPanel abajo = new JPanel(new GridLayout(2, 1, 0, 6));
        abajo.setOpaque(false);
        abajo.add(vu);
        abajo.add(lbEstadisticas);
        v.add(abajo, BorderLayout.SOUTH);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(vol, BorderLayout.NORTH);
        col.add(arriba, BorderLayout.CENTER);
        return col;
    }

    private JComponent columnaCentral() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);

        JPanel filaSalas = new JPanel(new GridLayout(1, 3, 12, 0));
        filaSalas.setOpaque(false);
        for (Sala s : Sala.values()) {
            TarjetaSala t = new TarjetaSala(s);
            tarjetas.put(s, t);
            filaSalas.add(t);
        }
        col.add(filaSalas, BorderLayout.NORTH);

        Tarjeta ahora = new Tarjeta("Sonando ahora");
        JPanel a = ahora.contenido();
        a.setLayout(new BorderLayout(0, 12));
        a.add(lbSalaActual, BorderLayout.NORTH);
        ecualizador.setMinimumSize(new Dimension(100, 60));
        a.add(ecualizador, BorderLayout.CENTER);
        JPanel textos = new JPanel(new GridLayout(0, 1, 0, 6));
        textos.setOpaque(false);
        textos.add(lbTitulo);
        textos.add(lbLocutor);
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fila.setOpaque(false);
        lbMic.setIcon(Iconos.de(Iconos.Tipo.MIC, 14, Color.WHITE));
        lbMic.setOpaque(true);
        lbMic.setBackground(Tema.ROJO);
        lbMic.setBorder(new EmptyBorder(4, 10, 4, 12));
        lbMic.setVisible(false);
        fila.add(lbMic);
        textos.add(fila);
        a.add(textos, BorderLayout.SOUTH);
        col.add(ahora, BorderLayout.CENTER);
        return col;
    }

    private JComponent columnaDerecha() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(300, 10));
        Tarjeta tPersonas = new Tarjeta("En esta sala");
        tPersonas.contenido().setLayout(new BorderLayout());
        tPersonas.contenido().add(personas);
        tPersonas.setPreferredSize(new Dimension(10, 220));
        col.add(tPersonas, BorderLayout.NORTH);
        Tarjeta tChat = new Tarjeta("Chat de la sala");
        tChat.contenido().setLayout(new BorderLayout());
        tChat.contenido().add(chat);
        col.add(tChat, BorderLayout.CENTER);
        return col;
    }

    // ------------------------------------------------------------------ acciones

    private void elegirSala(Sala sala) {
        ConexionRadio c = conexion;
        if (c != null && c.estaDentro()) {
            if (sala != salaActual) {
                lbEstado.setText("Cambiando a " + sala.titulo + "…");
                c.cambiarSala(sala);
            }
        } else if (c == null) {
            salaElegida = sala;
            actualizarControles();
        }
    }

    private void alternarConexion() {
        if (conexion != null) {
            desconectar();
            lbEstado.setText("Te desconectaste.");
            actualizarControles();
            return;
        }
        String nombre = txNombre.getText().strip();
        String servidor = txServidor.getText().strip();
        if (nombre.isEmpty() || servidor.isEmpty()) {
            lbEstado.setText("Escribe el servidor y tu nombre.");
            return;
        }
        try {
            conexion = new ConexionRadio(servidor, Protocolo.OYENTE, salaElegida, nombre, new String(txClave.getPassword()), this);
        } catch (IllegalArgumentException e) {
            lbEstado.setText("Dirección no válida: " + e.getMessage());
            return;
        }
        conexion.connect();
        lbEstado.setText("Conectando con " + servidor + "…");
        actualizarControles();
    }

    private void desconectar() {
        ConexionRadio c = conexion;
        conexion = null;
        if (c != null) c.salir();
        Reproductor r = reproductor;
        reproductor = null;
        if (r != null) r.detener();
        salaActual = null;
        chat.setActivo(false);
        personas.limpiar();
        for (TarjetaSala t : tarjetas.values()) t.sinDatos();
        lbMic.setVisible(false);
        lbTitulo.setText("Sin conexión");
        lbLocutor.setText(" ");
    }

    private Reproductor nuevoReproductor() {
        Reproductor r = new Reproductor((Reproductor.Modo) cbModo.getSelectedItem());
        r.setVolumen(slVolumen.getValue() / 100f);
        r.setSilencio(btSilencio.isSelected());
        return r;
    }

    // ------------------------------------------------------------------ eventos de la conexión

    @Override
    public void dentro(Sala sala) {
        // al entrar o al cambiar de sala se empieza un reproductor nuevo (es otro flujo de audio)
        Reproductor viejo = reproductor;
        reproductor = nuevoReproductor();
        if (viejo != null) viejo.detener();
        SwingUtilities.invokeLater(() -> {
            salaActual = sala;
            salaElegida = sala;
            chat.limpiar();
            chat.setActivo(true);
            chat.aviso("Entraste a la sala " + sala.titulo + ".");
            lbEstado.setText("<html>Escuchando la sala <b>" + sala.titulo + "</b>.</html>");
            actualizarControles();
        });
    }

    @Override
    public void audio(ByteBuffer mp3) {
        Reproductor r = reproductor;
        if (r == null) return;
        byte[] b = new byte[mp3.remaining()];
        mp3.get(b);
        paquetes++;
        r.recibir(b);
    }

    @Override
    public void salas(List<ConexionRadio.InfoSala> salas) {
        SwingUtilities.invokeLater(() -> {
            for (ConexionRadio.InfoSala i : salas) tarjetas.get(i.sala()).mostrar(i);
        });
    }

    @Override
    public void oyentes(Sala sala, String locutor, List<String> oyentes) {
        SwingUtilities.invokeLater(() -> { if (sala == salaActual) personas.mostrar(locutor, oyentes); });
    }

    @Override
    public void info(Sala sala, String titulo, boolean mic, String locutor) {
        SwingUtilities.invokeLater(() -> {
            if (sala != salaActual) return;
            lbTitulo.setText(!titulo.isEmpty() ? titulo : (locutor.isEmpty() ? "La sala no tiene locutor ahora" : (mic ? "Al aire con el locutor" : "En silencio")));
            lbLocutor.setText(locutor.isEmpty() ? "Esperando a que un locutor entre a la sala…" : "Locutor: " + locutor);
            lbMic.setVisible(mic);
        });
    }

    @Override
    public void chat(Sala sala, String hora, String autor, boolean esLocutor, String texto) {
        SwingUtilities.invokeLater(() -> { if (sala == salaActual) chat.mensaje(hora, autor, esLocutor, texto); });
    }

    @Override
    public void aviso(String texto) {
        SwingUtilities.invokeLater(() -> chat.aviso(texto));
    }

    @Override
    public void cerrada(String motivo) {
        SwingUtilities.invokeLater(() -> {
            if (motivo == null) return;      // la cerró el usuario
            desconectar();
            lbEstado.setText("<html><span style='color:#FFC83D'>" + motivo + "</span></html>");
            chat.aviso(motivo);
            actualizarControles();
        });
    }

    // ------------------------------------------------------------------ estado

    private void actualizarControles() {
        boolean conectado = conexion != null;
        boolean dentro = conectado && salaActual != null;
        btConectar.setText(conectado ? "Desconectar" : "Conectar");
        Tema.estiloPrincipal(btConectar, conectado ? new Color(0x3D, 0x38, 0x60) : Tema.ACENTO);
        for (JComponent c : new JComponent[]{txServidor, txNombre, txClave, cbModo}) c.setEnabled(!conectado);
        Sala marcada = dentro ? salaActual : salaElegida;
        for (TarjetaSala t : tarjetas.values()) t.setMarcada(t.sala == marcada, dentro);
        lbSalaActual.setText(dentro ? "SALA " + salaActual.titulo.toUpperCase() : "SIN SALA");
        lbSalaActual.setForeground(dentro ? salaActual.color.brighter().brighter() : Tema.TEXTO_SUAVE);
        cabecera.setEnVivo(dentro, dentro ? "ESCUCHANDO" : (conectado ? "CONECTANDO…" : "SIN CONEXIÓN"));
        cabecera.setSubtitulo(dentro ? "Oyente · " + txNombre.getText().strip() + " · Sala " + salaActual.titulo : "Oyente · sin conectar");
    }

    private void animar() {
        Reproductor r = reproductor;
        boolean activo = r != null && conexion != null;
        vu.setNivel(activo ? r.nivel() : 0f);
        ecualizador.setBandas(activo ? r.bandas() : null);
        lbEstadisticas.setText(activo ? (r.cargando() ? "Cargando buffer…" : "Buffer " + r.bufferMs() + " ms")
                + "   ·   Paquetes recibidos " + paquetes : " ");
    }

    /** Tarjeta de una sala: al hacer clic se entra (o se elige antes de conectar). */
    private class TarjetaSala extends JPanel {
        final Sala sala;
        private boolean marcada, dentro;
        private final JLabel lbLocutor = Tema.etiqueta(" ", Font.BOLD, 12f, Color.WHITE);
        private final JLabel lbDetalle = Tema.etiqueta(" ", Font.PLAIN, 11f, new Color(255, 255, 255, 200));
        private final JLabel lbMarca = Tema.etiqueta(" ", Font.BOLD, 10f, Color.WHITE);

        TarjetaSala(Sala sala) {
            super(new GridLayout(0, 1, 0, 2));
            this.sala = sala;
            setOpaque(false);
            setBorder(new EmptyBorder(12, 14, 12, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(Tema.etiqueta(sala.titulo, Font.BOLD, 17f, Color.WHITE));
            add(lbLocutor);
            add(lbDetalle);
            add(lbMarca);
            sinDatos();
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    elegirSala(sala);
                }
            });
            setPreferredSize(new Dimension(150, 112));
        }

        void sinDatos() {
            lbLocutor.setText("Clic para elegir");
            lbDetalle.setText(" ");
        }

        void mostrar(ConexionRadio.InfoSala i) {
            lbLocutor.setText(i.locutor().isEmpty() ? "Sin locutor" : "● " + i.locutor() + (i.mic() ? " · MIC" : ""));
            String det = i.oyentes() == 1 ? "1 oyente" : i.oyentes() + " oyentes";
            if (!i.titulo().isEmpty()) det += " · " + i.titulo();
            lbDetalle.setText(det);
        }

        void setMarcada(boolean m, boolean dentro) {
            this.marcada = m;
            this.dentro = dentro;
            lbMarca.setText(m ? (dentro ? "ESTÁS AQUÍ" : "ELEGIDA") : (dentro ? "Clic para cambiarte" : " "));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = marcada ? sala.color : sala.color.darker().darker();
            g2.setPaint(new GradientPaint(0, 0, base.brighter(), getWidth(), getHeight(), base));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            if (marcada) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
            }
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Tema.aplicar();
            new OyenteApp(args.length > 0 ? args[0] : null).setVisible(true);
        });
    }
}
