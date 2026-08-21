package br.com.conceito.parcdest.service;

import br.com.conceito.parcdest.repository.PortalXmlRepository;
import br.com.conceito.parcdest.util.Log;
import br.com.conceito.parcdest.util.XmlNfe;
import br.com.sankhya.jape.dao.JdbcWrapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * FASE 2. Resolve o Parceiro Destinatario que o Portal de Importacao de XML nao preenche.
 *
 * Cadeia deterministica, comprovada por tracing e pelo Monitor de Consultas (secao 14.2):
 *
 * <pre>
 * CHAVENFE (VO, no beforeInsert)
 *   -> TGFIXN.XML  WHERE CHAVEACESSO = :chave
 *   -> &lt;compra&gt;&lt;xPed&gt;  = numero do Pedido de Compra
 *   -> TGFCAB do Pedido    -> CODPARCDEST
 * </pre>
 *
 * Nao ha heuristica: {@code xPed} e o mesmo campo que o proprio Portal usa para casar o
 * pedido, so que tarde demais. Em qualquer condicao ambigua nao atua (secao 16).
 */
public final class ResolvedorParceiroDestinatario {

    private ResolvedorParceiroDestinatario() {}

    /**
     * Destinatario a gravar, ou {@code null} quando o workaround nao deve atuar. Todo
     * caminho que devolve null registra o motivo no log.
     */
    public static BigDecimal resolver(JdbcWrapper jdbc, String chaveNfe,
                                      BigDecimal codParc, BigDecimal codEmp) throws Exception {

        if (chaveNfe == null || chaveNfe.trim().isEmpty()) {
            Log.info("workaround=SKIP motivo=SEM_CHAVENFE");
            return null;
        }
        if (codParc == null || codEmp == null) {
            Log.info("workaround=SKIP motivo=SEM_PARCEIRO_OU_EMPRESA");
            return null;
        }

        String xml = PortalXmlRepository.xmlDaChave(jdbc, chaveNfe.trim());
        if (xml == null) {
            Log.info("workaround=SKIP motivo=XML_NAO_ENCONTRADO chave=" + chaveNfe);
            return null;
        }

        String xPed = XmlNfe.xPed(xml);
        if (xPed == null) {
            Log.info("workaround=SKIP motivo=XML_SEM_XPED chave=" + chaveNfe);
            return null;
        }

        BigDecimal numeroPedido = comoNumero(xPed);
        if (numeroPedido == null) {
            Log.info("workaround=SKIP motivo=XPED_NAO_NUMERICO xPed=" + xPed);
            return null;
        }

        List<BigDecimal> candidatos =
            PortalXmlRepository.destinatariosDoPedido(jdbc, numeroPedido, codParc, codEmp);

        if (candidatos.isEmpty()) {
            Log.info("workaround=SKIP motivo=PEDIDO_NAO_ENCONTRADO xPed=" + xPed
                   + " CODPARC=" + codParc + " CODEMP=" + codEmp);
            return null;
        }
        // Secao 15: mais de um destinatario possivel e divergencia, nao escolha.
        if (candidatos.size() > 1) {
            Log.info("workaround=SKIP motivo=DESTINATARIOS_DIVERGENTES xPed=" + xPed
                   + " candidatos=" + candidatos);
            return null;
        }

        BigDecimal destinatario = candidatos.get(0);
        if (destinatario == null || destinatario.signum() <= 0) {
            Log.info("workaround=SKIP motivo=PEDIDO_SEM_DESTINATARIO xPed=" + xPed);
            return null;
        }

        Log.info("workaround=RESOLVIDO xPed=" + xPed + " CODPARCDEST=" + destinatario);
        return destinatario;
    }

    private static BigDecimal comoNumero(String texto) {
        try {
            return new BigDecimal(texto.trim());
        } catch (NumberFormatException naoENumero) {
            return null;
        }
    }
}
