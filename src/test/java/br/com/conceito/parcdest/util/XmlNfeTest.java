package br.com.conceito.parcdest.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XmlNfeTest {

    /** Trecho real do XML importado na base de simulacao (TGFIXN.XML, NUARQUIVO=3). */
    private static final String XML_REAL =
        "<infAdic><infCpl>MERC. ENVIADA PARA CARLOS ERIVELTON HENING</infCpl></infAdic>"
      + "<compra><xPed>1</xPed></compra>"
      + "<infRespTec><CNPJ>82161035000177</CNPJ><xContato>Jacques Ricardo Tesch</xContato></infRespTec>";

    @Test
    void extraiOXPedDoXmlReal() {
        assertEquals("1", XmlNfe.xPed(XML_REAL));
    }

    @Test
    void aceitaEspacosEXPedLongo() {
        assertEquals("1400306752", XmlNfe.xPed("<compra><xPed> 1400306752 </xPed></compra>"));
    }

    @Test
    void ignoraXPedDeForaDoGrupoCompra() {
        assertNull(XmlNfe.xPed("<det><xPed>999</xPed></det><compra><xNEmp>7</xNEmp></compra>"));
    }

    @Test
    void naoAtuaQuandoNaoHaInformacao() {
        assertNull(XmlNfe.xPed(null));
        assertNull(XmlNfe.xPed("<infNFe><emit/></infNFe>"));
        assertNull(XmlNfe.xPed("<compra><xPed></xPed></compra>"));
        assertNull(XmlNfe.xPed("<compra><xPed>   </xPed></compra>"));
    }
}
