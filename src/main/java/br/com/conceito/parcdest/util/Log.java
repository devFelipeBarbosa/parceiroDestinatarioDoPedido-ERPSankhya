package br.com.conceito.parcdest.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log em arquivo dentro do Repositorio de Arquivos do Sankhya (SW Repository),
 * pasta {@code personalizacao/parcdest}.
 *
 * SWRepositoryUtils (mge-modelcore) e acessado por reflection para nao exigir o JAR
 * em tempo de compilacao — em runtime no servidor a classe sempre existe. Fora do
 * servidor (teste local) cai no diretorio temporario da JVM.
 *
 * Prefixo das mensagens conforme secao 19 da arquitetura.
 */
public final class Log {

    public static final String PREFIXO = "[PORTAL-XML-PARCDEST-WA]";

    private static final String CLASSE_SW_REPOSITORY = "br.com.sankhya.modelcore.util.SWRepositoryUtils";
    private static final String SUBPASTA = "/personalizacao/parcdest";
    private static final String NOME_ARQUIVO = "PARCDEST-TRACE";
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS");

    /** Resolvido uma unica vez: o caminho base nao muda durante a vida da JVM. */
    private static volatile String pastaResolvida;

    private Log() {
    }

    /** Pasta de trabalho do componente (log e arquivo de configuracao). */
    static String pastaDeTrabalho() {
        String pasta = pastaResolvida;
        if (pasta == null) {
            pasta = baseFolder() + SUBPASTA;
            pastaResolvida = pasta;
        }
        return pasta;
    }

    public static void info(String mensagem) {
        escrever("INFO", mensagem);
    }

    public static void erro(String mensagem, Throwable causa) {
        StringWriter sw = new StringWriter();
        causa.printStackTrace(new PrintWriter(sw));
        escrever("ERROR", mensagem + "\nStackTrace:\n" + sw);
    }

    private static void escrever(String tipo, String mensagem) {
        String pasta = pastaDeTrabalho();
        String caminho = pasta + "/log" + LocalDate.now() + "-" + NOME_ARQUIVO + ".txt";
        String linha = LocalDateTime.now().format(DT_FORMAT)
            + " - t[" + Thread.currentThread().getId() + "]"
            + " - [" + tipo + "] - " + PREFIXO + " " + mensagem + "\n";
        try {
            new File(pasta).mkdirs();
            FileWriter fw = new FileWriter(caminho, true);
            try {
                fw.write(linha);
            } finally {
                fw.close();
            }
        } catch (Exception falhaAoLogar) {
            // Log e observabilidade, nao regra de negocio: nunca pode derrubar a transacao.
            System.err.println(PREFIXO + " falha ao gravar log: " + falhaAoLogar.getMessage());
        }
    }

    private static String baseFolder() {
        try {
            Class<?> swRepository = Class.forName(CLASSE_SW_REPOSITORY);
            Method getBaseFolder = swRepository.getMethod("getBaseFolder");
            Object base = getBaseFolder.invoke(null);
            if (base != null) {
                return base.toString();
            }
        } catch (Exception foraDoServidorSankhya) {
            // classe nao existe em teste local
        }
        return System.getProperty("java.io.tmpdir");
    }
}
