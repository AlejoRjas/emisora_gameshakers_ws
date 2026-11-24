package org.gameshakers.comun.ui;

import org.gameshakers.comun.Protocolo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.util.function.Consumer;

/** Conversación de una sala: muestra los mensajes y permite escribir. */
public class PanelChat extends JPanel {

    private final JTextPane mensajes = new JTextPane();
    private final JTextField entrada = new JTextField();
    private final JButton btEnviar = Tema.botonPrincipal("Enviar", Tema.ACENTO);
    private final Consumer<String> alEnviar;
    private String yo = "";

    public PanelChat(Consumer<String> alEnviar) {
        super(new BorderLayout(0, 10));
        this.alEnviar = alEnviar;
        setOpaque(false);
        mensajes.setEditable(false);
        mensajes.setBackground(Tema.TARJETA);
        mensajes.setBorder(new EmptyBorder(8, 10, 8, 10));
        mensajes.setFont(Tema.fuente(Font.PLAIN, 14f));
        JScrollPane sp = new JScrollPane(mensajes);
        sp.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sp.getViewport().setBackground(Tema.TARJETA);
        add(sp, BorderLayout.CENTER);

        JPanel fila = new JPanel(new BorderLayout(8, 0));
        fila.setOpaque(false);
        entrada.putClientProperty("JTextField.placeholderText", "Escribe un mensaje y pulsa Enter…");
        entrada.setFont(Tema.fuente(Font.PLAIN, 14f));
        entrada.setPreferredSize(new Dimension(10, 40));
        ((AbstractDocument) entrada.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void replace(FilterBypass fb, int off, int len, String txt, AttributeSet a) throws BadLocationException {
                int nuevo = fb.getDocument().getLength() - len + (txt == null ? 0 : txt.length());
                if (nuevo <= Protocolo.MAX_CHAT) super.replace(fb, off, len, txt, a);
            }
        });
        entrada.addActionListener(e -> enviar());
        btEnviar.addActionListener(e -> enviar());
        btEnviar.setPreferredSize(new Dimension(100, 40));
        fila.add(entrada, BorderLayout.CENTER);
        fila.add(btEnviar, BorderLayout.EAST);
        add(fila, BorderLayout.SOUTH);
        setActivo(false);
    }

    private void enviar() {
        String t = entrada.getText().strip();
        if (t.isEmpty()) return;
        alEnviar.accept(t);
        entrada.setText("");
    }

    public void setActivo(boolean activo) {
        entrada.setEnabled(activo);
        btEnviar.setEnabled(activo);
        if (activo) entrada.requestFocusInWindow();
    }

    public void setYo(String nombre) {
        yo = nombre == null ? "" : nombre;
    }

    public void limpiar() {
        mensajes.setText("");
    }

    public void mensaje(String hora, String autor, String texto) {
        StyledDocument d = mensajes.getStyledDocument();
        boolean mio = autor.equalsIgnoreCase(yo);
        try {
            d.insertString(d.getLength(), hora + "  ", estilo(Tema.TEXTO_SUAVE, false));
            Color color = mio ? Tema.ACENTO : Color.getHSBColor((Math.abs(autor.hashCode()) % 360) / 360f, 0.45f, 0.95f);
            d.insertString(d.getLength(), autor + (mio ? " (tú)" : "") + ": ", estilo(color, true));
            d.insertString(d.getLength(), texto + "\n", estilo(Tema.TEXTO, false));
        } catch (BadLocationException ignorada) {
            // no ocurre al insertar al final
        }
        mensajes.setCaretPosition(d.getLength());
    }

    /** Aviso del sistema: alguien entró o salió, cambio de sala, etc. */
    public void aviso(String texto) {
        StyledDocument d = mensajes.getStyledDocument();
        try {
            d.insertString(d.getLength(), "• " + texto + "\n", estilo(Tema.AMARILLO, false));
        } catch (BadLocationException ignorada) {
            // no ocurre al insertar al final
        }
        mensajes.setCaretPosition(d.getLength());
    }

    private static SimpleAttributeSet estilo(Color c, boolean negrita) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, negrita);
        return a;
    }
}
