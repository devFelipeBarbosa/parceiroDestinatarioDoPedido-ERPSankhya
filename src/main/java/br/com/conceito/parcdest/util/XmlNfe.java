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
