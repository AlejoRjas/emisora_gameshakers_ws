package org.gameshakers.comun.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** Quién está en la sala. */
public class PanelPersonas extends JPanel {

    private final DefaultListModel<String[]> modelo = new DefaultListModel<>();   // {nombre, rol}
    private final JLabel lbTotal = Tema.etiqueta("0 personas", Font.BOLD, 13f, Tema.TEXTO);

    public PanelPersonas() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        add(lbTotal, BorderLayout.NORTH);
        JList<String[]> lista = new JList<>(modelo);
        lista.setBackground(Tema.TARJETA);
        lista.setFixedCellHeight(38);
        lista.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foco) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, false, false);
                String[] p = (String[]) v;
                c.setText("<html><b>" + escapar(p[0]) + "</b>" + (p[1].isEmpty() ? "" : "  <span style='color:#FF3D7F'>" + p[1] + "</span>") + "</html>");
                c.setIcon(new Avatar(p[0], !p[1].isEmpty()));
                c.setIconTextGap(10);
                c.setBorder(new EmptyBorder(2, 2, 2, 2));
                c.setBackground(Tema.TARJETA);
                return c;
            }
        });
        JScrollPane sp = new JScrollPane(lista);
        sp.setBorder(null);
        sp.getViewport().setBackground(Tema.TARJETA);
        add(sp, BorderLayout.CENTER);
    }

    /** @param yo tu propio nombre, que aparece marcado como "tú" */
    public void mostrar(List<String> nombres, String yo) {
        modelo.clear();
        for (String n : nombres) modelo.addElement(new String[]{n, n.equalsIgnoreCase(yo) ? "tú" : ""});
        lbTotal.setText(nombres.size() == 1 ? "1 persona" : nombres.size() + " personas");
    }

    public void limpiar() {
        mostrar(List.of(), "");
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Círculo de color con la inicial. */
    private record Avatar(String nombre, boolean resaltado) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(resaltado ? Tema.ACENTO : Color.getHSBColor((Math.abs(nombre.hashCode()) % 360) / 360f, 0.55f, 0.85f));
            g2.fillOval(x, y, 28, 28);
            g2.setColor(Color.WHITE);
            g2.setFont(Tema.fuente(Font.BOLD, 13f));
            String ini = nombre.isEmpty() ? "?" : nombre.substring(0, 1).toUpperCase();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(ini, x + (28 - fm.stringWidth(ini)) / 2, y + (28 + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 28;
        }

        @Override
        public int getIconHeight() {
            return 28;
        }
    }
}
