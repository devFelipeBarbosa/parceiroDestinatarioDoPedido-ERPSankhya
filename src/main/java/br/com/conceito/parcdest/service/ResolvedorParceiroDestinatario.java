package br.com.conceito.parcdest.service;

import br.com.conceito.parcdest.repository.PortalXmlRepository;
import br.com.conceito.parcdest.repository.PortalXmlRepository.ArquivoImportado;
import br.com.conceito.parcdest.repository.PortalXmlRepository.PedidoCandidato;
import br.com.conceito.parcdest.util.Configuracao;
import br.com.conceito.parcdest.util.Configuracao.Fallback;
import br.com.conceito.parcdest.util.Log;
import br.com.conceito.parcdest.util.XmlNfe;
import br.com.sankhya.jape.dao.JdbcWrapper;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FASE 2. Resolve o Parceiro Destinatario que o Portal de Importacao de XML nao preenche.
 *
 * Tres origens, nessa ordem:
 *
 * <pre>
 * 1. TGFIXN.CODPARCDEST  -> escolha explicita do usuario na tela do Portal
 * 2. &lt;compra&gt;&lt;xPed&gt;    -> pedido casado por numero, o mesmo campo que o Portal usa
 * 3. fallback            -> pedido pendente mais antigo do parceiro (secao 3: e a mesma
 *                           regra do botao "Ligar pedidos mais antigos" do Portal)
 * </pre>
 *
 * A origem 2 e a unica deterministica; existe porque nem todo fornecedor preenche o xPed.
 * Divergencia declarada no proprio xPed nao cai para o fallback: escolha ambigua e recusa,
 * nao chute (secao 16).
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

        ArquivoImportado arquivo = PortalXmlRepository.arquivoDaChave(jdbc, chaveNfe.trim());
        if (arquivo == null) {
            Log.info("workaround=SKIP motivo=XML_NAO_ENCONTRADO chave=" + chaveNfe);
            return null;
        }

        // 1. Escolha do usuario na tela do Portal manda em qualquer inferencia.
        if (arquivo.codParcDest != null && arquivo.codParcDest.signum() > 0) {
            Log.info("workaround=RESOLVIDO origem=PORTAL_TGFIXN CODPARCDEST=" + arquivo.codParcDest);
            return arquivo.codParcDest;
        }

        // 2. xPed.
        String xPed = XmlNfe.xPed(arquivo.xml);
        BigDecimal numeroPedido = xPed == null ? null : comoNumero(xPed);

        if (numeroPedido != null) {
            List<BigDecimal> candidatos =
                PortalXmlRepository.destinatariosDoPedido(jdbc, numeroPedido, codParc, codEmp);

            if (candidatos.size() > 1) {
                // Secao 15: mais de um destinatario para o mesmo numero de pedido e
                // divergencia declarada. Nao cai para o fallback.
                Log.info("workaround=SKIP motivo=DESTINATARIOS_DIVERGENTES xPed=" + xPed
                       + " candidatos=" + candidatos);
                return null;
            }
            if (candidatos.size() == 1) {
                BigDecimal destinatario = candidatos.get(0);
                if (destinatario != null && destinatario.signum() > 0) {
                    Log.info("workaround=RESOLVIDO origem=XPED xPed=" + xPed
                           + " CODPARCDEST=" + destinatario);
                    return destinatario;
                }
                Log.info("workaround=xPed motivo=PEDIDO_SEM_DESTINATARIO xPed=" + xPed);
            } else {
                Log.info("workaround=xPed motivo=PEDIDO_NAO_ENCONTRADO xPed=" + xPed
                       + " CODPARC=" + codParc + " CODEMP=" + codEmp);
            }
        } else {
            Log.info("workaround=xPed motivo=" + (xPed == null ? "XML_SEM_XPED" : "XPED_NAO_NUMERICO xPed=" + xPed));
        }

        // 3. Fallback.
        return pedidoMaisAntigo(jdbc, codParc, codEmp);
    }

    /**
     * Pedido pendente mais antigo do parceiro. Mesma ordem de "Ligar pedidos mais antigos".
     *
     * ponytail: casa por parceiro e empresa, nao por produto nem por quantidade. Se o
     * parceiro tiver pedidos pendentes para destinatarios diferentes, o modo UNICO recusa
     * e o modo ANTIGO segue o Portal. Casar item a item exigiria ler TGFITE do XML inteiro.
     */
    private static BigDecimal pedidoMaisAntigo(JdbcWrapper jdbc, BigDecimal codParc,
                                               BigDecimal codEmp) throws Exception {
        Fallback modo = Configuracao.atual().fallback();
        if (modo == Fallback.OFF) {
            Log.info("workaround=SKIP motivo=FALLBACK_DESLIGADO");
            return null;
        }

        List<PedidoCandidato> pedidos = PortalXmlRepository.pedidosPendentes(jdbc, codParc, codEmp);
        if (pedidos.isEmpty()) {
            Log.info("workaround=SKIP motivo=SEM_PEDIDO_PENDENTE CODPARC=" + codParc
                   + " CODEMP=" + codEmp);
            return null;
        }

        boolean divergentes = destinatariosDistintos(pedidos) > 1;
        if (divergentes && modo == Fallback.UNICO) {
            Log.info("workaround=SKIP motivo=PENDENTES_DIVERGENTES CODPARC=" + codParc
                   + " pedidos=" + pedidos.size() + " modo=UNICO");
            return null;
        }

        PedidoCandidato maisAntigo = pedidos.get(0);
        Log.info("workaround=RESOLVIDO origem=PEDIDO_MAIS_ANTIGO NUNOTA=" + maisAntigo.nunota
               + " NUMNOTA=" + maisAntigo.numnota
               + " CODPARCDEST=" + maisAntigo.codParcDest
               + " pendentes=" + pedidos.size()
               + " divergentes=" + (divergentes ? "S" : "N"));
        return maisAntigo.codParcDest;
    }

    private static int destinatariosDistintos(List<PedidoCandidato> pedidos) {
        Set<BigDecimal> distintos = new HashSet<BigDecimal>();
        for (PedidoCandidato pedido : pedidos) {
            distintos.add(pedido.codParcDest.stripTrailingZeros());
        }
        return distintos.size();
    }

    private static BigDecimal comoNumero(String texto) {
        try {
            return new BigDecimal(texto.trim());
        } catch (NumberFormatException naoENumero) {
            return null;
        }
    }
}
