package org.gameshakers.servidor;

import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Protocolo.Sala;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Páginas para ver desde el navegador que el servidor está vivo y cómo están las salas.
 *   GET /estado     página con las tres salas (el chat web está en /, archivo static/index.html)
 *   GET /api/salas  lo mismo en JSON
 */
@RestController
public class EstadoController {

    private final SalasChat salas;

    public EstadoController(SalasChat salas) {
        this.salas = salas;
    }

    @GetMapping("/api/salas")
    public Map<String, Object> salas() {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Sala s : Sala.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("nombre", s.titulo);
            m.put("usuarios", salas.nombres(s));
            m.put("mensajesEnHistorial", salas.mensajesEnHistorial(s));
            lista.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("servicio", Protocolo.NOMBRE_CHAT);
        r.put("websocket", "/chat");
        r.put("mensajesPor", salas.descripcionDifusor());
        r.put("mensajesDesdeQueArranco", salas.mensajesTotales());
        r.put("salas", lista);
        return r;
    }

    @GetMapping(value = "/estado", produces = MediaType.TEXT_HTML_VALUE)
    public String inicio() {
        StringBuilder tarjetas = new StringBuilder();
        for (Sala s : Sala.values()) {
            List<String> n = salas.nombres(s);
            String color = String.format("#%02X%02X%02X", s.color.getRed(), s.color.getGreen(), s.color.getBlue());
            tarjetas.append("<div class='sala' style='background:").append(color).append("'><h2>").append(s.titulo)
                    .append("</h2><p class='n'>").append(n.size()).append(n.size() == 1 ? " persona" : " personas")
                    .append("</p><p>").append(n.isEmpty() ? "Nadie por ahora" : escapar(String.join(", ", n)))
                    .append("</p><p class='h'>").append(salas.mensajesEnHistorial(s))
                    .append(salas.mensajesEnHistorial(s) == 1 ? " mensaje" : " mensajes").append(" en el historial</p></div>");
        }
        return """
                <!doctype html><html lang="es"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="refresh" content="5"><title>GameShakers Chat</title>
                <style>
                body{margin:0;font-family:system-ui,sans-serif;background:#14121F;color:#ECEAF5}
                header{padding:24px;background:linear-gradient(120deg,#7C4DFF,#FF3D7F)}
                h1{margin:0;font-size:26px} header p{margin:6px 0 0;opacity:.85}
                main{padding:20px;display:grid;gap:16px;grid-template-columns:repeat(auto-fit,minmax(220px,1fr))}
                .sala{border-radius:16px;padding:18px;color:#fff} .sala h2{margin:0 0 6px}
                .n{font-size:20px;font-weight:700;margin:4px 0} .h{opacity:.75;font-size:13px}
                footer{padding:0 20px 24px;color:#9A96B5;font-size:14px} code{color:#ECEAF5}
                </style></head><body>
                <header><h1>GameShakers Chat</h1><p>Servidor en línea · 3 salas por WebSocket</p></header>
                <main>%s</main>
                <footer><a href="/" style="color:#FF3D7F">Entrar al chat</a> · o con el cliente <code>chat.jar</code> (WebSocket <code>/chat</code>).
                Mensajes por: %s. Esta página se actualiza cada 5 s.</footer>
                </body></html>
                """.formatted(tarjetas, escapar(salas.descripcionDifusor()));
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
