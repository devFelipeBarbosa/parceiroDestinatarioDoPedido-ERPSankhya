# Parceiro Destinatário — Portal de Importação de XML (Sankhya ERP)

Evento Programável Java que corrige, na origem, o `TGFCAB.CODPARCDEST` que o Portal de
Importação de XML deixa zerado em notas de industrialização por conta e ordem.

> **Status:** fase 1 (tracing) encerrada. Fase 2 (workaround) **homologada em base de
> simulação 4.36b134** e desligada por padrão. Falta homologar em 4.35b809, a versão do
> cliente, e exercitar os caminhos de não-atuação.

---

## Versão Sankhya

- **Cliente (alvo de produção):** `4.35b809` — 24/07/2026
- **Base de simulação (onde foi homologado):** `4.36b134` — 20/08/2026
- **Compilado contra:** API `4.35`
- **Banco:** Oracle · **App server:** WildFly · **Java:** 8 (toolchain Gradle)

Compilar contra 4.35 é deliberado. Comparadas com `javap`, `JdbcWrapper`,
`PersistenceEvent`, `TransactionContext`, `ModifingFields` e `EntityVO` são idênticas nas
duas versões; `DynamicVO` e `JapeSession` apenas ganham métodos na 4.36. A 4.35 é
subconjunto da 4.36, então o JAR roda nas duas bases — o inverso produziria
`NoSuchMethodError` em produção.

---

## O problema

A nota formada pelo Portal de Importação de XML nasce com `TGFCAB.CODPARCDEST = 0`, mesmo
quando o Pedido de Compra vinculado tem o Parceiro Destinatário preenchido. Corrigir o campo
depois esbarra na trigger standard:

```
ORA-20101: Não é possível alterar o código do Parceiro de destino quando o
           lançamento atualiza estoque de terceiros
ORA-06512: em "SANKHYA.TRG_DLT_TGFCAB_ESTTERC", line 77
```

O cenário é TOP com CFOP de entrada 1122/2122 (industrialização por conta e ordem) e
`ATUALESTTERC <> 'N'`. O usuário fica sem saída: o Portal não preenche e o ERP não deixa
corrigir.

A análise funcional completa é documento interno do projeto e não acompanha este repositório.

---

## Arquitetura

```text
[Portal de Importação de XML]
       │  XML autorizado, com <compra><xPed>
       ▼
[TGFIXN]  registro do arquivo: CHAVEACESSO + XML (CLOB)
       │
       ▼
[INSERT INTO TGFCAB]  ← ponto de extensão: beforeInsert
       │
       ├── workaround=OFF ─────────────────────────► nada acontece
       │
       └── workaround=ON
             │
             ├─ CHAVENFE do VO                       (comprovado presente)
             ├─ SELECT XML, CODPARCDEST FROM TGFIXN
             │      WHERE CHAVEACESSO = :chave
             │
             ├─ 1. TGFIXN.CODPARCDEST > 0 ────► usa    (escolha do usuário na tela)
             │
             ├─ 2. xPed de <compra> (ZC) + de cada <det><prod> (I05)
             │     SELECT DISTINCT CODPARCDEST FROM TGFCAB
             │        WHERE NUMNOTA IN (:xPeds)
             │          AND CODPARC = :codparc AND CODEMP = :codemp
             │          AND TIPMOV = 'O' AND PENDENTE = 'S'
             │     ├─ 1 valor > 0 ──► usa
             │     ├─ 2+ valores ───► NÃO atua + log     (documento ambíguo, encerra)
             │     └─ 0 linhas / sem xPed / valor 0 ──► cai para 3
             │
             ├─ 3. <entrega><CNPJ> — o recebedor, dito pelo emitente
             │     SELECT CODPARC FROM TGFPAR
             │        WHERE só-dígitos(CGC_CPF) = :cnpj AND ATIVO = 'S'
             │     ├─ 1 parceiro ──► usa
             │     ├─ 2+ ─────────► NÃO atua + log        (cadastro duplicado, encerra)
             │     └─ 0 / sem grupo <entrega> ──► cai para 4
             │
             ├─ 4. CNPJ no <infAdic><infCpl> — DV validado, sem emit/dest
             │     TGFPAR: ATIVO='S', FORNECEDOR='S', TRANSPORTADORA='N'
             │     ∩ com os CODPARCDEST dos pedidos pendentes do fornecedor
             │     ├─ 1 na interseção ──► usa
             │     ├─ 2+ ──────────────► NÃO atua + log (encerra)
             │     └─ 0 / sem CNPJ / sem pedido ──► cai para 5
             │
             ├─ 5. fallback: SELECT NUNOTA, NUMNOTA, CODPARCDEST FROM TGFCAB
             │        WHERE CODPARC = :codparc AND CODEMP = :codemp
             │          AND TIPMOV = 'O' AND PENDENTE = 'S' AND CODPARCDEST > 0
             │        ORDER BY DTNEG, NUNOTA        ← o pendente mais antigo
             │     ├─ UNICO ──► usa só se todos convergirem   (default)
             │     ├─ ANTIGO ─► usa a 1ª linha                (regra do Portal)
             │     └─ OFF ────► não atua + log
             │
             └─ vo.setProperty("CODPARCDEST", valor)
       │
       ▼
[Nota nasce com o destinatário correto]
       │
       ▼
[Portal segue o fluxo normal — nenhum UPDATE de CODPARCDEST existe]
```

**Por que só no `beforeInsert`.** A trigger standard que gera o `ORA-20101` atua **apenas em
`UPDATE`** e sua condição depende da TOP e da mudança de valor, não do estado da nota — não
avalia `STATUSNOTA`, `PENDENTE`, existência de itens nem movimentação de estoque já gravada.
Portanto **não existe janela de `UPDATE` em momento algum do ciclo**, antes ou depois de
confirmar, e uma nota que nasce com o campo preenchido não passa por validação nenhuma. Todo
gancho posterior — `afterInsert` do item, `TGFVAR` já gravada, `beforeCommit` — está
descartado por evidência.

O comportamento acima foi levantado lendo a trigger na própria base. O texto dela é código do
ERP e não é reproduzido aqui.

---

## Estrutura do Projeto

```
src/main/java/br/com/conceito/parcdest/
├── event/
│   └── ParceiroDestinatarioTracer.java   Evento Programável (TGFCAB). Tracing + gancho.
├── service/
│   └── ResolvedorParceiroDestinatario.java   Regra de resolução e fail-safe.
├── repository/
│   └── PortalXmlRepository.java             As quatro consultas. Somente leitura.
└── util/
    ├── XmlNfe.java        Extrai xPed (ZC e I05), <entrega> e CNPJ do <infCpl>.
    ├── Configuracao.java  Feature flag e escopo, relidos do disco a cada 60s.
    └── Log.java           Arquivo no Repositório de Arquivos do Sankhya.

src/test/java/.../util/
├── ConfiguracaoTest.java   10 testes
└── XmlNfeTest.java         16 testes

config/parcdest.properties      Arquivo de configuração pronto para subir ao Repositório.
xml/preparar_xml_simulacao.py   Adapta um XML modelo para importar em outra base.
```

`event/` é o único ponto de contato com o ciclo de persistência do ERP. `util/` não conhece
evento nem TGFCAB, e roda fora do servidor — por isso é a única camada com teste unitário.

---

## Configuração

Arquivo pronto em **`config/parcdest.properties`**. Subir pelo Repositório de Arquivos para
`personalizacao/parcdest/` — mesma pasta onde o log é gravado. Relido a cada 60s, sem
restart. Ausente = default seguro (`tracing=ON`, `workaround=OFF`).

```properties
tracing=ON            # registra eventos da TGFCAB no log
workaround=OFF        # FASE 2. Preenche CODPARCDEST no beforeInsert.
tops=1419             # TOPs no escopo, separadas por vírgula
fallback=ANTIGO       # quando o xPed não resolve: ANTIGO | UNICO | OFF
camposExtras=         # campos extras da TGFCAB a registrar no log
```

| `fallback` | Comportamento quando nem o `xPed` nem o `<entrega>` resolvem |
|---|---|
| `UNICO` (default) | Só atua se todos os pedidos pendentes do parceiro apontarem para o mesmo destinatário. Havendo divergência, não grava. |
| `ANTIGO` | Usa o pedido pendente mais antigo do parceiro — a regra do botão **Ligar pedidos mais antigos** do Portal. Grava mesmo sob ambiguidade. |
| `OFF` | Nenhuma inferência. Só as três origens declaradas no documento. |

Valor irreconhecível cai em `UNICO`: erro de digitação no properties não pode abrir a opção
mais arriscada.

Arquivo, e não parâmetro de sistema, por decisão de arquitetura: desligar não
exige alteração de banco, de objeto standard, nem recompilação.

---

## Decisões Técnicas

### 1. `beforeInsert` é o único gancho possível

Não por preferência de projeto. A trigger é `BEFORE UPDATE` e sua condição ignora o estado
da nota — ver "Arquitetura" acima. O texto da trigger foi lido de `ALL_SOURCE`, não deduzido.

### 2. A fonte é o `xPed`, não a `TGFVAR`

A `TGFVAR` liga nota e pedido, mas nasce junto com o **item**, e o item vem depois do
cabeçalho. O Monitor de Consultas mostrou que o pedido só é lido **1.389 comandos depois**
do `INSERT INTO TGFCAB`. No `beforeInsert` não há vínculo algum: `NUNOTAORIG` não existe na
TGFCAB e `NUMPEDIDO`/`NUMPEDIDO2` ficam vazios em todo o fluxo.

O que sobra — e basta — é a `CHAVENFE`, presente no VO desde o `BEFORE_INSERT`. Ela endereça
o registro da `TGFIXN`, gravado ~12 minutos antes da nota existir, com o XML íntegro.

### 3. Cinco origens, e cada uma sabe o quanto vale

O `xPed` é o mesmo campo que o próprio Portal usa para casar o pedido, apenas antecipado —
enquanto ele resolver, é ele que manda. Só que **o fornecedor não é obrigado a preencher o
`xPed`**, e quando não preenche o campo chega vazio ou com um número que não existe na base.
Foi o caso do teste com `xPed=4`: `motivo=PEDIDO_NAO_ENCONTRADO`, nota nascida com `0`, e o
`ORA-20101` de volta na correção manual.

Daí a cadeia:

| # | Origem | Grupo no layout | Natureza |
|---|---|---|---|
| 1 | `TGFIXN.CODPARCDEST` | — | Escolha explícita do usuário na tela do Portal. |
| 2 | `xPed` do documento **e de cada item** | ZC01 e I05 | Determinística. O emitente diz qual é o pedido. |
| 3 | `<entrega>` CNPJ/CPF do recebedor | G | Determinística. O emitente diz para quem entregou. |
| 4 | CNPJ no `<infCpl>`, fornecedor não-transportadora, **∩** destinatário de pedido pendente | Z (texto livre) | Duas fontes independentes concordando. |
| 5 | Pedido pendente do parceiro | — | Inferência. |

**O `xPed` é lido em dois lugares.** O grupo `<compra>` (ZC01) traz o pedido do documento
inteiro; o grupo I05 traz `xPed` e `nItemPed` **dentro de cada `<det><prod>`**. Boa parte dos
ERPs emissores preenche só o segundo — é o campo que existe justamente para o comprador casar
nota com pedido. Ler apenas `<compra>` descarta esses documentos sem necessidade.

**O grupo `<entrega>` é a fonte mais forte que existe para esse campo.** Ele só aparece
quando a entrega é em endereço diferente do destinatário — exatamente o caso do estoque de
terceiros — e, quando aparece, o CNPJ/CPF do recebedor é **obrigatório**. Ou seja: o
documento identifica o parceiro destinatário sem depender de pedido nenhum. E a obrigação é
fiscal, não comercial: fornecedor que entrega em terceiro e não preenche o grupo G está
irregular, o que é uma alavanca de cobrança bem mais firme que pedir `xPed`.

**A origem 4 existe porque nem todo emitente usa o grupo G.** Parte deles descreve o
recebedor em texto livre: *"MERC. ENVIADA PARA (...) CNPJ:32.787.025/0001-73 IE:258977566"*.
Garimpar texto livre é heurística — mas CNPJ tem dígito verificador, e o cruzamento fecha o
resto:

1. Extrai sequências de 14 dígitos do `infCpl` (com ou sem máscara).
2. Valida o DV módulo 11 — número solto, IE e chave de acesso caem fora sozinhos.
3. Descarta o CNPJ do emitente e o do destinatário, ambos lidos do próprio XML.
4. Casa contra `TGFPAR` exigindo `ATIVO='S'`, **`FORNECEDOR='S'` e `TRANSPORTADORA='N'`**.
5. **Intersecta com os destinatários dos Pedidos de Compra pendentes daquele fornecedor.**

Nenhuma das duas pontas decide sozinha. Texto livre pode citar transportadora, matriz,
filial. Pedido pendente sozinho é o chute que a origem 5 assume. Juntas, uma confirma a
outra — e resolvem justamente o caso em que a origem 5 não saberia escolher: com dois pedidos
pendentes para destinos diferentes, o CNPJ do texto diz qual é.

As duas marcações fecham o falso positivo natural desse caminho: transportadora citada na
observação é o que mais aparece em texto livre. `FORNECEDOR='S'` já elimina a maioria;
`TRANSPORTADORA='N'` pega o cadastro que acumula as duas marcações. O filtro vale **só** para
esta origem — no grupo `<entrega>` o recebedor vem em campo próprio, sem concorrência.

Sem flag própria: a origem só atua quando as duas fontes concordam, e `workaround=OFF` já
desliga tudo.

A origem 5 é a única inferência pura, e por isso é a única governada por flag (`fallback`), a
única que registra `pendentes=` e `divergentes=` no log, e a única que pode ser desligada sem
desligar o componente.

**Ambiguidade encerra a cadeia, não avança para a origem seguinte.** Se os `xPed` do
documento casam pedidos com destinatários diferentes, ou se o CNPJ de entrega casa dois
cadastros ativos, o componente não atua. Descer para a próxima origem nesse caso trocaria uma
recusa por um chute.

### 4. `TIPMOV='O'` e `PENDENTE='S'` — a mesma regra do Portal

O Portal só vincula pedido digitado, confirmado e pendente. O workaround usa o mesmo
conjunto, por dois motivos: coerência (não escolher um pedido que o standard descartaria) e
desambiguação (número de pedido se repete entre anos).

`PENDENTE='S'` dá **idempotência de graça**: pedido já atendido vira `'N'` e sai do
conjunto, então reimportar não reaplica nada. Sem controle de estado próprio.

### 5. Destinatário vazio é validado no resultado, não filtrado no `WHERE`

`CODPARCDEST > 0` no `WHERE` esconderia ambiguidade: com dois pedidos pendentes, um com
destinatário e outro zerado, gravaria o preenchido com falsa confiança. Fora do `WHERE`, o
`DISTINCT` devolve dois valores, e o fail-safe assume: não atua.

### 6. Conexão: `PersistenceEvent.getJdbcWrapper()`

O próprio evento entrega o `JdbcWrapper` da transação corrente. Não abre `JapeSession`, não
usa `EntityFacadeFactory`, não cria conexão nova. As consultas rodam na mesma conexão
que está criando a nota, e nenhuma das tabelas lidas é escrita pela transação antes do
`INSERT` do cabeçalho.

### 7. Sem parser de XML

O único dado necessário é o `<xPed>` dentro de `<compra>`, e o CLOB passa de 9 KB. `indexOf`
resolve em uma passada, dentro da transação do usuário. Um `DocumentBuilder` custaria mais
do que a consulta inteira.

### 8. Falha nunca derruba a importação

Toda exceção do workaround é engolida e registrada. A nota entra com zero —
exatamente o comportamento sem o componente. Um workaround que quebra a operação é pior que
o problema que resolve.

### 9. JARs reais em vez de stubs

O projeto começou com stubs `compileOnly` para `br.com.sankhya.jape.*`, escritos a partir do
bytecode. Com o `jape-4.35.jar` extraído do deploy do WildFly, os stubs foram removidos — e
o código compilou contra as classes reais **sem alteração**, o que validou retroativamente as
assinaturas que haviam sido inferidas.

### 10. Feature flag por arquivo, não por parâmetro de sistema

Desligar não pode exigir alteração de banco, de objeto standard, nem recompilação.

---

## Investigação — como cada decisão foi comprovada

Nada aqui foi deduzido. A sequência de testes, em ordem:

| # | Ação | O que provou |
|---|---|---|
| 1 | Tracer instalado, importação real | O Portal **dispara** o evento. O gancho serve. |
| 2 | Leitura do log | Nota nasce `CODPARCDEST=0`; **nenhum** evento do Portal altera o campo (`ModifingFields.isModifing` sempre falso). Refutou a hipótese original de que o Portal aplicaria o valor tarde demais. |
| 3 | `TGFVAR` consultada | O vínculo existe **depois**, e o pedido tem o destinatário certo. Fonte determinística confirmada. |
| 4 | `ALL_SOURCE` da trigger | `BEFORE UPDATE` apenas; condição não olha estado. Fechou o gancho no `beforeInsert` e descartou todos os posteriores. |
| 5 | `camposExtras` ligado | `CHAVENFE` presente no `BEFORE_INSERT`; `NUNOTAORIG` e `ORIGEM` inexistentes. |
| 6 | Dicionário de dados | `TGFIXN` tem `CHAVEACESSO`, `XML`, `CONFIG` e `CODPARCDEST`. |
| 7 | `TGFIXN` consultada | `CODPARCDEST` da TGFIXN vem **vazio** — o Portal não preenche. `CONFIG` guarda o relatório de conferência, não o pedido. Sobrou o XML. |
| 8 | `DBMS_LOB` no CLOB | `<compra><xPed>1</xPed></compra>` presente e íntegro na posição 4904. |
| 9 | **Monitor de Consultas** | Ordem real: o pedido só é lido no comando 1452; o `INSERT INTO TGFCAB` é o 63. Provou que não há vínculo no `beforeInsert`. |
| 10 | Pedido consultado vinculado × desvinculado | `PENDENTE` alterna `S`/`N` com o vínculo. Deu o filtro e a idempotência. |
| 11 | `javap` comparativo 4.35 × 4.36 | API só cresce. Compilar contra 4.35 é seguro nas duas bases. |
| 12 | **`workaround=ON`, importação real** | Nota nasce com o destinatário. Nenhum `UPDATE`, nenhum `ORA-20101`. |
| 13 | `xPed` trocado para número inexistente | `PEDIDO_NAO_ENCONTRADO`, nota com `0`, `ORA-20101` de volta na correção manual. Provou que o `xPed` sozinho não cobre. |
| 14 | Layout NF-e conferido | `xPed` existe em dois grupos (ZC01 e I05) e o grupo G (`<entrega>`) traz CNPJ obrigatório do recebedor. Duas fontes que não estavam sendo lidas. |
| 15 | Observação da nota lida na Central | O emitente descreve o recebedor em texto livre, com CNPJ, e **não** usa o grupo G. |
| 16 | `TGFPAR` consultada | O CNPJ da observação **é** o parceiro destinatário. `CGC_CPF` sem máscara; destinatário com `FORNECEDOR='S'` e `TRANSPORTADORA='N'`. |
| 17 | XML com `xPed` **e** `nItemPed` no item | O Portal passa a **vincular o pedido sozinho** — aba Pedidos com `Vinculado 8.002,64 / Não vinculado 0,0000`, sem clicar em nada. `nItemPed` é a âncora que faltava, porque a `TGFVAR` é item × item. |
| 18 | **O mesmo XML com `workaround=OFF`** | Nota nasce `CODPARCDEST=0` e **permanece 0** no ciclo inteiro. O Portal vincula o pedido e ainda assim não propaga o destinatário. |
| 19 | XML com grupo `<entrega>`, pedido do `xPed` inexistente | Origem 3 resolve pelo CNPJ do recebedor. E como o pedido estava **não pendente**, as origens 4 e 5 nem podiam agir — o filtro `PENDENTE='S'` foi exercitado por acidente e segurou. |
| 20 | `TGFIXN.CODPARCDEST` gravado entre importar e processar | Origem 1 atua e **encerra a cadeia numa linha só**. Provou a precedência: o XML tinha `xPed` no item e nem foi lido. |

Cinco caminhos homologados em base real:

```
nota 100   workaround=RESOLVIDO origem=XPED xPeds=[1] CODPARCDEST=8
nota 102   workaround=xPed motivo=PEDIDO_NAO_ENCONTRADO xPeds=[4]
           workaround=RESOLVIDO origem=PEDIDO_MAIS_ANTIGO NUNOTA=96 CODPARCDEST=8 pendentes=1
nota 103   workaround=xPed motivo=PEDIDO_NAO_ENCONTRADO xPeds=[4]
           workaround=entrega motivo=XML_SEM_ENTREGA
           workaround=RESOLVIDO origem=INFCPL_PEDIDO CODPARCDEST=8 pendentes=1
nota 104   workaround=RESOLVIDO origem=XPED xPeds=[1] CODPARCDEST=8      (xPed do item, grupo I05)
nota 107   workaround=xPed motivo=PEDIDO_NAO_ENCONTRADO xPeds=[4]
           workaround=RESOLVIDO origem=ENTREGA doc=**********0173 CODPARCDEST=8
nota 110   workaround=RESOLVIDO origem=PORTAL_TGFIXN CODPARCDEST=1
```

E o caminho de não-atuação completo, quando nenhuma origem resolve (notas 108 e 109):

```
workaround=xPed     motivo=PEDIDO_NAO_ENCONTRADO xPeds=[4] CODPARC=7 CODEMP=1
workaround=entrega  motivo=XML_SEM_ENTREGA
workaround=infCpl   motivo=SEM_CNPJ_NO_TEXTO
workaround=SKIP     motivo=SEM_PEDIDO_PENDENTE CODPARC=7 CODEMP=1
```

Nota nasce com `0`, sem erro, sem `ORA-20101`. 8 ms.

Em todos, zero `UPDATE` de `CODPARCDEST` em toda a transação e zero `ORA-20101`. Os pares
`BEFORE/AFTER_UPDATE` seguintes já nascem com o valor certo.

E o contraexemplo que fecha o diagnóstico — mesmo documento, `workaround=OFF`:

```
nota 105   BEFORE_INSERT  CODPARCDEST=0
           AFTER_INSERT   CODPARCDEST=0
           BEFORE_UPDATE  CODPARCDEST=0
           AFTER_UPDATE   CODPARCDEST=0
```

Seis linhas, nenhuma `workaround=`. **O Portal vinculou o pedido automaticamente e ainda
assim entregou a nota com o campo zerado.** Documento fiscal completo, vínculo feito pelo
próprio Portal, pedido de origem com o destinatário preenchido, nenhuma ação do usuário — e o
campo continua `0`. É a reprodução mais limpa do defeito, e a que serve de anexo ao chamado.

Custo `BEFORE_INSERT` → `AFTER_INSERT`: **~50 ms**, estável independente de quantas origens a
cadeia percorre. A primeira medição de 330 ms era o custo de aquecimento da primeira
importação após subir o JAR.

---

## Tabelas Envolvidas

| Tabela | Uso |
|---|---|
| `TGFCAB` | Cabeçalho da nota (entidade do evento) e do Pedido de Compra (consulta) |
| `TGFIXN` | Arquivo do Portal de Importação de XML: `CHAVEACESSO`, `XML` (CLOB), `CODPARCDEST` |
| `TGFPAR` | Parceiro pelo CNPJ/CPF do `<entrega>` ou do `<infCpl>`: `CGC_CPF`, `ATIVO`, `FORNECEDOR`, `TRANSPORTADORA` |
| `TGFTOP` | Origem da condição da trigger: `ATUALESTTERC`, `CODCFO_ENTRADA` |
| `TGFVAR` | Vínculo nota × pedido. Evidência e reconciliação, nunca fonte no ponto de extensão |
| `TSIPAR` | `CONCNPJIEIMPXML`, `TOPIMPORTXML` — parâmetros do Portal |

A trigger standard que protege o estoque de terceiros **não é alterada nem desabilitada**. Ela
possui desvios nativos que permitiriam suprimir a validação; usá-los foi descartado por
princípio, porque desligariam a validação da `TGFCAB` inteira na sessão e não apenas o campo
em questão. O componente trabalha *fora* do alcance da trigger, não *contra* ela.

---

## JARs do Classpath (compileOnly)

```
libs/SankhyaW-extensions.jar        EventoProgramavelJava
libs/mge-modelcore-4.35b491.jar     SWRepositoryUtils (via reflection)
libs/jape-4.35.jar                  jape.* — do deploy do WildFly do cliente
libs/referencia/jape-4.36b134.jar   fora do classpath, só para conferência com javap
```

**`libs/` não é versionado.** São binários proprietários da Sankhya, vindos do build e do
deploy do cliente. São `compileOnly`, não entram no JAR entregue e não são redistribuídos.
Para compilar, copie-os manualmente. O `jape` sai do servidor:

```powershell
Get-ChildItem -Path <wildfly> -Recurse -Filter jape-*.jar |
  Where-Object { $_.FullName -notlike '*to-be-deleted*' }
```

---

## Setup (homologação / produção)

1. `./gradlew build` → `build/libs/ParceiroDestinatarioPortalXML-1.0.jar`
2. Subir o JAR no **Módulo Java** do ERP.
3. Cadastrar o Evento Programável:
   - Entidade: **CabecalhoNota** (`TGFCAB`)
   - Classe: `br.com.conceito.parcdest.event.ParceiroDestinatarioTracer`
4. Subir `config/parcdest.properties` no Repositório de Arquivos, em
   `personalizacao/parcdest/` — criar a pasta se não existir. Sem ele valem os defaults,
   e o default de `workaround` é `OFF`.
5. Começar com `workaround=OFF`. Ligar só depois de confirmar o tracing na base alvo.

Log em `<SW Repository>/personalizacao/parcdest/logAAAA-MM-DD-PARCDEST-TRACE.txt`.

---

## Verificação (Smoke Test)

1. `workaround=OFF`, importar um XML com `<compra><xPed>` apontando para pedido pendente com
   destinatário. Esperado: nota com `CODPARCDEST=0` e log registrando o ciclo.
2. `workaround=ON`, esperar 60s, apagar a nota, importar de novo.
   Esperado no log:
   ```
   workaround=RESOLVIDO origem=XPED xPeds=[<n>] CODPARCDEST=<n>
   workaround=APLICADO  NUNOTA=<n> ...
   AFTER_INSERT ... CODPARCDEST=<n>
   ```
3. Conferir na Central de Compras que o Parceiro Destinatário veio preenchido, **sem** erro.
4. Confirmar que o log não tem nenhuma linha `OLD.CODPARCDEST` para a nota nova — se tiver,
   alguém tentou um `UPDATE` e o desenho falhou.

5. Repetir com o `xPed` apontando para um pedido inexistente. Esperado: o `xPed` desiste, o
   fallback assume, e a nota nasce preenchida do mesmo jeito:
   ```
   workaround=xPed     motivo=PEDIDO_NAO_ENCONTRADO xPeds=[4] CODPARC=7 CODEMP=1
   workaround=entrega  motivo=XML_SEM_ENTREGA
   workaround=RESOLVIDO origem=PEDIDO_MAIS_ANTIGO NUNOTA=96 NUMNOTA=1 CODPARCDEST=8 pendentes=1 divergentes=N
   ```

O log distingue o que é desistência de etapa do que é não-atuação do componente:

```
workaround=xPed  motivo=...     ← a etapa 2 desistiu, o fallback ainda vai rodar
workaround=SKIP  motivo=...     ← o componente não atua, a nota nasce com 0
```

Desistências de etapa — a cadeia continua na origem seguinte:

```
workaround=xPed     motivo=XML_SEM_XPED
workaround=xPed     motivo=XPED_NAO_NUMERICO xPeds=[...]
workaround=xPed     motivo=PEDIDO_NAO_ENCONTRADO xPeds=[...] CODPARC=... CODEMP=...
workaround=xPed     motivo=PEDIDO_SEM_DESTINATARIO xPeds=[...]
workaround=entrega  motivo=XML_SEM_ENTREGA
workaround=entrega  motivo=PARCEIRO_NAO_CADASTRADO doc=**********0144
workaround=infCpl   motivo=SEM_CNPJ_NO_TEXTO
workaround=infCpl   motivo=SEM_PEDIDO_PARA_CONFIRMAR cnpjs=1
workaround=infCpl   motivo=CNPJ_SEM_PEDIDO_CORRESPONDENTE cnpjs=2
```

Não-atuação do componente — a nota nasce com `0`:

```
workaround=SKIP motivo=SEM_CHAVENFE
workaround=SKIP motivo=SEM_PARCEIRO_OU_EMPRESA
workaround=SKIP motivo=XML_NAO_ENCONTRADO chave=...
workaround=SKIP motivo=DESTINATARIOS_DIVERGENTES xPeds=[...] candidatos=[...]
workaround=SKIP motivo=PARCEIROS_DUPLICADOS doc=... candidatos=[...]
workaround=SKIP motivo=INFCPL_DIVERGENTE candidatos=[...]
workaround=SKIP motivo=FALLBACK_DESLIGADO
workaround=SKIP motivo=SEM_PEDIDO_PENDENTE CODPARC=... CODEMP=...
workaround=SKIP motivo=PENDENTES_DIVERGENTES CODPARC=... pedidos=... modo=UNICO
```

O CNPJ/CPF sai mascarado no log — os quatro últimos dígitos bastam para o suporte achar o
registro.

---

## Próximos Passos

Roteiro detalhado é documento interno. Em resumo:

1. **Ambiguidade real.** Dois pedidos pendentes do mesmo fornecedor com destinatários
   diferentes. É onde a origem 4 desempata o que o fallback recusaria, e o único cenário
   com risco de gravar o parceiro errado. Ainda não exercitado.
2. Validar estoque de terceiros, fiscal e financeiro na nota resultante.
3. **Homologar em 4.35b809**, a versão do cliente, reconfirmando trigger, `TGFIXN` e o
   formato de `TIPMOV`/`PENDENTE`.
4. Quando a Sankhya publicar correção oficial: `workaround=OFF` e remover o componente.

**Todas as cinco origens já foram exercitadas em base real** — notas 110, 100, 104, 107, 103
e 102 — junto com o caminho de não-atuação completo (108, 109) e o kill switch em runtime sem
restart (105).

---

## Histórico de Mudanças Relevantes

| Data | Mudança |
|---|---|
| 20/08/2026 | Fase 1 concluída: tracing provou o ciclo e refutou a hipótese da seção 17 |
| 20/08/2026 | Trigger lida de `ALL_SOURCE`; `beforeInsert` confirmado como único gancho |
| 20/08/2026 | Monitor de Consultas mapeou a ordem real dos comandos do Portal |
| 20/08/2026 | Estrutura de pacotes reorganizada no modelo `event/service/repository/util` |
| 20/08/2026 | `stubs/` removido, substituído pelo `jape-4.35.jar` real do servidor |
| 20/08/2026 | Fase 2 implementada e homologada em simulação 4.36b134 (`origem=XPED`) |
| 20/08/2026 | `xPed` inexistente reproduziu o bug; fallback por pedido pendente adicionado e homologado |
| 21/08/2026 | Origens `xPed` de item (I05) e grupo `<entrega>` adicionadas; default do `fallback` passou a `UNICO` |
| 21/08/2026 | Origem `INFCPL_PEDIDO` adicionada e homologada, com filtro `FORNECEDOR='S'` / `TRANSPORTADORA='N'` |
| 21/08/2026 | `nItemPed` provado como a âncora do vínculo automático do Portal |
| 21/08/2026 | Provado que nem com vínculo automático o Portal propaga o destinatário; kill switch homologado |
| 21/08/2026 | Origem `ENTREGA` homologada; filtro `PENDENTE='S'` exercitado com pedido já atendido |
| 21/08/2026 | Origem `PORTAL_TGFIXN` homologada; cadeia completa exercitada em base real |

---

## Autoria

Felipe Cavalcante Barbosa — consultor de implantação Sankhya.

Componente desenvolvido em projeto de implantação. Nomes de cliente, parceiros e dados de nota
foram removidos desta versão pública; a análise funcional e o roteiro de testes permanecem
como documentos internos do projeto.
