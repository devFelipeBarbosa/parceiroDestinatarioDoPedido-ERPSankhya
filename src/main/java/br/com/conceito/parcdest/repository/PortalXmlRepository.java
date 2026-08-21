package br.com.conceito.parcdest.repository;

import br.com.sankhya.jape.dao.JdbcWrapper;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas do workaround. Todas rodam no {@link JdbcWrapper} do proprio evento, ou seja
 * na conexao da transacao que esta criando a nota — nao abre sessao nem conexao propria.
 *
 * Somente leitura. Nenhuma das duas tabelas e escrita pela transacao antes do INSERT do
 * cabecalho (comprovado pelo Monitor de Consultas, secao 14.1 da arquitetura).
 */
public final class PortalXmlRepository {

    /** Arquivo do Portal de Importacao de XML, endereçado pela chave de acesso. */
    private static final String SQL_XML =
        "SELECT XML FROM TGFIXN WHERE CHAVEACESSO = ?";

    /**
     * Destinatarios distintos dos Pedidos de Compra candidatos.
     *
     * TIPMOV = 'O' restringe a pedido; PENDENTE = 'S' reproduz a mesma exigencia do Portal
     * e, de quebra, da idempotencia: pedido ja atendido vira 'N' e sai do conjunto.
     */
    private static final String SQL_DESTINATARIOS =
        "SELECT DISTINCT CODPARCDEST FROM TGFCAB"
      + " WHERE NUMNOTA = ? AND CODPARC = ? AND CODEMP = ?"
      + "   AND TIPMOV = 'O' AND PENDENTE = 'S'";

    private PortalXmlRepository() {}

    /** XML importado correspondente a chave, ou null se nao houver registro. */
    public static String xmlDaChave(JdbcWrapper jdbc, String chaveNfe) throws Exception {
        PreparedStatement consulta = jdbc.getPreparedStatement(SQL_XML);
        try {
            consulta.setString(1, chaveNfe);
            ResultSet resultado = consulta.executeQuery();
            try {
                return resultado.next() ? resultado.getString(1) : null;
            } finally {
                resultado.close();
            }
        } finally {
            consulta.close();
        }
    }

    /**
     * Valores distintos de CODPARCDEST dos pedidos candidatos. Lista vazia = nenhum pedido;
     * mais de um elemento = divergencia, e quem chama decide (secoes 15 e 16).
     */
    public static List<BigDecimal> destinatariosDoPedido(JdbcWrapper jdbc, BigDecimal numeroPedido,
                                                         BigDecimal codParc, BigDecimal codEmp) throws Exception {
        List<BigDecimal> encontrados = new ArrayList<BigDecimal>(2);
        PreparedStatement consulta = jdbc.getPreparedStatement(SQL_DESTINATARIOS);
        try {
            consulta.setBigDecimal(1, numeroPedido);
            consulta.setBigDecimal(2, codParc);
            consulta.setBigDecimal(3, codEmp);
            ResultSet resultado = consulta.executeQuery();
            try {
                while (resultado.next()) {
                    BigDecimal valor = resultado.getBigDecimal(1);
                    encontrados.add(valor == null ? BigDecimal.ZERO : valor);
                }
            } finally {
                resultado.close();
            }
        } finally {
            consulta.close();
        }
        return encontrados;
    }
}
