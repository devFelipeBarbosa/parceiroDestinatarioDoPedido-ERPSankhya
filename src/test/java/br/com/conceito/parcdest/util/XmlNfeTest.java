package br.com.conceito.parcdest.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Grupo I05: o xPed vem item a item, e o grupo <compra> nem existe. */
    private static final String XML_XPED_NO_ITEM =
        "<det nItem=\"1\"><prod><cProd>A1</cProd><xPed>2051</xPed><nItemPed>1</nItemPed></prod></det>"
      + "<det nItem=\"2\"><prod><cProd>A2</cProd><xPed>2051</xPed><nItemPed>2</nItemPed></prod></det>";

    /** Grupo G: entrega em endereco diferente do destinatario. */
    private static final String XML_COM_ENTREGA =
        "<dest><CNPJ>99999999000191</CNPJ></dest>"
      + "<entrega><CNPJ>11.222.333/0001-44</CNPJ><xNome>TERCEIRO</xNome><UF>SC</UF></entrega>"
      + "<det nItem=\"1\"><prod><cProd>A1</cProd></prod></det>";

    @Test
    void xPedDoItemEDistintoEIgnoraORepetido() {
        List<String> xPeds = XmlNfe.xPedsDosItens(XML_XPED_NO_ITEM);

        assertEquals(1, xPeds.size(), "dois itens do mesmo pedido valem um xPed");
        assertEquals("2051", xPeds.get(0));
        assertNull(XmlNfe.xPed(XML_XPED_NO_ITEM), "sem grupo <compra> nao ha xPed de documento");
    }

    @Test
    void xPedDoDocumentoNaoEntraNaListaDosItens() {
        assertTrue(XmlNfe.xPedsDosItens(XML_REAL).isEmpty(), "o xPed de <compra> nao e de item");
        assertEquals("1", XmlNfe.xPed(XML_REAL));
    }

    @Test
    void notaQueAtendeDoisPedidosDevolveOsDois() {
        String xml = "<det><prod><xPed>10</xPed></prod></det><det><prod><xPed>20</xPed></prod></det>";

        assertEquals(2, XmlNfe.xPedsDosItens(xml).size());
    }

    @Test
    void documentoDeEntregaVemSoComDigitos() {
        assertEquals("11222333000144", XmlNfe.documentoEntrega(XML_COM_ENTREGA));
    }

    @Test
    void semGrupoEntregaNaoHaRecebedor() {
        assertNull(XmlNfe.documentoEntrega(XML_REAL));
        assertNull(XmlNfe.documentoEntrega(null));
    }

    @Test
    void cnpjDoRespTecNaoEConfundidoComEntrega() {
        assertNull(XmlNfe.documentoEntrega(XML_REAL), "o CNPJ do responsavel tecnico nao e recebedor");
    }
}
