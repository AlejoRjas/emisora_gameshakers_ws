package org.gameshakers.comun.ui;

import org.gameshakers.comun.Protocolo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.util.function.Consumer;

/** Chat de una sala: muestra los mensajes y permite escribir. */
public class PanelChat extends JPanel {

    private final JTextPane mensajes = new JTextPane();
    private final JTextField entrada = new JTextField();
    private final JButton btEnviar = new JButton("Enviar");
    private final Consumer<String> alEnviar;

    public PanelChat(Consumer<String> alEnviar) {
        super(new BorderLayout(0, 8));
        this.alEnviar = alEnviar;
        setOpaque(false);
        mensajes.setEditable(false);
        mensajes.setBackground(Tema.TARJETA);
        mensajes.setBorder(new EmptyBorder(4, 4, 4, 4));
        JScrollPane sp = new JScrollPane(mensajes);
        sp.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sp.getViewport().setBackground(Tema.TARJETA);
        add(sp, BorderLayout.CENTER);

        JPanel fila = new JPanel(new BorderLayout(8, 0));
        fila.setOpaque(false);
        entrada.putClientProperty("JTextField.placeholderText", "Escribe un mensaje…");
        ((AbstractDocument) entrada.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void replace(FilterBypass fb, int off, int len, String txt, AttributeSet a) throws BadLocationException {
                int nuevo = fb.getDocument().getLength() - len + (txt == null ? 0 : txt.length());
                if (nuevo <= Protocolo.MAX_CHAT) super.replace(fb, off, len, txt, a);
            }
        });
        entrada.addActionListener(e -> enviar());
        btEnviar.addActionListener(e -> enviar());
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
    }

    public void limpiar() {
        mensajes.setText("");
    }

    /** Agrega un mensaje de una persona. */
    public void mensaje(String hora, String autor, boolean esLocutor, String texto) {
        StyledDocument d = mensajes.getStyledDocument();
        try {
            d.insertString(d.getLength(), hora + "  ", estilo(Tema.TEXTO_SUAVE, false));
            Color color = esLocutor ? Tema.ACENTO : Color.getHSBColor((Math.abs(autor.hashCode()) % 360) / 360f, 0.45f, 0.95f);
            d.insertString(d.getLength(), autor + (esLocutor ? " (locutor)" : "") + ": ", estilo(color, true));
            d.insertString(d.getLength(), texto + "\n", estilo(Tema.TEXTO, false));
        } catch (BadLocationException ignorada) {
            // no ocurre al insertar al final
        }
        mensajes.setCaretPosition(d.getLength());
    }

    /** Agrega un aviso del sistema (alguien salió, cambio de sala, etc.). */
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
