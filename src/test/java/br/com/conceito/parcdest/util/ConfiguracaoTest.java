package br.com.conceito.parcdest.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguracaoTest {

    private static Properties props(String... paresChaveValor) {
        Properties p = new Properties();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            p.setProperty(paresChaveValor[i], paresChaveValor[i + 1]);
        }
        return p;
    }

    @Test
    void arquivoAusenteUsaDefaultSeguro() {
        Configuracao c = Configuracao.parse(new Properties());

        assertTrue(c.tracingAtivo(), "tracing nasce ligado: instalou, observa");
        assertFalse(c.workaroundAtivo(), "secao 16: alteracao de dado nasce desligada");
        assertTrue(c.topElegivel(new BigDecimal("1419")));
    }

    @Test
    void workaroundSoLigaComValorExplicito() {
        assertTrue(Configuracao.parse(props("workaround", "ON")).workaroundAtivo());
        assertTrue(Configuracao.parse(props("workaround", "on")).workaroundAtivo());
        assertTrue(Configuracao.parse(props("workaround", "S")).workaroundAtivo());

        assertFalse(Configuracao.parse(props("workaround", "OFF")).workaroundAtivo());
        assertFalse(Configuracao.parse(props("workaround", "")).workaroundAtivo());
        assertFalse(Configuracao.parse(props("workaround", "talvez")).workaroundAtivo());
    }

    @Test
    void tracingDesligaExplicitamente() {
        assertFalse(Configuracao.parse(props("tracing", "OFF")).tracingAtivo());
    }

    @Test
    void escalaDaTopNaoMudaOEscopo() {
        Configuracao c = Configuracao.parse(props("tops", "1419"));

        assertTrue(c.topElegivel(new BigDecimal("1419")));
        assertTrue(c.topElegivel(new BigDecimal("1419.00")), "TGFCAB devolve CODTIPOPER com escala");
    }

    @Test
    void topForaDaListaNaoEntraNoEscopo() {
        Configuracao c = Configuracao.parse(props("tops", "1419, 1420"));

        assertTrue(c.topElegivel(new BigDecimal("1420")));
        assertFalse(c.topElegivel(new BigDecimal("1314")), "TOP do Pedido de Compra nao e alvo");
        assertFalse(c.topElegivel(null));
    }

    @Test
    void listaDeTopsInvalidaNaoAbreEscopo() {
        Configuracao c = Configuracao.parse(props("tops", "abc, , 1419"));

        assertTrue(c.topElegivel(new BigDecimal("1419")));
        assertFalse(c.topElegivel(new BigDecimal("0")));
    }

    @Test
    void camposExtrasSaoAparadosEIgnoramVazios() {
        Configuracao c = Configuracao.parse(props("camposExtras", " NUNOTAORIG , ,STATUSNOTA "));

        assertEquals(2, c.camposExtras().size());
        assertEquals("NUNOTAORIG", c.camposExtras().get(0));
        assertEquals("STATUSNOTA", c.camposExtras().get(1));
    }
}
