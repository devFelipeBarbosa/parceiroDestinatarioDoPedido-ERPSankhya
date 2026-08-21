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


def xped_de_item(xml, xped, item):
    """Insere <xPed>/<nItemPed> em cada <prod>, na posicao do schema."""
    def um(m):
        prod = m.group(0)
        tags = '<xPed>%s</xPed><nItemPed>%s</nItemPed>' % (xped, item)
        if '<rastro>' in prod:
            return prod.replace('<rastro>', tags + '<rastro>', 1)
        return prod.replace('</indTot>', '</indTot>' + tags, 1)
    novo, n = re.subn(r'<prod>.*?</prod>', um, xml, flags=re.S)
    if not n:
        sys.exit('nenhum <prod> encontrado')
    print('xPed de item aplicado em %d item(ns)' % n)
    return novo


def bloco_entrega(dest, cnpj):
    """Monta o grupo <entrega> reaproveitando o endereco do <dest>."""
    def campo(tag):
        m = re.search(r'<%s>(.*?)</%s>' % (tag, tag), dest, re.S)
        return m.group(1) if m else None

    partes = ['<CNPJ>%s</CNPJ>' % re.sub(r'[^0-9]', '', cnpj)]
    for tag in ('xNome', 'xLgr', 'nro', 'xBairro', 'cMun', 'xMun', 'UF', 'CEP'):
        valor = campo(tag)
        if valor:
            partes.append('<%s>%s</%s>' % (tag, valor, tag))
    return '<entrega>' + ''.join(partes) + '</entrega>'


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
    p.add_argument('--sem-compra', action='store_true',
                   help='remove o grupo <compra> (testa XML_SEM_XPED)')
    p.add_argument('--sem-infcpl', action='store_true',
                   help='apaga o texto de <infCpl> (testa SEM_CNPJ_NO_TEXTO)')
    p.add_argument('--xped-item', help='poe <xPed>/<nItemPed> em cada <det><prod> (grupo I05)')
    p.add_argument('--entrega-cnpj', help='cria o grupo <entrega> com esse CNPJ (grupo G)')
    p.add_argument('--autoteste', action='store_true')
    a = p.parse_args()

    if a.autoteste:
        # chave real do XML modelo: os 43 primeiros digitos tem que gerar o DV 8
        assert dv_chave('4226051128764200013055002000067460153160760') == '8'
        assert troca_tag('<xPed>1</xPed>', 'xPed', '77') == '<xPed>77</xPed>'
        # xPed de item entra depois de indTot e antes de rastro (ordem do schema)
        prod = '<det><prod><indTot>1</indTot><rastro><nLote>A</nLote></rastro></prod></det>'
        assert xped_de_item(prod, '9', '1') == (
            '<det><prod><indTot>1</indTot><xPed>9</xPed><nItemPed>1</nItemPed>'
            '<rastro><nLote>A</nLote></rastro></prod></det>')
        # sem rastro, entra logo apos indTot
        assert xped_de_item('<prod><indTot>1</indTot></prod>', '9', '1') == (
            '<prod><indTot>1</indTot><xPed>9</xPed><nItemPed>1</nItemPed></prod>')
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

    if a.entrega_cnpj:
        # Grupo G vem logo depois de </dest>, conforme a ordem do layout.
        xml = xml.replace('</dest>', '</dest>' + bloco_entrega(novo_dest, a.entrega_cnpj), 1)
        print('grupo <entrega> criado')

    if a.sem_compra:
        xml, n = re.subn(r'<compra>.*?</compra>', '', xml, count=1, flags=re.S)
        print('grupo <compra> removido' if n else 'nao havia grupo <compra>')

    if a.sem_infcpl:
        xml, n = re.subn(r'<infCpl>.*?</infCpl>', '<infCpl>SEM COMPLEMENTO</infCpl>',
                         xml, count=1, flags=re.S)
        print('infCpl esvaziado' if n else 'nao havia <infCpl>')

    if a.xped_item:
        xml = xped_de_item(xml, a.xped_item, '1')

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
