package br.com.conceito.parcdest.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Feature flag e escopo do componente (secoes 12 e 13 da arquitetura).
 *
 * Lida de {@code <SW Repository>/personalizacao/parcdest/parcdest.properties}. Arquivo
 * ausente vale como default seguro: tracing ligado, workaround desligado.
 *
 * Arquivo, e nao parametro de sistema, para atender a secao 26: desligar nao exige
 * alteracao de banco, alteracao de objeto standard nem recompilacao.
 *
 * <pre>
 * tracing=ON            # registra os eventos da TGFCAB no log (nao altera dado)
 * workaround=OFF        # FASE 2: preenche CODPARCDEST. Manter OFF ate a fase 1 concluir.
 * tops=1419             # TOPs elegiveis, separadas por virgula
 * camposExtras=         # campos extras da TGFCAB a registrar no log, separados por virgula
 * </pre>
 */
public final class Configuracao {

    static final String NOME_ARQUIVO = "parcdest.properties";

    /** ponytail: releitura por TTL, nao por watcher. Roda dentro da transacao do usuario. */
    private static final long TTL_MS = 60000L;

    private static volatile Configuracao cache;
    private static volatile long expiraEm;

    private final boolean tracing;
    private final boolean workaround;
    private final Set<String> tops;
    private final List<String> camposExtras;

    private Configuracao(boolean tracing, boolean workaround, Set<String> tops, List<String> camposExtras) {
        this.tracing = tracing;
        this.workaround = workaround;
        this.tops = Collections.unmodifiableSet(tops);
        this.camposExtras = Collections.unmodifiableList(camposExtras);
    }

    public static Configuracao atual() {
        long agora = System.currentTimeMillis();
        Configuracao atual = cache;
        if (atual != null && agora < expiraEm) {
            return atual;
        }
        atual = carregar();
        cache = atual;
        expiraEm = agora + TTL_MS;
        return atual;
    }

    private static Configuracao carregar() {
        Properties props = new Properties();
        File arquivo = new File(Log.pastaDeTrabalho(), NOME_ARQUIVO);
        if (arquivo.isFile()) {
            try {
                InputStream in = new FileInputStream(arquivo);
                try {
                    props.load(in);
                } finally {
                    in.close();
                }
            } catch (Exception falhaDeLeitura) {
                // Secao 16 (fail-safe): erro de leitura nao pode virar comportamento indefinido.
                Log.erro("Falha ao ler " + arquivo.getAbsolutePath() + "; assumindo defaults.", falhaDeLeitura);
                props.clear();
            }
        }
        return parse(props);
    }

    /** Visivel para teste. */
    static Configuracao parse(Properties props) {
        return new Configuracao(
            ligado(props.getProperty("tracing"), true),
            ligado(props.getProperty("workaround"), false),
            normalizarTops(props.getProperty("tops", "1419")),
            listar(props.getProperty("camposExtras")));
    }

    private static boolean ligado(String valor, boolean padrao) {
        if (valor == null) {
            return padrao;
        }
        String v = valor.trim();
        return "ON".equalsIgnoreCase(v) || "S".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v);
    }

    private static Set<String> normalizarTops(String valor) {
        Set<String> resultado = new HashSet<String>();
        for (String item : listar(valor)) {
            String top = normalizar(item);
            if (top != null) {
                resultado.add(top);
            }
        }
        return resultado;
    }

    private static List<String> listar(String valor) {
        List<String> resultado = new ArrayList<String>();
        if (valor == null) {
            return resultado;
        }
        for (String item : valor.split(",")) {
            String limpo = item.trim();
            if (!limpo.isEmpty()) {
                resultado.add(limpo);
            }
        }
        return resultado;
    }

    /** "1419", "1419.00" e BigDecimal(1419) convergem para a mesma chave. */
    private static String normalizar(String numero) {
        try {
            return new BigDecimal(numero).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException naoENumero) {
            return null;
        }
    }

    public boolean tracingAtivo() {
        return tracing;
    }

    public boolean workaroundAtivo() {
        return workaround;
    }

    public boolean topElegivel(BigDecimal codTipoOper) {
        if (codTipoOper == null) {
            return false;
        }
        return tops.contains(codTipoOper.stripTrailingZeros().toPlainString());
    }

    public List<String> camposExtras() {
        return camposExtras;
    }
}
