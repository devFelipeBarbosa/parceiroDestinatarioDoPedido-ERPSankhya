package br.com.conceito.parcdest.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Leitura pontual do XML da NF-e guardado em {@code TGFIXN.XML}.
 *
 * Nao usa parser de proposito: sao tres dados pontuais, o CLOB passa de 9 KB e isto roda
 * dentro da transacao do usuario. indexOf resolve em uma passada.
 *
 * ponytail: casa tag sem namespace. A NF-e 4.00 usa namespace default, sem prefixo; se
 * algum emitente prefixar, o retorno e null e o fail-safe assume.
 */
public final class XmlNfe {

    private static final String ABRE_COMPRA = "<compra>";
    private static final String FECHA_COMPRA = "</compra>";
    private static final String ABRE_ENTREGA = "<entrega>";
    private static final String ABRE_INFCPL = "<infCpl>";
    private static final String FECHA_INFCPL = "</infCpl>";
    private static final String ABRE_EMIT = "<emit>";
    private static final String FECHA_EMIT = "</emit>";
    private static final String ABRE_DEST = "<dest>";
    private static final String FECHA_DEST = "</dest>";
    private static final String FECHA_ENTREGA = "</entrega>";
    private static final String ABRE_XPED = "<xPed>";
    private static final String FECHA_XPED = "</xPed>";

    /** ponytail: teto de itens lidos. NF-e com mais itens que isso ja resolveu ou nao resolve. */
    private static final int LIMITE_ITENS = 990;

    private XmlNfe() {}

    /**
     * Numero do Pedido de Compra do grupo ZC ({@code <compra><xPed>}), ou {@code null}.
     * E o pedido do documento como um todo.
     */
    public static String xPed(String xml) {
        if (xml == null) {
            return null;
        }
        int inicio = xml.indexOf(ABRE_COMPRA);
        if (inicio < 0) {
            return null;
        }
        int fim = xml.indexOf(FECHA_COMPRA, inicio);
        return fim < 0 ? null : conteudo(xml, ABRE_XPED, FECHA_XPED, inicio, fim);
    }

    /**
     * Numeros de pedido declarados item a item — grupo I05, {@code <det><prod><xPed>}.
     * Distintos, na ordem em que aparecem.
     *
     * E o campo que a maioria dos ERPs emissores preenche, justamente para o comprador
     * conseguir casar a nota com o pedido. O grupo ZC costuma vir vazio no mesmo documento.
     *
     * Lista com mais de um elemento = a nota atende mais de um pedido; quem chama decide.
     */
    public static List<String> xPedsDosItens(String xml) {
        List<String> encontrados = new ArrayList<String>(2);
        if (xml == null) {
            return encontrados;
        }
        // Delimita o grupo <compra> para excluir o xPed de nivel de documento.
        int inicioCompra = xml.indexOf(ABRE_COMPRA);
        int fimCompra = inicioCompra < 0 ? -1 : xml.indexOf(FECHA_COMPRA, inicioCompra);

        int cursor = 0;
        while (encontrados.size() < LIMITE_ITENS) {
            int abre = xml.indexOf(ABRE_XPED, cursor);
            if (abre < 0) {
                break;
            }
            int fecha = xml.indexOf(FECHA_XPED, abre);
            if (fecha < 0) {
                break;
            }
            cursor = fecha + FECHA_XPED.length();

            boolean dentroDoCompra = inicioCompra >= 0 && abre > inicioCompra && abre < fimCompra;
            if (dentroDoCompra) {
                continue;
            }
            String valor = xml.substring(abre + ABRE_XPED.length(), fecha).trim();
            if (!valor.isEmpty() && !encontrados.contains(valor)) {
                encontrados.add(valor);
            }
        }
        return encontrados;
    }

    /**
     * CNPJ ou CPF do recebedor — grupo G, {@code <entrega>}. Somente digitos, ou {@code null}.
     *
     * O grupo so aparece quando a entrega e em endereco diferente do destinatario, que e
     * exatamente o caso do estoque de terceiros. Quando aparece, o CNPJ/CPF e obrigatorio.
     */
    public static String documentoEntrega(String xml) {
        if (xml == null) {
            return null;
        }
        int inicio = xml.indexOf(ABRE_ENTREGA);
        if (inicio < 0) {
            return null;
        }
        int fim = xml.indexOf(FECHA_ENTREGA, inicio);
        if (fim < 0) {
            return null;
        }
        String documento = conteudo(xml, "<CNPJ>", "</CNPJ>", inicio, fim);
        if (documento == null) {
            documento = conteudo(xml, "<CPF>", "</CPF>", inicio, fim);
        }
        return documento == null ? null : somenteDigitos(documento);
    }

    /** CNPJ do emitente, so digitos, ou null. */
    public static String cnpjEmitente(String xml) {
        return cnpjDoGrupo(xml, ABRE_EMIT, FECHA_EMIT);
    }

    /** CNPJ do destinatario, so digitos, ou null. */
    public static String cnpjDestinatario(String xml) {
        return cnpjDoGrupo(xml, ABRE_DEST, FECHA_DEST);
    }

    /**
     * CNPJs com digito verificador valido citados nas Informacoes Complementares.
     *
     * Existe porque emitente que entrega em terceiro nem sempre usa o grupo &lt;entrega&gt;:
     * parte deles descreve o recebedor em texto livre, com CNPJ e IE. O DV filtra numero
     * solto; quem chama ainda precisa descartar emitente e destinatario e confirmar o
     * candidato contra outra fonte — CNPJ no texto sozinho nao decide nada.
     */
    public static List<String> cnpjsDoInfCpl(String xml) {
        List<String> encontrados = new ArrayList<String>(1);
        if (xml == null) {
            return encontrados;
        }
        int inicio = xml.indexOf(ABRE_INFCPL);
        if (inicio < 0) {
            return encontrados;
        }
        int fim = xml.indexOf(FECHA_INFCPL, inicio);
        if (fim < 0) {
            return encontrados;
        }
        String texto = xml.substring(inicio + ABRE_INFCPL.length(), fim);

        StringBuilder token = new StringBuilder(20);
        for (int i = 0; i <= texto.length(); i++) {
            char c = i < texto.length() ? texto.charAt(i) : ' ';
            boolean parteDeNumero = (c >= '0' && c <= '9') || c == '.' || c == '/' || c == '-';
            if (parteDeNumero) {
                if (c >= '0' && c <= '9') {
                    token.append(c);
                }
                continue;
            }
            // Comprimento diferente de 14 descarta IE, numero de nota, chave de acesso.
            String candidato = token.toString();
            token.setLength(0);
            if (candidato.length() == 14 && dvValido(candidato) && !encontrados.contains(candidato)) {
                encontrados.add(candidato);
            }
        }
        return encontrados;
    }

    /** Digito verificador do CNPJ, modulo 11. */
    static boolean dvValido(String cnpj) {
        int[] pesosPrimeiro = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] pesosSegundo = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return digito(cnpj, pesosPrimeiro) == cnpj.charAt(12) - '0'
            && digito(cnpj, pesosSegundo) == cnpj.charAt(13) - '0';
    }

    private static int digito(String cnpj, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (cnpj.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static String cnpjDoGrupo(String xml, String abre, String fecha) {
        if (xml == null) {
            return null;
        }
        int inicio = xml.indexOf(abre);
        if (inicio < 0) {
            return null;
        }
        int fim = xml.indexOf(fecha, inicio);
        if (fim < 0) {
            return null;
        }
        String documento = conteudo(xml, "<CNPJ>", "</CNPJ>", inicio, fim);
        return documento == null ? null : somenteDigitos(documento);
    }

    /** Conteudo da tag dentro da faixa [inicio, limite], ou null. */
    private static String conteudo(String xml, String abre, String fecha, int inicio, int limite) {
        int posAbre = xml.indexOf(abre, inicio);
        if (posAbre < 0 || posAbre > limite) {
            return null;
        }
        int posFecha = xml.indexOf(fecha, posAbre);
        if (posFecha < 0 || posFecha > limite) {
            return null;
        }
        String valor = xml.substring(posAbre + abre.length(), posFecha).trim();
        return valor.isEmpty() ? null : valor;
    }

    private static String somenteDigitos(String texto) {
        StringBuilder digitos = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c >= '0' && c <= '9') {
                digitos.append(c);
            }
        }
        return digitos.length() == 0 ? null : digitos.toString();
    }
}
