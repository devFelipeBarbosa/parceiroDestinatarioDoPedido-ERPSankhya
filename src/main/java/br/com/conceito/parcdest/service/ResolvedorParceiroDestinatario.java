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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FASE 2. Resolve o Parceiro Destinatario que o Portal de Importacao de XML nao preenche.
 *
 * Quatro origens, nessa ordem, e so a ultima e inferencia:
 *
 * <pre>
 * 1. TGFIXN.CODPARCDEST        escolha explicita do usuario na tela do Portal
 * 2. xPed (grupos ZC e I05)    pedido declarado pelo emitente -> CODPARCDEST do pedido
 * 3. &lt;entrega&gt; CNPJ/CPF        recebedor declarado no documento -> TGFPAR
 * 4. fallback                  pedido pendente do parceiro, conforme flag
 * </pre>
 *
 * As origens 2 e 3 sao deterministicas: quem declarou foi o emitente, no proprio documento
 * fiscal. A 4 e inferencia declarada e pode ser desligada sem desligar o componente.
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

        // 2. Pedido declarado pelo emitente: grupo ZC (<compra>) e grupo I05 (item a item).
        Resultado porPedido = porXPed(jdbc, arquivo.xml, codParc, codEmp);
        if (porPedido.decidiu()) {
            return porPedido.destinatario;
        }

        // 3. Recebedor declarado no proprio documento.
        Resultado porEntrega = porEntrega(jdbc, arquivo.xml);
        if (porEntrega.decidiu()) {
            return porEntrega.destinatario;
        }

        // 4. Inferencia.
        return porPedidoPendente(jdbc, codParc, codEmp);
    }

    /**
     * Uniao dos destinatarios de todos os pedidos declarados no XML. Um unico valor > 0
     * resolve; mais de um e divergencia e encerra sem atuar — nao cai para as origens
     * seguintes, porque o proprio documento esta ambiguo.
     */
    private static Resultado porXPed(JdbcWrapper jdbc, String xml, BigDecimal codParc,
                                     BigDecimal codEmp) throws Exception {
        List<String> xPeds = new ArrayList<String>(2);
        String doDocumento = XmlNfe.xPed(xml);
        if (doDocumento != null) {
            xPeds.add(doDocumento);
        }
        for (String doItem : XmlNfe.xPedsDosItens(xml)) {
            if (!xPeds.contains(doItem)) {
                xPeds.add(doItem);
            }
        }
        if (xPeds.isEmpty()) {
            Log.info("workaround=xPed motivo=XML_SEM_XPED");
            return Resultado.SEGUIR;
        }

        Set<BigDecimal> destinatarios = new HashSet<BigDecimal>();
        int numericos = 0;
        for (String xPed : xPeds) {
            BigDecimal numero = comoNumero(xPed);
            if (numero == null) {
                continue;
            }
            numericos++;
            destinatarios.addAll(
                PortalXmlRepository.destinatariosDoPedido(jdbc, numero, codParc, codEmp));
        }

        if (numericos == 0) {
            // xPed e alfanumerico no layout (15 posicoes); NUMNOTA nao. Nao da para casar.
            Log.info("workaround=xPed motivo=XPED_NAO_NUMERICO xPeds=" + xPeds);
            return Resultado.SEGUIR;
        }
        if (destinatarios.isEmpty()) {
            Log.info("workaround=xPed motivo=PEDIDO_NAO_ENCONTRADO xPeds=" + xPeds
                   + " CODPARC=" + codParc + " CODEMP=" + codEmp);
            return Resultado.SEGUIR;
        }
        if (destinatarios.size() > 1) {
            Log.info("workaround=SKIP motivo=DESTINATARIOS_DIVERGENTES xPeds=" + xPeds
                   + " candidatos=" + destinatarios);
            return Resultado.PARAR;
        }

        BigDecimal destinatario = destinatarios.iterator().next();
        if (destinatario == null || destinatario.signum() <= 0) {
            Log.info("workaround=xPed motivo=PEDIDO_SEM_DESTINATARIO xPeds=" + xPeds);
            return Resultado.SEGUIR;
        }
        Log.info("workaround=RESOLVIDO origem=XPED xPeds=" + xPeds + " CODPARCDEST=" + destinatario);
        return Resultado.de(destinatario);
    }

    /**
     * Grupo G do layout: so existe quando a entrega e em endereco diferente do destinatario,
     * e nesse caso o CNPJ/CPF do recebedor e obrigatorio. E exatamente o parceiro
     * destinatario, dito pelo emitente, sem depender de pedido nenhum.
     */
    private static Resultado porEntrega(JdbcWrapper jdbc, String xml) throws Exception {
        String documento = XmlNfe.documentoEntrega(xml);
        if (documento == null) {
            Log.info("workaround=entrega motivo=XML_SEM_ENTREGA");
            return Resultado.SEGUIR;
        }

        List<BigDecimal> parceiros = PortalXmlRepository.parceirosPorDocumento(jdbc, documento);
        if (parceiros.isEmpty()) {
            Log.info("workaround=entrega motivo=PARCEIRO_NAO_CADASTRADO doc=" + mascarar(documento));
            return Resultado.SEGUIR;
        }
        if (parceiros.size() > 1) {
            Log.info("workaround=SKIP motivo=PARCEIROS_DUPLICADOS doc=" + mascarar(documento)
                   + " candidatos=" + parceiros);
            return Resultado.PARAR;
        }

        BigDecimal destinatario = parceiros.get(0);
        Log.info("workaround=RESOLVIDO origem=ENTREGA doc=" + mascarar(documento)
               + " CODPARCDEST=" + destinatario);
        return Resultado.de(destinatario);
    }

    /**
     * Pedido pendente do parceiro. Mesma ordem de "Ligar pedidos mais antigos" do Portal.
     *
     * ponytail: casa por parceiro e empresa, nao por produto nem por quantidade. Casar item
     * a item exigiria confrontar todo o &lt;det&gt; com a TGFITE do pedido.
     */
    private static BigDecimal porPedidoPendente(JdbcWrapper jdbc, BigDecimal codParc,
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

    /** CNPJ/CPF nao precisa aparecer inteiro no log para o suporte identificar o registro. */
    private static String mascarar(String documento) {
        int visiveis = 4;
        if (documento.length() <= visiveis) {
            return documento;
        }
        StringBuilder mascarado = new StringBuilder();
        for (int i = 0; i < documento.length() - visiveis; i++) {
            mascarado.append('*');
        }
        return mascarado.append(documento.substring(documento.length() - visiveis)).toString();
    }

    private static BigDecimal comoNumero(String texto) {
        try {
            return new BigDecimal(texto.trim());
        } catch (NumberFormatException naoENumero) {
            return null;
        }
    }

    /** Resolveu, nao resolveu mas pode seguir, ou encerrou por ambiguidade. */
    private static final class Resultado {
        static final Resultado SEGUIR = new Resultado(null, false);
        static final Resultado PARAR = new Resultado(null, true);

        final BigDecimal destinatario;
        final boolean encerra;

        private Resultado(BigDecimal destinatario, boolean encerra) {
            this.destinatario = destinatario;
            this.encerra = encerra;
        }

        static Resultado de(BigDecimal destinatario) {
            return new Resultado(destinatario, true);
        }

        boolean decidiu() {
            return encerra;
        }
    }
}
