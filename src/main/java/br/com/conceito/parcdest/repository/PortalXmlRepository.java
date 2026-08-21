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
 * Somente leitura. Nenhuma das tabelas lidas e escrita pela transacao antes do INSERT do
 * cabecalho (comprovado pelo Monitor de Consultas, secao 14.1 da arquitetura).
 */
public final class PortalXmlRepository {

    /**
     * Arquivo do Portal de Importacao de XML, endereçado pela chave de acesso.
     * CODPARCDEST e a coluna que o Portal expoe na tela e nao preenche sozinho: quando o
     * usuario digita ali, e escolha explicita e tem prioridade sobre qualquer inferencia.
     */
    private static final String SQL_ARQUIVO =
        "SELECT XML, CODPARCDEST FROM TGFIXN WHERE CHAVEACESSO = ?";

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

    /**
     * Pedidos pendentes do parceiro, do mais antigo para o mais novo. Reproduz a ordem de
     * "Ligar pedidos mais antigos" do proprio Portal (secao 3 da arquitetura).
     *
     * CODPARCDEST > 0 ja descarta pedido que nao tem o que propagar.
     */
    private static final String SQL_PEDIDOS_PENDENTES =
        "SELECT NUNOTA, NUMNOTA, CODPARCDEST FROM TGFCAB"
      + " WHERE CODPARC = ? AND CODEMP = ?"
      + "   AND TIPMOV = 'O' AND PENDENTE = 'S' AND CODPARCDEST > 0"
      + " ORDER BY DTNEG, NUNOTA";

    /**
     * Parceiro pelo CNPJ/CPF do grupo &lt;entrega&gt;.
     *
     * ponytail: REGEXP_REPLACE na coluna ignora indice, porque nao ha garantia de que o
     * cadastro esteja sem mascara. TGFPAR e pequena o bastante. Se pesar, criar indice
     * funcional sobre a mesma expressao.
     */
    private static final String SQL_PARCEIRO_POR_DOCUMENTO =
        "SELECT CODPARC FROM TGFPAR"
      + " WHERE REGEXP_REPLACE(CGC_CPF, '[^0-9]', '') = ? AND ATIVO = 'S'";

    /**
     * Mesma consulta, exigindo fornecedor e recusando transportadora.
     *
     * Usada so pelo CNPJ vindo de texto livre: transportadora citada no corpo da observacao
     * e o falso positivo natural desse caminho. FORNECEDOR = 'S' ja elimina a maioria;
     * TRANSPORTADORA = 'N' fecha o cadastro que acumula as duas marcacoes — com NVL, porque
     * coluna nula ali significa "nao e transportadora", nao "descarta". O grupo
     * &lt;entrega&gt; nao usa este filtro — ali o recebedor e declarado em campo proprio.
     */
    private static final String SQL_FORNECEDOR_POR_DOCUMENTO =
        "SELECT CODPARC FROM TGFPAR"
      + " WHERE REGEXP_REPLACE(CGC_CPF, '[^0-9]', '') = ?"
      + "   AND ATIVO = 'S' AND FORNECEDOR = 'S' AND NVL(TRANSPORTADORA, 'N') = 'N'";

    /** ponytail: teto de linhas lidas. Parceiro com mais pedidos pendentes que isso ja e ambiguidade. */
    private static final int LIMITE_CANDIDATOS = 50;

    private PortalXmlRepository() {}

    /** Linha do Portal: o XML importado e o destinatario que o usuario tenha digitado. */
    public static final class ArquivoImportado {
        public final String xml;
        public final BigDecimal codParcDest;

        ArquivoImportado(String xml, BigDecimal codParcDest) {
            this.xml = xml;
            this.codParcDest = codParcDest;
        }
    }

    /** Pedido de Compra candidato. */
    public static final class PedidoCandidato {
        public final BigDecimal nunota;
        public final BigDecimal numnota;
        public final BigDecimal codParcDest;

        PedidoCandidato(BigDecimal nunota, BigDecimal numnota, BigDecimal codParcDest) {
            this.nunota = nunota;
            this.numnota = numnota;
            this.codParcDest = codParcDest;
        }
    }

    /** Arquivo correspondente a chave, ou null se nao houver registro. */
    public static ArquivoImportado arquivoDaChave(JdbcWrapper jdbc, String chaveNfe) throws Exception {
        PreparedStatement consulta = jdbc.getPreparedStatement(SQL_ARQUIVO);
        try {
            consulta.setString(1, chaveNfe);
            ResultSet resultado = consulta.executeQuery();
            try {
                if (!resultado.next()) {
                    return null;
                }
                return new ArquivoImportado(resultado.getString(1), resultado.getBigDecimal(2));
            } finally {
                resultado.close();
            }
        } finally {
            consulta.close();
        }
    }

    /**
     * Valores distintos de CODPARCDEST dos pedidos casados pelo xPed. Lista vazia = nenhum
     * pedido; mais de um elemento = divergencia, e quem chama decide (secoes 15 e 16).
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

    /** Parceiros ativos com esse CNPJ/CPF. Mais de um = cadastro duplicado, quem chama decide. */
    public static List<BigDecimal> parceirosPorDocumento(JdbcWrapper jdbc, String documento) throws Exception {
        return porDocumento(jdbc, SQL_PARCEIRO_POR_DOCUMENTO, documento);
    }

    /** Idem, restrito a quem esta marcado como fornecedor. */
    public static List<BigDecimal> fornecedoresPorDocumento(JdbcWrapper jdbc, String documento) throws Exception {
        return porDocumento(jdbc, SQL_FORNECEDOR_POR_DOCUMENTO, documento);
    }

    private static List<BigDecimal> porDocumento(JdbcWrapper jdbc, String sql, String documento) throws Exception {
        List<BigDecimal> encontrados = new ArrayList<BigDecimal>(1);
        PreparedStatement consulta = jdbc.getPreparedStatement(sql);
        try {
            consulta.setString(1, documento);
            ResultSet resultado = consulta.executeQuery();
            try {
                while (resultado.next()) {
                    encontrados.add(resultado.getBigDecimal(1));
                }
            } finally {
                resultado.close();
            }
        } finally {
            consulta.close();
        }
        return encontrados;
    }

    /** Pedidos pendentes do parceiro com destinatario preenchido, do mais antigo em diante. */
    public static List<PedidoCandidato> pedidosPendentes(JdbcWrapper jdbc, BigDecimal codParc,
                                                        BigDecimal codEmp) throws Exception {
        List<PedidoCandidato> encontrados = new ArrayList<PedidoCandidato>(4);
        PreparedStatement consulta = jdbc.getPreparedStatement(SQL_PEDIDOS_PENDENTES);
        try {
            consulta.setBigDecimal(1, codParc);
            consulta.setBigDecimal(2, codEmp);
            ResultSet resultado = consulta.executeQuery();
            try {
                while (resultado.next() && encontrados.size() < LIMITE_CANDIDATOS) {
                    encontrados.add(new PedidoCandidato(
                        resultado.getBigDecimal(1),
                        resultado.getBigDecimal(2),
                        resultado.getBigDecimal(3)));
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
