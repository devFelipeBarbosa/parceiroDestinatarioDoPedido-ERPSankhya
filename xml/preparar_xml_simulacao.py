#!/usr/bin/env python3
"""Adapta o XML modelo da NF-e para importar na base de simulacao.

Troca o bloco <dest> pela empresa da base, ajusta <compra><xPed> para o numero do
Pedido de Compra e, opcionalmente, gera chave nova (para reimportar sem duplicidade).

O <emit> nao e alterado de proposito: a chave de acesso carrega o CNPJ do emitente,
entao mexer nele obrigaria a recalcular chave, Id e protNFe/chNFe.

    python3 preparar_xml_simulacao.py modelo.xml -o saida.xml \
        --cnpj 99999999000191 --nome "EMPRESA PADRAO" \
        --uf MG --cmun 3170206 --mun UBERLANDIA --xped 1234
"""
import argparse
import random
import re
import sys

CAMPOS_DEST = {
    'cnpj': 'CNPJ', 'ie': 'IE', 'nome': 'xNome', 'uf': 'UF',
    'cmun': 'cMun', 'mun': 'xMun', 'cep': 'CEP', 'lgr': 'xLgr',
    'nro': 'nro', 'bairro': 'xBairro', 'fone': 'fone',
}


def dv_chave(chave43):
    """Digito verificador da chave de acesso: modulo 11, pesos 2..9 da direita."""
    soma = sum(int(d) * (2 + i % 8) for i, d in enumerate(reversed(chave43)))
    resto = soma % 11
    return '0' if resto < 2 else str(11 - resto)


def troca_tag(trecho, tag, valor):
    novo, n = re.subn(r'<%s>.*?</%s>' % (tag, tag), '<%s>%s</%s>' % (tag, valor, tag),
                      trecho, count=1, flags=re.S)
    if n != 1:
        sys.exit('tag <%s> nao encontrada' % tag)
    return novo


def main():
    p = argparse.ArgumentParser()
    p.add_argument('entrada', nargs='?')
    p.add_argument('-o', '--saida')
    for arg in CAMPOS_DEST:
        p.add_argument('--' + arg)
    p.add_argument('--xped', help='NUMNOTA do Pedido de Compra na base')
    p.add_argument('--nova-chave', action='store_true',
                   help='gera cNF/nNF novos e recalcula DV, Id e chNFe')
    p.add_argument('--nnf', help='numero da NF (usado com --nova-chave)')
    p.add_argument('--autoteste', action='store_true')
    a = p.parse_args()

    if a.autoteste:
        # chave real do XML modelo: os 43 primeiros digitos tem que gerar o DV 8
        assert dv_chave('4226051128764200013055002000067460153160760') == '8'
        assert troca_tag('<xPed>1</xPed>', 'xPed', '77') == '<xPed>77</xPed>'
        print('autoteste ok')
        return

    if not a.entrada or not a.saida:
        sys.exit('uso: preparar_xml_simulacao.py <entrada.xml> -o <saida.xml> [...]')

    xml = open(a.entrada, encoding='utf-8').read()

    dest = re.search(r'<dest>.*?</dest>', xml, re.S)
    if not dest:
        sys.exit('bloco <dest> nao encontrado')
    novo_dest = dest.group(0)
    for arg, tag in CAMPOS_DEST.items():
        valor = getattr(a, arg)
        if valor:
            novo_dest = troca_tag(novo_dest, tag, valor)
    xml = xml[:dest.start()] + novo_dest + xml[dest.end():]

    if a.xped:
        compra = re.search(r'<compra>.*?</compra>', xml, re.S)
        if not compra:
            sys.exit('bloco <compra> nao encontrado; XML sem vinculo de pedido')
        xml = xml[:compra.start()] + troca_tag(compra.group(0), 'xPed', a.xped) + xml[compra.end():]

    if a.nova_chave:
        chave = re.search(r'<chNFe>(\d{44})</chNFe>', xml).group(1)
        nnf = (a.nnf or chave[25:34]).rjust(9, '0')
        cnf = '%08d' % random.randint(1, 99999999)
        nova = chave[:25] + nnf + chave[34] + cnf
        nova += dv_chave(nova)
        xml = xml.replace(chave, nova)
        xml = troca_tag(xml, 'cNF', cnf)
        xml = troca_tag(xml, 'cDV', nova[-1])
        xml = troca_tag(xml, 'nNF', str(int(nnf)))
        print('chave nova: %s' % nova)

    open(a.saida, 'w', encoding='utf-8').write(xml)
    print('gravado: %s' % a.saida)


if __name__ == '__main__':
    main()
