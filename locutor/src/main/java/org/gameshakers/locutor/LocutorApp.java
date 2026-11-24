package org.gameshakers.locutor;

import org.gameshakers.comun.ConexionRadio;
import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.gameshakers.comun.ui.*;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Ventana del locutor: se conecta por WebSocket al servidor, ocupa una de las tres salas y transmite
 * su micrófono y una lista de canciones MP3. También ve quién escucha y participa en el chat de la sala.
 */
public class LocutorApp extends JFrame implements ConexionRadio.Eventos {

    private final FuenteMicrofono microfono = new FuenteMicrofono();
    private volatile Mezclador mezclador;
    private volatile ConexionRadio conexion;

    private final DefaultListModel<File> lista = new DefaultListModel<>();
    private int indiceActual = -1;

    private final Cabecera cabecera = new Cabecera(Protocolo.NOMBRE_RADIO, "Locutor · sin conectar");
    private final JTextField txServidor = new JTextField("localhost:" + Protocolo.PUERTO_PREDETERMINADO);
    private final JComboBox<Sala> cbSala = new JComboBox<>(Sala.values());
    private final JTextField txNombre = new JTextField(System.getProperty("user.name", ""));
    private final JPasswordField txClave = new JPasswordField();
    private final JButton btAire = Tema.botonPrincipal("Salir al aire", Tema.ACENTO);
    private final JLabel lbEstadoConexion = Tema.etiqueta(" ", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);

    private final JToggleButton btMic = new JToggleButton("Abrir micrófono", Iconos.de(Iconos.Tipo.MIC_OFF, 22, Color.WHITE));
    private final VuMetro vuMic = new VuMetro();
    private final JSlider slMic = new JSlider(0, 150, 100);
    private final JCheckBox chAtenuar = new JCheckBox("Bajar la música mientras hablo", true);

    private final JLabel lbSonando = Tema.etiqueta("Nada sonando", Font.BOLD, 20f, Tema.TEXTO);
    private final JLabel lbEstado = Tema.etiqueta(" ", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final JProgressBar progreso = new JProgressBar(0, 1000);
    private final JLabel lbTiempo = Tema.etiqueta("0:00 / 0:00", Font.PLAIN, 12f, Tema.TEXTO_SUAVE);
    private final JButton btPlay = Tema.botonRedondo(Iconos.de(Iconos.Tipo.PLAY, 20, Color.WHITE), "Reproducir / Pausar");
    private final JButton btStop = Tema.botonRedondo(Iconos.de(Iconos.Tipo.STOP, 18, Color.WHITE), "Detener");
    private final JButton btSiguiente = Tema.botonRedondo(Iconos.de(Iconos.Tipo.SIGUIENTE, 18, Color.WHITE), "Siguiente");
    private final JSlider slMusica = new JSlider(0, 100, 80);
    private final Ecualizador ecualizador = new Ecualizador(Analizador.FRECUENCIAS.length);
    private final VuMetro vuSalida = new VuMetro();
    private final JCheckBox chMonitor = new JCheckBox("Escuchar la música en este equipo");
    private final JList<File> jlLista = new JList<>(lista);

    private final PanelPersonas personas = new PanelPersonas();
    private final PanelChat chat = new PanelChat(t -> { if (conexion != null) conexion.enviarChat(t); });

    public LocutorApp() {
        super(Protocolo.NOMBRE_RADIO + " · Locutor");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                desconectar();
                dispose();
                System.exit(0);
            }
        });
        construir();
        actualizarControles();
        new Timer(40, e -> animar()).start();
        new Timer(3000, e -> enviarInfo()).start();      // por si un mensaje se pierde al cambiar de sala
        setMinimumSize(new Dimension(1120, 680));
        setSize(1260, 760);
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
        String[] etiquetas = {"Servidor", "Sala", "Tu nombre", "Clave"};
        txServidor.setToolTipText("IP o dominio del servidor, con :puerto si no es " + Protocolo.PUERTO_PREDETERMINADO);
        txClave.putClientProperty("JTextField.placeholderText", "Clave de locutores");
        txClave.putClientProperty("JTextField.showRevealButton", true);
        cbSala.setRenderer(new RenderSala());
        JComponent[] campos = {txServidor, cbSala, txNombre, txClave};
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
        btAire.setIcon(Iconos.de(Iconos.Tipo.ANTENA, 20, Color.WHITE));
        btAire.setPreferredSize(new Dimension(10, 48));
        btAire.setMinimumSize(new Dimension(10, 48));
        btAire.addActionListener(e -> alternarConexion());
        c.add(btAire, g);
        g.gridy++;
        g.insets = new Insets(0, 0, 0, 0);
        lbEstadoConexion.setPreferredSize(new Dimension(10, 34));
        lbEstadoConexion.setVerticalAlignment(SwingConstants.TOP);
        c.add(lbEstadoConexion, g);
        col.add(con, BorderLayout.NORTH);

        Tarjeta mic = new Tarjeta("Micrófono del locutor");
        JPanel m = mic.contenido();
        m.setLayout(new GridBagLayout());
        GridBagConstraints f = filaCompleta();
        btMic.setPreferredSize(new Dimension(10, 48));
        btMic.setMinimumSize(new Dimension(10, 48));
        btMic.addActionListener(e -> alternarMicrofono());
        m.add(btMic, f);
        f.gridy++;
        f.insets = new Insets(14, 0, 4, 0);
        m.add(Tema.etiqueta("Nivel de tu voz", Font.PLAIN, 12f, Tema.TEXTO_SUAVE), f);
        f.gridy++;
        f.insets = new Insets(0, 0, 0, 0);
        m.add(vuMic, f);
        f.gridy++;
        f.insets = new Insets(12, 0, 0, 0);
        m.add(conIcono(Iconos.Tipo.MIC, slMic), f);
        slMic.addChangeListener(e -> { if (mezclador != null) mezclador.setVolMicrofono(slMic.getValue() / 100f); });
        f.gridy++;
        chAtenuar.addActionListener(e -> { if (mezclador != null) mezclador.setAtenuarAlHablar(chAtenuar.isSelected()); });
        m.add(chAtenuar, f);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(mic, BorderLayout.NORTH);
        col.add(arriba, BorderLayout.CENTER);
        return col;
    }

    private JComponent columnaCentral() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);

        Tarjeta ahora = new Tarjeta("Sonando ahora");
        JPanel a = ahora.contenido();
        a.setLayout(new GridBagLayout());
        GridBagConstraints c = filaCompleta();
        a.add(lbSonando, c);
        c.gridy++;
        a.add(lbEstado, c);
        c.gridy++;
        c.insets = new Insets(12, 0, 12, 0);
        ecualizador.setPreferredSize(new Dimension(300, 90));
        ecualizador.setMinimumSize(new Dimension(100, 60));
        a.add(ecualizador, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        JPanel barra = new JPanel(new BorderLayout(10, 0));
        barra.setOpaque(false);
        progreso.setPreferredSize(new Dimension(10, 6));
        barra.add(progreso, BorderLayout.CENTER);
        barra.add(lbTiempo, BorderLayout.EAST);
        a.add(barra, c);
        c.gridy++;
        c.insets = new Insets(12, 0, 0, 0);
        JPanel controles = new JPanel(new BorderLayout(16, 0));
        controles.setOpaque(false);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        botones.setOpaque(false);
        btPlay.setPreferredSize(new Dimension(52, 52));
        Tema.estiloPrincipal(btPlay, Tema.ACENTO);
        btPlay.putClientProperty("FlatLaf.style", btPlay.getClientProperty("FlatLaf.style") + ";arc:999");
        btPlay.addActionListener(e -> playPausa());
        btStop.addActionListener(e -> detenerCancion());
        btSiguiente.addActionListener(e -> siguiente());
        botones.add(btPlay);
        botones.add(btStop);
        botones.add(btSiguiente);
        controles.add(botones, BorderLayout.WEST);
        JPanel volumen = new JPanel(new BorderLayout(0, 6));
        volumen.setOpaque(false);
        volumen.setBorder(new EmptyBorder(6, 0, 0, 0));
        volumen.add(conIcono(Iconos.Tipo.PARLANTE, slMusica), BorderLayout.NORTH);
        volumen.add(vuSalida, BorderLayout.CENTER);
        slMusica.addChangeListener(e -> { if (mezclador != null) mezclador.setVolMusica(slMusica.getValue() / 100f); });
        controles.add(volumen, BorderLayout.CENTER);
        a.add(controles, c);
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        chMonitor.addActionListener(e -> { if (mezclador != null) mezclador.setMonitor(chMonitor.isSelected()); });
        a.add(chMonitor, c);
        col.add(ahora, BorderLayout.NORTH);

        Tarjeta tLista = new Tarjeta("Lista de reproducción · solo MP3");
        JPanel l = tLista.contenido();
        l.setLayout(new BorderLayout(0, 10));
        jlLista.setCellRenderer(new RenderCancion());
        jlLista.setBackground(Tema.TARJETA);
        jlLista.setFixedCellHeight(36);
        jlLista.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jlLista.getSelectedIndex() >= 0) reproducir(jlLista.getSelectedIndex());
            }
        });
        jlLista.setTransferHandler(new SoltarArchivos());
        JScrollPane sp = new JScrollPane(jlLista);
        sp.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sp.getViewport().setBackground(Tema.TARJETA);
        l.add(sp, BorderLayout.CENTER);
        JPanel acciones = new JPanel(new BorderLayout());
        acciones.setOpaque(false);
        JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        izq.setOpaque(false);
        JButton btAgregar = new JButton("Agregar MP3", Iconos.de(Iconos.Tipo.MAS, 14, Tema.TEXTO));
        btAgregar.addActionListener(e -> elegirArchivos());
        JButton btQuitar = new JButton("Quitar", Iconos.de(Iconos.Tipo.MENOS, 14, Tema.TEXTO));
        btQuitar.addActionListener(e -> quitarSeleccionadas());
        izq.add(btAgregar);
        izq.add(Box.createHorizontalStrut(8));
        izq.add(btQuitar);
        acciones.add(izq, BorderLayout.WEST);
        acciones.add(Tema.etiqueta("Arrastra archivos aquí · doble clic para reproducir", Font.PLAIN, 11f, Tema.TEXTO_SUAVE),
                BorderLayout.EAST);
        l.add(acciones, BorderLayout.SOUTH);
        col.add(tLista, BorderLayout.CENTER);
        return col;
    }

    private JComponent columnaDerecha() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(300, 10));
        Tarjeta tPersonas = new Tarjeta("En tu sala");
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

    private static GridBagConstraints filaCompleta() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static JComponent conIcono(Iconos.Tipo tipo, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.add(new JLabel(Iconos.de(tipo, 16, Tema.TEXTO_SUAVE)), BorderLayout.WEST);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ conexión

    private void alternarConexion() {
        if (conexion != null) {
            desconectar();
            lbEstadoConexion.setText("Saliste del aire.");
            actualizarControles();
            return;
        }
        String nombre = txNombre.getText().strip();
        String servidor = txServidor.getText().strip();
        if (nombre.isEmpty() || servidor.isEmpty()) {
            lbEstadoConexion.setText("Escribe el servidor y tu nombre.");
            return;
        }
        Sala sala = (Sala) cbSala.getSelectedItem();
        try {
            conexion = new ConexionRadio(servidor, Protocolo.LOCUTOR, sala, nombre, new String(txClave.getPassword()), this);
        } catch (IllegalArgumentException e) {
            lbEstadoConexion.setText("Dirección no válida: " + e.getMessage());
            return;
        }
        conexion.connect();
        lbEstadoConexion.setText("Conectando con " + servidor + "…");
        chat.limpiar();
        actualizarControles();
    }

    private void desconectar() {
        ConexionRadio c = conexion;
        conexion = null;
        if (c != null) c.salir();
        detenerTransmision();
    }

    private void detenerTransmision() {
        if (microfono.activo()) microfono.cerrar();
        btMic.setSelected(false);
        Mezclador mz = mezclador;
        mezclador = null;
        if (mz != null) {
            mz.setCancion(null, null);
            mz.detener();
        }
        indiceActual = -1;
        chat.setActivo(false);
        personas.limpiar();
    }

    @Override
    public void dentro(Sala sala) {
        SwingUtilities.invokeLater(() -> {
            ConexionRadio c = conexion;
            if (c == null) return;
            Mezclador mz = new Mezclador(microfono, c::enviarAudio);
            mz.setVolMusica(slMusica.getValue() / 100f);
            mz.setVolMicrofono(slMic.getValue() / 100f);
            mz.setAtenuarAlHablar(chAtenuar.isSelected());
            mz.setMonitor(chMonitor.isSelected());
            mz.iniciar();
            mezclador = mz;
            chat.setActivo(true);
            lbEstadoConexion.setText("<html>Al aire en la sala <b>" + sala.titulo + "</b>.</html>");
            enviarInfo();
            actualizarControles();
        });
    }

    @Override
    public void cerrada(String motivo) {
        SwingUtilities.invokeLater(() -> {
            if (motivo == null) return;      // la cerró el usuario
            conexion = null;
            detenerTransmision();
            lbEstadoConexion.setText("<html><span style='color:#FFC83D'>" + motivo + "</span></html>");
            chat.aviso(motivo);
            actualizarControles();
        });
    }

    @Override
    public void salas(List<ConexionRadio.InfoSala> salas) {
        // el locutor solo se ocupa de su propia sala
    }

    @Override
    public void oyentes(Sala sala, String locutor, List<String> oyentes) {
        SwingUtilities.invokeLater(() -> personas.mostrar(locutor, oyentes));
    }

    @Override
    public void info(Sala sala, String titulo, boolean mic, String locutor) {
    }

    @Override
    public void chat(Sala sala, String hora, String autor, boolean esLocutor, String texto) {
        SwingUtilities.invokeLater(() -> chat.mensaje(hora, autor, esLocutor, texto));
    }

    @Override
    public void aviso(String texto) {
        SwingUtilities.invokeLater(() -> chat.aviso(texto));
    }

    /** Le cuenta al servidor qué suena y si el micrófono está abierto. */
    private void enviarInfo() {
        ConexionRadio c = conexion;
        if (c == null || !c.estaDentro()) return;
        Mezclador mz = mezclador;
        FuenteMp3 cancion = mz != null ? mz.cancion() : null;
        String titulo = cancion == null ? "" : (mz.pausado() ? "(en pausa) " : "") + cancion.titulo();
        c.enviarInfo(titulo, microfono.activo());
    }

    // ------------------------------------------------------------------ micrófono y música

    private void alternarMicrofono() {
        if (btMic.isSelected()) {
            try {
                microfono.abrir();
            } catch (LineUnavailableException | SecurityException | IllegalArgumentException e) {
                btMic.setSelected(false);
                JOptionPane.showMessageDialog(this, "No se pudo abrir el micrófono.\n\n" + e.getMessage(),
                        "Micrófono", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            microfono.cerrar();
        }
        enviarInfo();
        actualizarControles();
    }

    private void playPausa() {
        if (mezclador == null) return;
        if (mezclador.cancion() == null) {
            if (lista.isEmpty()) {
                elegirArchivos();
                if (lista.isEmpty()) return;
            }
            reproducir(Math.max(0, jlLista.getSelectedIndex()));
        } else {
            mezclador.setPausado(!mezclador.pausado());
            enviarInfo();
        }
        actualizarControles();
    }

    private void reproducir(int i) {
        if (mezclador == null) {
            JOptionPane.showMessageDialog(this, "Primero sal al aire en una sala.", "Sin conexión", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (i < 0 || i >= lista.size()) return;
        indiceActual = i;
        FuenteMp3 fuente = new FuenteMp3(lista.get(i));
        mezclador.setPausado(false);
        mezclador.setCancion(fuente, () -> SwingUtilities.invokeLater(() -> cancionTerminada(fuente)));
        jlLista.setSelectedIndex(i);
        enviarInfo();
        actualizarControles();
    }

    private void cancionTerminada(FuenteMp3 fuente) {
        if (fuente.error() != null) chat.aviso(fuente.error());
        if (mezclador == null || mezclador.cancion() != null) return;
        if (indiceActual + 1 < lista.size()) {
            reproducir(indiceActual + 1);
        } else {
            indiceActual = -1;
            enviarInfo();
            actualizarControles();
        }
    }

    private void detenerCancion() {
        if (mezclador == null) return;
        mezclador.setCancion(null, null);
        indiceActual = -1;
        enviarInfo();
        actualizarControles();
    }

    private void siguiente() {
        if (!lista.isEmpty()) reproducir((indiceActual + 1) % lista.size());
    }

    private void elegirArchivos() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(new FileNameExtensionFilter("Audio MP3 (*.mp3)", "mp3"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) agregar(List.of(fc.getSelectedFiles()));
    }

    private void agregar(List<File> archivos) {
        List<String> rechazados = new ArrayList<>();
        for (File f : archivos) {
            if (f.isDirectory()) {
                File[] hijos = f.listFiles();
                if (hijos != null) agregar(List.of(hijos));
            } else if (FuenteMp3.esMp3Valido(f)) {
                lista.addElement(f);
            } else {
                rechazados.add(f.getName());
            }
        }
        if (!rechazados.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Solo se aceptan archivos MP3 válidos. No se agregaron:\n• "
                    + String.join("\n• ", rechazados), "Formato no permitido", JOptionPane.WARNING_MESSAGE);
        }
        actualizarControles();
    }

    private void quitarSeleccionadas() {
        int[] sel = jlLista.getSelectedIndices();
        for (int k = sel.length - 1; k >= 0; k--) {
            int i = sel[k];
            if (i == indiceActual) detenerCancion();
            else if (i < indiceActual) indiceActual--;
            lista.remove(i);
        }
        actualizarControles();
    }

    // ------------------------------------------------------------------ estado

    private void actualizarControles() {
        boolean conectando = conexion != null;
        boolean alAire = mezclador != null;
        btAire.setText(conectando ? (alAire ? "Terminar transmisión" : "Cancelar") : "Salir al aire");
        Tema.estiloPrincipal(btAire, conectando ? new Color(0x3D, 0x38, 0x60) : Tema.ACENTO);
        for (JComponent c : new JComponent[]{txServidor, cbSala, txNombre, txClave}) c.setEnabled(!conectando);

        btMic.setEnabled(alAire);
        btMic.setText(microfono.activo() ? "Cerrar micrófono" : "Abrir micrófono");
        btMic.setIcon(Iconos.de(microfono.activo() ? Iconos.Tipo.MIC : Iconos.Tipo.MIC_OFF, 22, Color.WHITE));
        Tema.estiloPrincipal(btMic, microfono.activo() ? Tema.ROJO : new Color(0x3D, 0x38, 0x60));
        btPlay.setEnabled(alAire);
        btStop.setEnabled(alAire);
        btSiguiente.setEnabled(alAire && !lista.isEmpty());

        FuenteMp3 c = alAire ? mezclador.cancion() : null;
        boolean sonando = c != null && !mezclador.pausado();
        btPlay.setIcon(Iconos.de(sonando ? Iconos.Tipo.PAUSA : Iconos.Tipo.PLAY, 20, Color.WHITE));
        lbSonando.setText(c != null ? c.titulo() : (microfono.activo() ? "Micrófono abierto" : "Nada sonando"));
        if (!alAire) lbEstado.setText("Conéctate a una sala para transmitir");
        else if (c != null) lbEstado.setText(mezclador.pausado() ? "En pausa" : "Transmitiendo en MP3 · " + Protocolo.KBPS + " kbps");
        else if (lista.isEmpty()) lbEstado.setText("Agrega canciones MP3 a la lista");
        else lbEstado.setText("Pulsa reproducir o haz doble clic en una canción");

        Sala sala = (Sala) cbSala.getSelectedItem();
        cabecera.setEnVivo(alAire, alAire ? (microfono.activo() ? "EN VIVO · MIC ABIERTO" : "AL AIRE") : (conectando ? "CONECTANDO…" : "FUERA DEL AIRE"));
        cabecera.setSubtitulo(alAire ? "Locutor · Sala " + sala.titulo + " · " + txNombre.getText().strip() : "Locutor · sin conectar");
        jlLista.repaint();
    }

    private void animar() {
        Mezclador mz = mezclador;
        vuMic.setNivel(mz != null ? mz.nivelMic() : 0f);
        vuSalida.setNivel(mz != null ? mz.nivelSalida() : 0f);
        ecualizador.setBandas(mz != null ? mz.bandas() : null);
        FuenteMp3 c = mz != null ? mz.cancion() : null;
        if (c != null) {
            long pos = c.posicionMs(), dur = c.duracionMs();
            progreso.setValue(dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0);
            lbTiempo.setText(tiempo(pos) + " / " + tiempo(dur));
        } else {
            progreso.setValue(0);
            lbTiempo.setText("0:00 / 0:00");
        }
    }

    private static String tiempo(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ renderizadores

    /** Muestra cada sala con su color. */
    static class RenderSala extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foco) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foco);
            if (v instanceof Sala s) {
                c.setText(s.titulo);
                c.setIcon(new Icon() {
                    public void paintIcon(Component comp, Graphics g, int x, int y) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(s.color.brighter());
                        g2.fillOval(x, y + 1, 10, 10);
                        g2.dispose();
                    }

                    public int getIconWidth() {
                        return 12;
                    }

                    public int getIconHeight() {
                        return 12;
                    }
                });
            }
            return c;
        }
    }

    private class RenderCancion extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foco) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foco);
            boolean actual = i == indiceActual && mezclador != null && mezclador.cancion() != null;
            c.setText(String.format("%02d   %s", i + 1, FuenteMp3.titulo((File) v)));
            c.setBorder(new EmptyBorder(0, 12, 0, 12));
            c.setIcon(actual ? Iconos.de(Iconos.Tipo.PARLANTE, 16, Tema.ACENTO) : null);
            c.setIconTextGap(10);
            if (actual) {
                c.setForeground(Tema.ACENTO);
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            }
            if (!sel) c.setBackground(i % 2 == 0 ? Tema.TARJETA : new Color(0x24, 0x21, 0x35));
            return c;
        }
    }

    private class SoltarArchivos extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                agregar((List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Tema.aplicar();
            LocutorApp app = new LocutorApp();
            if (args.length > 0) app.txServidor.setText(args[0]);
            app.setVisible(true);
        });
    }
}
