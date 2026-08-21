package br.com.conceito.parcdest.util;

/**
 * Leitura pontual do XML da NF-e guardado em {@code TGFIXN.XML}.
 *
 * Nao usa parser de proposito: o unico dado necessario e o {@code <xPed>} de dentro de
 * {@code <compra>}, o CLOB passa de 9 KB e isto roda dentro da transacao do usuario.
 * indexOf resolve em uma passada.
 */
public final class XmlNfe {

    private static final String ABRE_COMPRA = "<compra>";
    private static final String FECHA_COMPRA = "</compra>";
    private static final String ABRE_XPED = "<xPed>";
    private static final String FECHA_XPED = "</xPed>";

    private XmlNfe() {}

    /**
     * Numero do Pedido de Compra informado pelo emitente no XML, ou {@code null} se o
     * documento nao trouxer o grupo {@code <compra>} ou a tag vier vazia.
     *
     * ponytail: casa a tag sem namespace. A NF-e 4.00 usa namespace default, sem prefixo;
     * se algum emitente prefixar, o retorno e null e o fail-safe da secao 16 assume.
     */
    public static String xPed(String xml) {
        if (xml == null) {
            return null;
        }
        int inicioCompra = xml.indexOf(ABRE_COMPRA);
        if (inicioCompra < 0) {
            return null;
        }
        int fimCompra = xml.indexOf(FECHA_COMPRA, inicioCompra);
        if (fimCompra < 0) {
            return null;
        }
        int inicio = xml.indexOf(ABRE_XPED, inicioCompra);
        if (inicio < 0 || inicio > fimCompra) {
            return null;
        }
        int fim = xml.indexOf(FECHA_XPED, inicio);
        if (fim < 0 || fim > fimCompra) {
            return null;
        }
        String valor = xml.substring(inicio + ABRE_XPED.length(), fim).trim();
        return valor.isEmpty() ? null : valor;
    }
}
